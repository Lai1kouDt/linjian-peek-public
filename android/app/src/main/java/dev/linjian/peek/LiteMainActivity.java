package dev.linjian.peek;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;
import java.util.List;

public class LiteMainActivity extends Activity {
    private EditText serverUrlInput;
    private EditText tokenInput;
    private EditText deviceIdInput;
    private TextView serviceStatus;
    private TextView accessibilityStatus;
    private TextView connectionHint;
    private Button toggleButton;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final Runnable refreshTick = new Runnable() {
        @Override public void run() {
            updateStatus();
            uiHandler.postDelayed(this, 1500L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lite);

        serverUrlInput = findViewById(R.id.liteServerUrl);
        tokenInput = findViewById(R.id.liteToken);
        deviceIdInput = findViewById(R.id.liteDeviceId);
        serviceStatus = findViewById(R.id.liteServiceStatus);
        accessibilityStatus = findViewById(R.id.liteAccessibilityStatus);
        connectionHint = findViewById(R.id.liteConnectionHint);
        toggleButton = findViewById(R.id.liteToggleButton);

        loadSettings();

        findViewById(R.id.liteSaveButton).setOnClickListener(v -> {
            saveSettings(true);
            Toast.makeText(this, "连接已保存", Toast.LENGTH_SHORT).show();
            updateStatus();
        });
        findViewById(R.id.liteAccessibilityButton).setOnClickListener(v -> openAccessibilitySettings());
        findViewById(R.id.liteBatteryButton).setOnClickListener(v -> requestBackgroundPermission());
        toggleButton.setOnClickListener(v -> {
            if (CompanionService.isRunning()) stopCompanionService();
            else startCompanionService();
        });
        findViewById(R.id.liteTestAlarmButton).setOnClickListener(v -> testAlarm());
        findViewById(R.id.liteTestLockButton).setOnClickListener(v -> testLockScreen());
        findViewById(R.id.liteDebugButton).setOnClickListener(v -> showDebugLog());

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 13);
        }
        updateStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        uiHandler.removeCallbacks(refreshTick);
        uiHandler.post(refreshTick);
    }

    @Override protected void onPause() {
        saveSettings(false);
        uiHandler.removeCallbacks(refreshTick);
        super.onPause();
    }

    private void loadSettings() {
        SharedPreferences prefs = AppPrefs.get(this);
        serverUrlInput.setText(prefs.getString(AppPrefs.KEY_SERVER, ""));
        tokenInput.setText(prefs.getString(AppPrefs.KEY_TOKEN, ""));
        deviceIdInput.setText(prefs.getString(AppPrefs.KEY_DEVICE, "android-phone"));
    }

    private boolean saveSettings(boolean blocking) {
        String server = AppPrefs.cleanServer(serverUrlInput.getText().toString());
        String token = tokenInput.getText().toString().trim();
        String device = deviceIdInput.getText().toString().trim();
        if (device.isEmpty()) device = "android-phone";
        SharedPreferences.Editor editor = AppPrefs.get(this).edit()
                .putString(AppPrefs.KEY_SERVER, server)
                .putString(AppPrefs.KEY_TOKEN, token)
                .putString(AppPrefs.KEY_DEVICE, device)
                .putInt(AppPrefs.KEY_INTERVAL, AppPrefs.DEFAULT_POLL_INTERVAL_MS);
        if (blocking) return editor.commit();
        editor.apply();
        return true;
    }

    private void startCompanionService() {
        saveSettings(true);
        String server = AppPrefs.server(this);
        String token = AppPrefs.token(this);
        if (server.isEmpty() || token.isEmpty()) {
            Toast.makeText(this, "请先填写服务器地址和 Token", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isAccessibilityEnabled() || ScreenshotService.getInstance() == null) {
            DebugState.append(this, "Lite 启动等待：无障碍服务尚未连接");
            Toast.makeText(this, "请先开启掌心窗无障碍服务", Toast.LENGTH_LONG).show();
            openAccessibilitySettings();
            return;
        }
        AppPrefs.get(this).edit().putBoolean("user_stopped", false).apply();
        Intent service = new Intent(this, CompanionService.class);
        service.putExtra("server_url", server);
        service.putExtra("token", token);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
        else startService(service);
        DebugState.append(this, "掌心窗 Lite 已请求启动后台服务");
        Toast.makeText(this, "掌心窗已启动", Toast.LENGTH_SHORT).show();
        updateStatus();
    }

    private void stopCompanionService() {
        AppPrefs.get(this).edit().putBoolean("user_stopped", true).apply();
        stopService(new Intent(this, CompanionService.class));
        DebugState.append(this, "掌心窗 Lite 已停止后台服务");
        Toast.makeText(this, "掌心窗已停止", Toast.LENGTH_SHORT).show();
        updateStatus();
    }

    private void testAlarm() {
        Calendar time = Calendar.getInstance();
        time.add(Calendar.MINUTE, 1);
        try {
            Intent alarm = new Intent(AlarmClock.ACTION_SET_ALARM);
            alarm.putExtra(AlarmClock.EXTRA_HOUR, time.get(Calendar.HOUR_OF_DAY));
            alarm.putExtra(AlarmClock.EXTRA_MINUTES, time.get(Calendar.MINUTE));
            alarm.putExtra(AlarmClock.EXTRA_MESSAGE, "掌心窗 Lite 测试闹钟");
            alarm.putExtra(AlarmClock.EXTRA_VIBRATE, true);
            alarm.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
            startActivity(alarm);
            DebugState.append(this, "已请求设置一分钟后的 Lite 测试闹钟");
            Toast.makeText(this, "已设置一分钟后的测试闹钟", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            DebugState.append(this, "Lite 测试闹钟失败：" + ScreenshotService.shortMsg(error));
            Toast.makeText(this, "系统闹钟没有接住请求", Toast.LENGTH_LONG).show();
        }
    }

    private void testLockScreen() {
        ScreenshotService service = ScreenshotService.getInstance();
        if (service == null || !isAccessibilityEnabled()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show();
            openAccessibilitySettings();
            return;
        }
        boolean requested = service.doLockScreen();
        if (!requested) {
            DebugState.append(this, "Lite 本机锁屏请求失败");
            Toast.makeText(this, "锁屏失败；此功能需要 Android 9 或更高版本", Toast.LENGTH_LONG).show();
            return;
        }
        uiHandler.postDelayed(() -> {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            boolean screenOn = power != null && (Build.VERSION.SDK_INT >= 20 ? power.isInteractive() : power.isScreenOn());
            DebugState.append(this, screenOn ? "Lite 已请求锁屏，但屏幕仍亮着" : "Lite 本机锁屏已确认：screen_off=true");
        }, 650L);
    }

    private void updateStatus() {
        boolean running = CompanionService.isRunning();
        boolean enabled = isAccessibilityEnabled();
        boolean connected = ScreenshotService.getInstance() != null;
        boolean configured = !AppPrefs.server(this).isEmpty() && !AppPrefs.token(this).isEmpty();

        serviceStatus.setText(running ? "●  后台服务正在运行" : "○  后台服务未启动");
        serviceStatus.setTextColor(running ? 0xFF477463 : 0xFF916E7B);
        accessibilityStatus.setText(enabled
                ? (connected ? "无障碍：已开启并连接" : "无障碍：系统已开启，正在连接")
                : "无障碍：未开启");
        if (!configured) {
            connectionHint.setText("请填写服务器地址和 Token。版本 " + AppPrefs.APP_VERSION_NAME);
        } else if (!running) {
            connectionHint.setText("连接已保存；开启无障碍后启动服务。版本 " + AppPrefs.APP_VERSION_NAME);
        } else if (CompanionService.consecutivePollFailures() > 0) {
            connectionHint.setText("连接中断，正在自动重连（连续失败 " + CompanionService.consecutivePollFailures() + " 次）。版本 " + AppPrefs.APP_VERSION_NAME);
        } else if (CompanionService.lastSuccessfulPollMs() > 0) {
            long agoSeconds = Math.max(0L, (System.currentTimeMillis() - CompanionService.lastSuccessfulPollMs()) / 1000L);
            connectionHint.setText("连接正常 · 心跳 " + agoSeconds + " 秒前 · 版本 " + AppPrefs.APP_VERSION_NAME);
        } else {
            connectionHint.setText("正在建立连接… 版本 " + AppPrefs.APP_VERSION_NAME);
        }
        toggleButton.setText(running ? "停止掌心窗" : "启动掌心窗");
        toggleButton.setBackgroundResource(running ? R.drawable.pill_danger : R.drawable.pill_primary);
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "开启“掌心窗服务”后返回", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "请在系统设置的无障碍页面开启掌心窗服务", Toast.LENGTH_LONG).show();
        }
    }

    private void requestBackgroundPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, "当前系统无需单独设置", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
            if (manager != null && manager.isIgnoringBatteryOptimizations(getPackageName())) {
                Toast.makeText(this, "后台运行权限已开启", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(intent);
            Toast.makeText(this, "请允许掌心窗在后台持续运行", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "请在电池设置中允许掌心窗后台运行", Toast.LENGTH_LONG).show();
        }
    }

    private boolean isAccessibilityEnabled() {
        if (ScreenshotService.getInstance() != null) return true;
        try {
            AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
            if (manager != null) {
                List<AccessibilityServiceInfo> services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                if (services != null) {
                    for (AccessibilityServiceInfo info : services) {
                        if (info == null || info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) continue;
                        if (getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)
                                && ScreenshotService.class.getName().equals(info.getResolveInfo().serviceInfo.name)) return true;
                    }
                }
            }
            String raw = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (!TextUtils.isEmpty(raw)) {
                TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
                splitter.setString(raw);
                ComponentName expected = new ComponentName(this, ScreenshotService.class);
                while (splitter.hasNext()) {
                    ComponentName item = ComponentName.unflattenFromString(splitter.next());
                    if (expected.equals(item)) return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void showDebugLog() {
        new AlertDialog.Builder(this)
                .setTitle("最近日志")
                .setMessage(DebugState.get(this))
                .setPositiveButton("关闭", null)
                .show();
    }
}
