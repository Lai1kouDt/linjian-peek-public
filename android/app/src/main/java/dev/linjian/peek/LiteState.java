package dev.linjian.peek;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** 最小状态上报：不读取页面文字、媒体、位置、日历、钱包或使用时长。 */
public final class LiteState {
    private LiteState() { }

    public static JSONObject collect(Context ctx) {
        JSONObject state = new JSONObject();
        try {
            long now = System.currentTimeMillis();
            Intent battery = ctx.registerReceiver((BroadcastReceiver) null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int batteryPercent = -1;
            boolean charging = false;
            if (battery != null) {
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) batteryPercent = Math.round(level * 100f / scale);
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
            }

            PowerManager power = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            boolean screenOn = power != null && (Build.VERSION.SDK_INT >= 20 ? power.isInteractive() : power.isScreenOn());
            boolean reportForegroundApp = AppPrefs.reportForegroundApp(ctx);
            String currentPackage = reportForegroundApp ? ScreenshotService.currentPackage() : "";

            state.put("device_id", AppPrefs.device(ctx));
            state.put("life_state_version", "lite.2");
            state.put("app_version", AppPrefs.APP_VERSION_NAME);
            state.put("app_version_code", AppPrefs.APP_VERSION_CODE);
            state.put("updated_at_ms", now);
            state.put("updated_at_local", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(now)));
            state.put("timezone", TimeZone.getDefault().getID());
            state.put("battery_percent", batteryPercent);
            state.put("charging", charging);
            state.put("screen_on", screenOn);
            state.put("foreground_app_reporting", reportForegroundApp);
            if (reportForegroundApp) {
                state.put("current_package", currentPackage);
                state.put("current_app", currentPackage);
            }
            state.put("accessibility_ready", ScreenshotService.ready());
            state.put("service_running", CompanionService.isRunning());
            state.put("last_poll_attempt_ms", CompanionService.lastPollAttemptMs());
            state.put("last_heartbeat_ms", CompanionService.lastSuccessfulPollMs());
            state.put("consecutive_poll_failures", CompanionService.consecutivePollFailures());
            state.put("last_poll_error", CompanionService.lastPollError());
            state.put("reconnecting", CompanionService.isRunning() && CompanionService.consecutivePollFailures() > 0);
            state.put("poll_interval_ms", AppPrefs.interval(ctx));
            state.put("managed_alarm_requests", AlarmBridge.records(ctx));
            state.put("privacy", "no_screen_text_no_screenshot");
        } catch (Exception error) {
            try { state.put("error", ScreenshotService.shortMsg(error)); } catch (Exception ignored) { }
        }
        return state;
    }
}
