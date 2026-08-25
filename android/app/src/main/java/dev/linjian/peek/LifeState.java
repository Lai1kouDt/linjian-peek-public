package dev.linjian.peek;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class LifeState {
    private LifeState() {}

    public static JSONObject collect(Context ctx) {
        JSONObject state = new JSONObject();
        try {
            long now = System.currentTimeMillis();
            Intent battery = ctx.registerReceiver((BroadcastReceiver) null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int percent = -1;
            boolean charging = false;
            String chargingType = "none";
            if (battery != null) {
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) percent = Math.round(level * 100f / scale);
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
                int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                if (plugged == BatteryManager.BATTERY_PLUGGED_USB) chargingType = "usb";
                else if (plugged == BatteryManager.BATTERY_PLUGGED_AC) chargingType = "ac";
                else if (Build.VERSION.SDK_INT >= 17 && plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) chargingType = "wireless";
            }
            PowerManager power = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            boolean screenOn = power != null && power.isInteractive();
            String currentPackage = ScreenshotService.currentPackage();
            state.put("device_id", AppPrefs.device(ctx));
            state.put("state_version", "0.4.0-lean");
            state.put("local_time", new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(now)));
            state.put("local_date", new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date(now)));
            state.put("timezone", TimeZone.getDefault().getID());
            state.put("updated_at_ms", now);
            state.put("battery_percent", percent);
            state.put("charging", charging);
            state.put("charging_type", chargingType);
            state.put("network_type", networkType(ctx));
            state.put("screen_on", screenOn);
            state.put("current_package", currentPackage);
            state.put("current_app", appLabel(ctx, currentPackage));
            state.put("accessibility_ready", ScreenshotService.ready());
            state.put("screen_text", ScreenshotService.screenText());
        } catch (Exception e) {
            try { state.put("error", ScreenshotService.shortMsg(e)); } catch (Exception ignored) {}
        }
        return state;
    }

    private static String networkType(Context ctx) {
        try {
            ConnectivityManager manager = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return "unknown";
            Network network = manager.getActiveNetwork();
            if (network == null) return "none";
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps == null) return "unknown";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
            return "other";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String appLabel(Context ctx, String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return "";
        try {
            PackageManager manager = ctx.getPackageManager();
            ApplicationInfo info = manager.getApplicationInfo(pkg, 0);
            CharSequence label = manager.getApplicationLabel(info);
            return label == null ? pkg : label.toString();
        } catch (Exception e) {
            return pkg;
        }
    }
}
