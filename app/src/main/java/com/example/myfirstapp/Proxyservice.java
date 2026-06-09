package com.techpremium.miniproxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import java.io.*;
import java.net.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProxyService extends Service {
    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private boolean isRunning = false;
    private static final int NOTIFICATION_ID = 9911;

    private String bindIp = "127.0.0.1";
    private int bindPort = 8080;

    @Override
    public void onCreate() {
        super.onCreate();
        threadPool = Executors.newCachedThreadPool();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            bindIp = intent.getStringExtra("BIND_IP");
            bindPort = intent.getIntExtra("BIND_PORT", 8080);
        }

        launchModernNotification();

        if (!isRunning) {
            isRunning = true;
            threadPool.execute(() -> {
                try {
                    // Bind to specific dynamic IP and Port
                    serverSocket = new ServerSocket(bindPort, 50, InetAddress.getByName(bindIp));
                    while (isRunning) {
                        Socket clientSocket = serverSocket.accept();
                        threadPool.execute(() -> processSocketTraffic(clientSocket));
                    }
                } catch (IOException e) {
                    isRunning = false; // Graceful exit if port is already in use
                }
            });
        }
        return START_STICKY;
    }

    private void processSocketTraffic(Socket clientSocket) {
        try (Socket client = clientSocket;
             BufferedInputStream clientIn = new BufferedInputStream(client.getInputStream());
             OutputStream clientOut = client.getOutputStream()) {

            byte[] buffer = new byte[16384];
            int bytesRead = clientIn.read(buffer);
            if (bytesRead == -1) return;

            String rawHeaders = new String(buffer, 0, bytesRead);
            String[] headerLines = rawHeaders.split("\r\n");
            if (headerLines.length == 0) return;

            String[] requestLine = headerLines[0].split(" ");
            if (requestLine.length < 2) return;

            String method = requestLine[0];
            String destinationUrl = requestLine[1];

            String remoteHost;
            int remotePort;

            if (method.equalsIgnoreCase("CONNECT")) {
                String[] parts = destinationUrl.split(":");
                remoteHost = parts[0];
                remotePort = parts.length > 1 ? Integer.parseInt(parts[1]) : 443;
            } else {
                URL url = new URL(destinationUrl);
                remoteHost = url.getHost();
                remotePort = url.getPort() == -1 ? 80 : url.getPort();
            }

            try (Socket targetSocket = new Socket(remoteHost, remotePort);
                 BufferedInputStream targetIn = new BufferedInputStream(targetSocket.getInputStream());
                 OutputStream targetOut = targetSocket.getOutputStream()) {

                if (method.equalsIgnoreCase("CONNECT")) {
                    clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());
                    clientOut.flush();
                } else {
                    targetOut.write(buffer, 0, bytesRead);
                    targetOut.flush();
                }

                ExecutorService localExecutor = Executors.newFixedThreadPool(2);
                localExecutor.execute(() -> transferDataStream(clientIn, targetOut));
                transferDataStream(targetIn, clientOut);
                localExecutor.shutdownNow();
            }

        } catch (Exception ignored) {}
    }

    private void transferDataStream(InputStream input, OutputStream output) {
        byte[] transferBuffer = new byte[8192];
        int length;
        try {
            while ((length = input.read(transferBuffer)) != -1) {
                output.write(transferBuffer, 0, length);
                output.flush();
            }
        } catch (IOException ignored) {}
    }

    private void launchModernNotification() {
        String CHANNEL_ID = "proxy_core_channel";
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Proxy Status Monitor", NotificationManager.IMPORTANCE_LOW);
            if (manager != null) manager.createNotificationChannel(channel);
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O 
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);

        Notification notification = builder
                .setContentTitle("Mini Proxy Active")
                .setContentText("Listening on " + bindIp + ":" + bindPort)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        try { 
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close(); // Forcibly breaks the .accept() loop
            }
        } catch (IOException ignored) {}
        if (threadPool != null) {
            threadPool.shutdownNow();
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
