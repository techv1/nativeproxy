package com.techpremium.miniproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ProxyService — Foreground service that runs an HTTP/HTTPS forward proxy.
 *
 * Supports:
 *  • Plain HTTP forwarding  (reads request, opens connection to upstream, pipes data)
 *  • HTTPS CONNECT tunneling (replies 200 tunnel established, then blindly pipes both streams)
 *
 * Threading model:
 *  • A single acceptor thread blocks on ServerSocket.accept()
 *  • Each accepted connection is dispatched to a cached thread pool
 *  • Each CONNECT tunnel spawns two more threads for bidirectional piping
 *  • On stop, the ServerSocket is closed (unblocks accept()) and the pool is shut down gracefully
 */
class ProxyService : Service() {

    // ── Companion / constants ────────────────────────────────────────────────
    companion object {
        private const val TAG = "NativeProxy"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "proxy_channel"

        const val ACTION_START = "com.techpremium.miniproxy.ACTION_START"
        const val ACTION_STOP  = "com.techpremium.miniproxy.ACTION_STOP"

        const val EXTRA_BIND_IP = "extra_bind_ip"
        const val EXTRA_PORT    = "extra_port"

        const val ACTION_STATUS_RUNNING = "com.techpremium.miniproxy.STATUS_RUNNING"
        const val ACTION_STATUS_STOPPED = "com.techpremium.miniproxy.STATUS_STOPPED"

        private const val SOCKET_TIMEOUT_MS   = 30_000   // 30 s client read timeout
        private const val UPSTREAM_TIMEOUT_MS = 30_000   // 30 s upstream connect/read timeout
        private const val PIPE_BUFFER_SIZE    = 8_192    // 8 KB pipe buffer

        /** Allows MainActivity to quickly check if the service is running. */
        @Volatile var isRunning: Boolean = false
            private set
    }

    // ── State ────────────────────────────────────────────────────────────────
    private var serverSocket: ServerSocket? = null
    private val running    = AtomicBoolean(false)
    private val connCount  = AtomicInteger(0)

    private val threadPool = ThreadPoolExecutor(
        0,
        200, // Safe upper bound for aggressive parallel scraping
        60L,
        TimeUnit.SECONDS,
        SynchronousQueue<Runnable>(),
        { r -> Thread(r).apply { isDaemon = true; name = "proxy-worker-${id}" } },
        ThreadPoolExecutor.DiscardPolicy()
    )
    private var acceptorThread: Thread? = null

    private var bindIp: String = "0.0.0.0"
    private var bindPort: Int  = 8080

    // ── Service lifecycle ────────────────────────────────────────────────────
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                bindIp   = intent.getStringExtra(EXTRA_BIND_IP) ?: "0.0.0.0"
                bindPort = intent.getIntExtra(EXTRA_PORT, 8080)
                startProxyForeground()
            }
            ACTION_STOP  -> stopProxy()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }

    // ── Start logic ──────────────────────────────────────────────────────────
    private fun startProxyForeground() {
        if (running.getAndSet(true)) return   // already running

        val notification = buildNotification(bindIp, bindPort)

        // Android 14+ requires the explicit service type overload
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isRunning = true
        broadcastStatus(ACTION_STATUS_RUNNING)

        acceptorThread = Thread({
            runAcceptLoop()
        }, "proxy-acceptor").apply { isDaemon = true; start() }
    }

    // ── Accept loop ──────────────────────────────────────────────────────────
    private fun runAcceptLoop() {
        try {
            val ss = ServerSocket(bindPort, 50,
                java.net.InetAddress.getByName(bindIp))
            ss.soTimeout = 0   // block indefinitely until close()
            serverSocket = ss
            Log.i(TAG, "Proxy listening on $bindIp:$bindPort")

            while (running.get()) {
                val client: Socket = try {
                    ss.accept()
                } catch (e: IOException) {
                    if (!running.get()) break   // normal shutdown
                    Log.w(TAG, "accept() error: ${e.message}")
                    continue
                }

                connCount.incrementAndGet()
                threadPool.submit {
                    try {
                        handleClient(client)
                    } finally {
                        connCount.decrementAndGet()
                        client.closeQuietly()
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "AcceptLoop fatal: ${e.message}", e)
        } finally {
            Log.i(TAG, "Accept loop exited")
        }
    }

    // ── Per-client handler ────────────────────────────────────────────────────
    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            val clientIn  = client.getInputStream()
            val clientOut = client.getOutputStream()

            // Read the first line (request line)
            val firstLine = readLine(clientIn) ?: return
            if (firstLine.isEmpty()) return

            val parts = firstLine.trim().split(" ")
            if (parts.size < 3) return

            val method = parts[0].uppercase()

            if (method == "CONNECT") {
                handleConnect(parts[1], clientIn, clientOut)
            } else {
                handleHttp(method, parts[1], parts[2], firstLine, clientIn, clientOut)
            }
        } catch (e: SocketTimeoutException) {
            // Client was idle — normal, just close
        } catch (e: IOException) {
            Log.d(TAG, "Client IO: ${e.message}")
        }
    }

    // ── HTTPS CONNECT tunnel ─────────────────────────────────────────────────
    private fun handleConnect(hostPort: String, clientIn: InputStream, clientOut: OutputStream) {
        // Drain remaining request headers
        drainHeaders(clientIn)

        val (host, port) = parseHostPort(hostPort, 443)

        val upstream: Socket = try {
            Socket().apply {
                soTimeout = UPSTREAM_TIMEOUT_MS
                connect(java.net.InetSocketAddress(host, port), UPSTREAM_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "CONNECT upstream failed $host:$port — ${e.message}")
            clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            clientOut.flush()
            return
        }

        // Tell the client the tunnel is open
        clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        clientOut.flush()

        // Bidirectional pipe — two threads, latch to wait for both
        val latch = java.util.concurrent.CountDownLatch(2)
        val upIn   = upstream.getInputStream()
        val upOut  = upstream.getOutputStream()

        threadPool.submit {
            try { pipe(clientIn, upOut) } catch (_: Exception) { } finally {
                upstream.closeQuietly(); latch.countDown()
            }
        }
        threadPool.submit {
            try { pipe(upIn, clientOut) } catch (_: Exception) { } finally {
                upstream.closeQuietly(); latch.countDown()
            }
        }

        latch.await(10, TimeUnit.MINUTES)
        upstream.closeQuietly()
    }

    // ── Plain HTTP forward proxy ──────────────────────────────────────────────
    private fun handleHttp(
        method: String,
        url: String,
        httpVersion: String,
        firstLine: String,
        clientIn: InputStream,
        clientOut: OutputStream
    ) {
        // Parse target host / port from the absolute URL
        val (host, port, path) = parseHttpUrl(url) ?: run {
            clientOut.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
            clientOut.flush()
            return
        }

        val upstream: Socket = try {
            Socket().apply {
                soTimeout = UPSTREAM_TIMEOUT_MS
                connect(java.net.InetSocketAddress(host, port), UPSTREAM_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP upstream failed $host:$port — ${e.message}")
            clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            clientOut.flush()
            return
        }

        try {
            val upIn  = upstream.getInputStream()
            val upOut = upstream.getOutputStream()

            // Forward the request line (rewritten to relative path) + headers + body
            val sb = StringBuilder()
            sb.append("$method $path $httpVersion\r\n")

            // Collect and forward headers (strip Proxy-Connection, add Host if missing)
            var hasHost = false
            var contentLength = -1L
            val headers = mutableListOf<String>()

            while (true) {
                val line = readLine(clientIn) ?: break
                if (line.isEmpty()) break
                val lower = line.lowercase()
                when {
                    lower.startsWith("proxy-connection:") -> continue   // strip
                    lower.startsWith("host:")             -> { hasHost = true; headers += line }
                    lower.startsWith("content-length:")   -> {
                        contentLength = line.substringAfter(':').trim().toLongOrNull() ?: -1L
                        headers += line
                    }
                    else -> headers += line
                }
            }

            if (!hasHost) headers.add(0, "Host: $host")
            headers.forEach { sb.append("$it\r\n") }
            sb.append("\r\n")

            upOut.write(sb.toString().toByteArray(Charsets.ISO_8859_1))

            // Forward body if present
            if (contentLength > 0) {
                val buf = ByteArray(PIPE_BUFFER_SIZE)
                var remaining = contentLength
                while (remaining > 0) {
                    val read = clientIn.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    upOut.write(buf, 0, read)
                    remaining -= read
                }
            }
            upOut.flush()

            // Pipe upstream response back to client
            pipe(upIn, clientOut)
        } finally {
            upstream.closeQuietly()
        }
    }

    // ── Stream utilities ──────────────────────────────────────────────────────
    /** Blindly copies bytes from [src] to [dst] until EOF or error. */
    private fun pipe(src: InputStream, dst: OutputStream) {
        val buf = ByteArray(PIPE_BUFFER_SIZE)
        try {
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                dst.write(buf, 0, n)
                dst.flush()
            }
        } catch (_: IOException) { /* stream closed — normal */ }
    }

    /**
     * Reads a single CRLF (or LF) terminated line from a raw InputStream.
     * Returns null on EOF, empty string on blank line (header section end).
     */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (prev == '\r'.code && sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            prev = b
        }
    }

    /** Drains and discards all HTTP headers (reads until blank line). */
    private fun drainHeaders(input: InputStream) {
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) return
        }
    }

    // ── Parsing helpers ───────────────────────────────────────────────────────
    /** Parses "host:port" or "host" into (host, port). */
    private fun parseHostPort(hostPort: String, defaultPort: Int): Pair<String, Int> {
        return if (hostPort.contains(':')) {
            val idx  = hostPort.lastIndexOf(':')
            val host = hostPort.substring(0, idx)
            val port = hostPort.substring(idx + 1).toIntOrNull() ?: defaultPort
            Pair(host, port)
        } else {
            Pair(hostPort, defaultPort)
        }
    }

    /** Parses an absolute HTTP URL into (host, port, path). */
    private fun parseHttpUrl(url: String): Triple<String, Int, String>? {
        return try {
            val u = java.net.URL(url)
            val host = u.host
            val port = if (u.port == -1) u.defaultPort.takeIf { it > 0 } ?: 80 else u.port
            val path = if (u.file.isNullOrEmpty()) "/" else u.file
            Triple(host, port, path)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot parse URL: $url")
            null
        }
    }

    private fun Socket.closeQuietly() { try { close() } catch (_: Exception) { } }

    // ── Stop logic ────────────────────────────────────────────────────────────
    private fun stopProxy() {
        if (!running.getAndSet(false)) return

        try { serverSocket?.close() } catch (_: Exception) { }
        serverSocket = null

        threadPool.shutdown()
        if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
            threadPool.shutdownNow()
        }

        isRunning = false
        broadcastStatus(ACTION_STATUS_STOPPED)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Proxy stopped")
    }

    // ── Broadcast helper ──────────────────────────────────────────────────────
    private fun broadcastStatus(action: String) {
        sendBroadcast(Intent(action).setPackage(packageName))
    }

    // ── Notification helpers ──────────────────────────────────────────────────
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(ip: String, port: Int): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ProxyService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, ip, port.toString()))
            .setContentIntent(tapIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setSilent(true)
            .build()
    }
}
