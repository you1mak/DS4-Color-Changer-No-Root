package com.you1mak.ds4light;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.input.InputManager;
import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.hardware.lights.LightsManager;
import android.hardware.lights.LightsRequest;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;

import java.util.List;

public class Ds4LightService extends Service implements InputManager.InputDeviceListener {
    public static final String ACTION_SET_COLOR = "com.you1mak.ds4light.SET_COLOR";
    public static final String ACTION_STOP = "com.you1mak.ds4light.STOP";
    public static final String ACTION_REFRESH = "com.you1mak.ds4light.REFRESH";
    public static final String EXTRA_COLOR = "color";

    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "ds4_light_service";
    private static final String PREFS = "state";
    private static final String PREF_COLOR = "color";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private InputManager inputManager;
    private InputDevice selectedDevice;
    private LightsManager lightsManager;
    private LightsManager.LightsSession lightsSession;
    private Light rgbLight;
    private int color = Color.rgb(0, 0, 64);

    private final Runnable reapplyRunnable = this::applyToCurrentDevice;

    @Override
    public void onCreate() {
        super.onCreate();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        color = prefs.getInt(PREF_COLOR, Color.rgb(0, 0, 64));

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Waiting for DualShock 4"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);

        inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (inputManager != null) {
            inputManager.registerInputDeviceListener(this, handler);
        }
        scanForDs4();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_REFRESH.equals(action)) {
                scanForDs4();
                return START_STICKY;
            }
            if (ACTION_STOP.equals(action)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACTION_SET_COLOR.equals(action) && intent.hasExtra(EXTRA_COLOR)) {
                color = intent.getIntExtra(EXTRA_COLOR, color);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_COLOR, color).apply();
                applyToCurrentDevice();
            }
        }
        return START_STICKY;
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, Ds4LightService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void setColor(Context context, int color) {
        Intent intent = new Intent(context, Ds4LightService.class)
                .setAction(ACTION_SET_COLOR)
                .putExtra(EXTRA_COLOR, color);
        context.startService(intent);
    }

    public static void refresh(Context context) {
        Intent intent = new Intent(context, Ds4LightService.class).setAction(ACTION_REFRESH);
        context.startService(intent);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, Ds4LightService.class));
    }

    private void scanForDs4() {
        handler.post(() -> {
            InputDevice best = null;
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice device = InputDevice.getDevice(id);
                if (device == null || !isLikelyDs4(device)) continue;
                if (findRgbLight(device) != null) {
                    best = device;
                    break;
                }
                if (best == null) best = device;
            }
            if (best != null) {
                attach(best);
            } else {
                updateNotification("Waiting for DualShock 4");
            }
        });
    }

    private boolean isLikelyDs4(InputDevice device) {
        if (device.getVendorId() == 0x054C && device.getProductId() == 0x05C4) {
            return true;
        }
        String name = device.getName() == null ? "" : device.getName().toLowerCase();
        return name.contains("dualshock 4") || name.contains("wireless controller") || name.contains("playstation 4 controller");
    }

    private Light findRgbLight(InputDevice device) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null;
        List<Light> lights = device.getLightsManager().getLights();
        for (Light light : lights) {
            if (light.hasRgbControl()) return light;
        }
        return null;
    }

    private void attach(InputDevice device) {
        handler.post(() -> {
            if (selectedDevice != null && selectedDevice.getId() == device.getId() && lightsSession != null) {
                applyToCurrentDevice();
                return;
            }
            closeSessionSafely();
            selectedDevice = device;
            lightsManager = device.getLightsManager();
            rgbLight = findRgbLight(device);
            if (rgbLight == null) {
                updateNotification("DS4 found, but no RGB light is exposed");
                return;
            }
            try {
                lightsSession = lightsManager.openSession();
                updateNotification("Connected: " + safeName(device));
                applyToCurrentDevice();
            } catch (Throwable t) {
                lightsSession = null;
                updateNotification("DS4 found, light session failed");
            }
        });
    }

    private void applyToCurrentDevice() {
        if (selectedDevice == null || lightsSession == null || rgbLight == null) {
            scanForDs4();
            return;
        }
        try {
            LightState state = new LightState.Builder().setColor(color).build();
            LightsRequest request = new LightsRequest.Builder()
                    .addLight(rgbLight, state)
                    .build();
            lightsSession.requestLights(request);
            updateNotification("Active: " + safeName(selectedDevice) + "  #" + String.format("%06X", color & 0xFFFFFF));
        } catch (Throwable t) {
            handler.removeCallbacks(reapplyRunnable);
            handler.postDelayed(reapplyRunnable, 500);
        }
    }

    private void closeSessionSafely() {
        if (lightsSession != null) {
            try {
                lightsSession.close();
            } catch (Throwable ignored) {
            }
        }
        lightsSession = null;
        rgbLight = null;
        lightsManager = null;
    }

    private String safeName(InputDevice device) {
        String name = device.getName();
        if (name == null || name.isEmpty()) return "DualShock 4";
        return name;
    }

    private Notification buildNotification(String text) {
        Intent stopIntent = new Intent(this, Ds4LightService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 7, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(
                this, 8, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(openPending)
                .addAction(new Notification.Action.Builder(null, "Stop", stopPending).build())
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps the DS4 light-control session alive");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        InputDevice device = InputDevice.getDevice(deviceId);
        if (device != null && isLikelyDs4(device)) attach(device);
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        InputDevice device = InputDevice.getDevice(deviceId);
        if (device != null && isLikelyDs4(device)) attach(device);
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        if (selectedDevice != null && selectedDevice.getId() == deviceId) {
            selectedDevice = null;
            closeSessionSafely();
            updateNotification("Waiting for DualShock 4");
            handler.postDelayed(this::scanForDs4, 150);
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (inputManager != null) inputManager.unregisterInputDeviceListener(this);
        closeSessionSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
