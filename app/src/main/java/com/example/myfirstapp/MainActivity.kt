package com.techpremium.miniproxy;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private boolean isServiceRunning = false;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("ProxyPrefs", MODE_PRIVATE);

        Button toggleButton = findViewById(R.id.toggleButton);
        TextView statusText = findViewById(R.id.statusText);
        EditText ipInput = findViewById(R.id.ipInput);
        EditText portInput = findViewById(R.id.portInput);

        // Load saved settings
        ipInput.setText(prefs.getString("BIND_IP", "127.0.0.1"));
        portInput.setText(String.valueOf(prefs.getInt("BIND_PORT", 8080)));

        // Request Android 13+ Notification Permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        toggleButton.setOnClickListener(v -> {
            String targetIp = ipInput.getText().toString().trim();
            String portStr = portInput.getText().toString().trim();

            if (targetIp.isEmpty() || portStr.isEmpty()) {
                Toast.makeText(this, "IP and Port cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }

            int targetPort = Integer.parseInt(portStr);

            Intent intent = new Intent(MainActivity.this, ProxyService.class);
            
            if (!isServiceRunning) {
                // Save settings
                prefs.edit().putString("BIND_IP", targetIp).putInt("BIND_PORT", targetPort).apply();
                
                // Pass settings to Service
                intent.putExtra("BIND_IP", targetIp);
                intent.putExtra("BIND_PORT", targetPort);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
                
                statusText.setText("Proxy Active: " + targetIp + ":" + targetPort);
                statusText.setTextColor(0xFF00FF00); // Green
                toggleButton.setText("STOP PROXY");
                ipInput.setEnabled(false);
                portInput.setEnabled(false);
                isServiceRunning = true;
            } else {
                stopService(intent);
                statusText.setText("Proxy Stopped");
                statusText.setTextColor(0xFFFF0000); // Red
                toggleButton.setText("START PROXY");
                ipInput.setEnabled(true);
                portInput.setEnabled(true);
                isServiceRunning = false;
            }
        });
    }
}
