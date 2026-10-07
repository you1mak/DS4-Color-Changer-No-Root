package com.you1mak.ds4light;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.hardware.BatteryState;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;
import android.view.InputDevice;

public class MainActivity extends Activity {
    private static final int REQ_BT = 10;
    private static final int REQ_NOTIF = 11;
    private SharedPreferences prefs;
    private TextView valueText;
    private View preview;
    private ColorWheelView colorWheel;
    private SeekBar brightnessBar;
    private TextView batteryText;
    private final android.os.Handler batteryHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private int baseColor;
    private int brightness;
    private boolean serviceStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("state", MODE_PRIVATE);
        baseColor = prefs.getInt("baseColor", Color.rgb(0, 0, 64));
        brightness = prefs.getInt("brightness", 100);
        buildUi();
        requestPermissionsIfNeeded();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        root.setBackgroundColor(Color.BLACK);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("DS4 Light — No Root");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(title, matchWrap());

        TextView info = new TextView(this);
        info.setText("Bluetooth / USB • Android 12+ • Live color");
        info.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(info, matchWrap());

        Space s1 = new Space(this);
        content.addView(s1, new LinearLayout.LayoutParams(1, dp(12)));

        colorWheel = new ColorWheelView(this);
        colorWheel.setColor(baseColor);
        content.addView(colorWheel, new LinearLayout.LayoutParams(-1, dp(290)));
        colorWheel.setOnColorChangedListener(color -> {
            baseColor = color;
            persistAndApply();
        });

        valueText = new TextView(this);
        valueText.setTextSize(18);
        valueText.setGravity(Gravity.CENTER);
        content.addView(valueText, matchWrap());

        preview = new View(this);
        preview.setBackgroundColor(effectiveColor());
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, dp(52));
        previewParams.topMargin = dp(8);
        previewParams.bottomMargin = dp(8);
        content.addView(preview, previewParams);

        TextView brightnessLabel = new TextView(this);
        brightnessLabel.setText("Brightness");
        content.addView(brightnessLabel, matchWrap());

        batteryText = new TextView(this);
        batteryText.setGravity(Gravity.CENTER);
        batteryText.setText("Battery: --");
        content.addView(batteryText, matchWrap());

        brightnessBar = new SeekBar(this);
        brightnessBar.setMax(100);
        brightnessBar.setProgress(brightness);
        content.addView(brightnessBar, matchWrap());
        brightnessBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                brightness = progress;
                persistAndApply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);

        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setOnClickListener(v -> {
            Ds4LightService.refresh(this);
            Toast.makeText(this, "Refreshing controller", Toast.LENGTH_SHORT).show();
        });
        buttons.addView(refresh, new LinearLayout.LayoutParams(0, -2, 1));

        Button start = new Button(this);
        start.setText("Start");
        start.setOnClickListener(v -> startServiceWithCurrentColor());
        buttons.addView(start, new LinearLayout.LayoutParams(0, -2, 1));

        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(v -> {
            Ds4LightService.stop(this);
            serviceStarted = false;
        });
        buttons.addView(stop, new LinearLayout.LayoutParams(0, -2, 1));

        content.addView(buttons, matchWrap());
        root.addView(content, new LinearLayout.LayoutParams(dp(320), -2));
        updateUi();
        setContentView(root);
        updateBattery();
        batteryHandler.post(batteryRunnable);
    }

    private void updateUi() {
        int effective = effectiveColor();
        if (preview != null) preview.setBackgroundColor(effective);
        if (valueText != null) {
            valueText.setText(String.format("#%02X%02X%02X  •  %d%%",
                    Color.red(effective), Color.green(effective), Color.blue(effective), brightness));
        }
    }

    private int effectiveColor() {
        if (brightness == 0) {
            return Color.rgb(1, 1, 1);
        }

        float scale = brightness / 100f;
        return Color.rgb(
                Math.round(Color.red(baseColor) * scale),
                Math.round(Color.green(baseColor) * scale),
                Math.round(Color.blue(baseColor) * scale)
        );
    }

    private void persistAndApply() {
        prefs.edit().putInt("baseColor", baseColor).putInt("brightness", brightness).apply();
        updateUi();
        if (serviceStarted) Ds4LightService.setColor(this, effectiveColor());
    }

    private final Runnable batteryRunnable = new Runnable() {
        @Override public void run() {
            updateBattery();
            batteryHandler.postDelayed(this, 1000);
        }
    };

    private void updateBattery() {
        if (batteryText == null) return;
        InputDevice ds4 = findDs4Device();
        if (ds4 == null) {
            batteryText.setText("Battery: --");
            return;
        }
        try {
            BatteryState state = ds4.getBatteryState();
            if (!state.isPresent()) {
                batteryText.setText("Battery: unavailable");
                return;
            }
            float capacity = state.getCapacity();
            String level = Float.isNaN(capacity) ? "--" : (Math.round(capacity * 100f) + "%");
            String status;
            switch (state.getStatus()) {
                case BatteryState.STATUS_CHARGING:
                    status = "Charging";
                    break;
                case BatteryState.STATUS_FULL:
                    status = "Full";
                    break;
                case BatteryState.STATUS_NOT_CHARGING:
                    status = "Not charging";
                    break;
                case BatteryState.STATUS_DISCHARGING:
                    status = "Discharging";
                    break;
                default:
                    status = "Unknown";
                    break;
            }
            batteryText.setText("Battery: " + level + "  •  " + status);
        } catch (Throwable t) {
            batteryText.setText("Battery: unavailable");
        }
    }

    private InputDevice findDs4Device() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null) continue;
            if (device.getVendorId() == 0x054C && device.getProductId() == 0x05C4) return device;
            String name = device.getName() == null ? "" : device.getName().toLowerCase();
            if (name.contains("dualshock 4") || name.contains("wireless controller") || name.contains("playstation 4 controller")) return device;
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        batteryHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
            return;
        }
        requestNotificationPermissionIfNeeded();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    private void startServiceWithCurrentColor() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Bluetooth permission is required.", Toast.LENGTH_LONG).show();
            requestPermissionsIfNeeded();
            return;
        }
        prefs.edit().putInt("baseColor", baseColor).putInt("brightness", brightness).apply();
        Ds4LightService.start(this);
        serviceStarted = true;
        Ds4LightService.setColor(this, effectiveColor());
        Toast.makeText(this, "Foreground session started", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT) requestNotificationPermissionIfNeeded();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    public static class ColorWheelView extends View {
        public interface OnColorChangedListener { void onColorChanged(int color); }
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private OnColorChangedListener listener;
        private float hue = 220f;
        private float saturation = 1f;
        private float radius;
        private float cx, cy;

        public ColorWheelView(Context context) { super(context); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }

        public void setOnColorChangedListener(OnColorChangedListener listener) { this.listener = listener; }

        public void setColor(int color) {
            float[] hsv = new float[3];
            Color.colorToHSV(color, hsv);
            hue = hsv[0];
            saturation = hsv[1];
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            cx = getWidth() / 2f;
            cy = getHeight() / 2f;
            radius = Math.min(getWidth(), getHeight()) / 2f - dp(10);

            canvas.save();
            canvas.rotate(90f, cx, cy);

            int[] colors = new int[]{
                    Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
                    Color.BLUE, Color.MAGENTA, Color.RED
            };
            paint.setShader(new SweepGradient(cx, cy, colors, null));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setShader(null);

            RadialGradient white = new RadialGradient(cx, cy, radius,
                    Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP);
            paint.setShader(white);
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setShader(null);

            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dp(2));
            ringPaint.setColor(Color.WHITE);
            float angle = (float) Math.toRadians(hue);
            float distance = saturation * radius;
            float px = cx + (float) Math.cos(angle) * distance;
            float py = cy + (float) Math.sin(angle) * distance;
            canvas.drawCircle(px, py, dp(7), ringPaint);
            ringPaint.setColor(Color.BLACK);
            ringPaint.setStrokeWidth(dp(1));
            canvas.drawCircle(px, py, dp(8), ringPaint);
            canvas.restore();
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_DOWN && event.getAction() != MotionEvent.ACTION_MOVE) return true;
            float dx = event.getX() - cx;
            float dy = event.getY() - cy;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d > radius) {
                float scale = radius / d;
                dx *= scale;
                dy *= scale;
                d = radius;
            }
            hue = (float) Math.toDegrees(Math.atan2(dy, dx)) - 90f;
            if (hue < 0) hue += 360f;
            if (hue >= 360) hue -= 360f;
            saturation = Math.max(0f, Math.min(1f, d / radius));
            invalidate();
            if (listener != null) listener.onColorChanged(Color.HSVToColor(new float[]{hue, saturation, 1f}));
            return true;
        }

        private float dp(int v) { return v * getResources().getDisplayMetrics().density; }
    }
}
