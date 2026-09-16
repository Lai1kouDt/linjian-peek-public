package dev.linjian.peek;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.AlarmClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/**
 * 通过系统闹钟 App 设置闹钟，并只保存掌心窗自己发出的请求记录。
 * Android 没有公开的“枚举/删除任意系统闹钟”API，因此绝不把 Intent 已分发写成已创建。
 */
public final class AlarmBridge {
    private static final String KEY_RECORDS = "lite_managed_alarm_records";
    private static final int MAX_RECORDS = 80;
    private static final int MAX_BATCH = 10;

    private AlarmBridge() { }

    public static JSONObject handle(Context ctx, JSONObject command) {
        try {
            String operation = command.optString("operation", "set").trim().toLowerCase();
            if (operation.length() == 0) operation = "set";
            if ("list".equals(operation) || "view".equals(operation)) return listResult(ctx);
            if ("delete".equals(operation) || "disable".equals(operation) || "close".equals(operation)) {
                return dismissResult(ctx, operation, command);
            }
            if (!"set".equals(operation) && !"create".equals(operation)) {
                return base(false, operation).put("error", "unsupported_alarm_operation");
            }
            return setAlarms(ctx, command);
        } catch (Exception error) {
            JSONObject out = new JSONObject();
            try { out.put("ok", false); out.put("completed", true); out.put("confirmed", false); out.put("error", ScreenshotService.shortMsg(error)); }
            catch (Exception ignored) { }
            return out;
        }
    }

    public static JSONArray records(Context ctx) {
        try {
            String raw = AppPrefs.get(ctx).getString(KEY_RECORDS, "[]");
            return new JSONArray(raw == null || raw.trim().length() == 0 ? "[]" : raw);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static JSONObject setAlarms(Context ctx, JSONObject command) throws Exception {
        JSONArray requested = command.optJSONArray("alarms");
        if (requested == null || requested.length() == 0) {
            requested = new JSONArray();
            requested.put(command);
        }

        JSONArray results = new JSONArray();
        JSONArray saved = records(ctx);
        boolean allDispatched = requested.length() > 0;
        int count = Math.min(requested.length(), MAX_BATCH);
        for (int index = 0; index < count; index++) {
            JSONObject item = requested.optJSONObject(index);
            if (item == null) item = new JSONObject();
            int hour = item.optInt("hour", -1);
            int minute = item.optInt("minute", -1);
            String message = item.optString("message", command.optString("message", "掌心窗闹钟"));
            boolean vibrate = item.has("vibrate") ? item.optBoolean("vibrate", true) : command.optBoolean("vibrate", true);
            boolean skipUi = item.has("skip_ui") ? item.optBoolean("skip_ui", true) : command.optBoolean("skip_ui", true);
            JSONObject result = dispatchOne(ctx, hour, minute, message, vibrate, skipUi);
            results.put(result);
            allDispatched = allDispatched && result.optBoolean("request_dispatched", false);
            if (result.optBoolean("request_dispatched", false)) saved.put(result.optJSONObject("record"));
        }
        trimAndSave(ctx, saved);

        JSONObject out = base(allDispatched, "set");
        out.put("batch_count", count);
        out.put("all_requests_dispatched", allDispatched);
        out.put("all_created_confirmed", false);
        out.put("confirmed", false);
        out.put("alarms", results);
        out.put("scope", "system_alarm_intent");
        out.put("note", "Android 只确认系统闹钟 App 接收了请求，不能通过公开 API 确认每个闹钟最终已创建。");
        return out;
    }

    private static JSONObject dispatchOne(Context ctx, int hour, int minute, String message, boolean vibrate, boolean skipUi) throws Exception {
        JSONObject result = new JSONObject();
        result.put("hour", hour);
        result.put("minute", minute);
        result.put("message", message == null || message.trim().length() == 0 ? "掌心窗闹钟" : message.trim());
        result.put("alarm_created", false);
        result.put("created_confirmed", false);
        result.put("request_dispatched", false);
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            result.put("error", "invalid_alarm_time");
            return result;
        }
        try {
            Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM);
            intent.putExtra(AlarmClock.EXTRA_HOUR, hour);
            intent.putExtra(AlarmClock.EXTRA_MINUTES, minute);
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, result.optString("message"));
            intent.putExtra(AlarmClock.EXTRA_VIBRATE, vibrate);
            intent.putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.resolveActivity(ctx.getPackageManager()) == null) {
                result.put("error", "no_system_alarm_handler");
                return result;
            }
            ctx.startActivity(intent);
            long now = System.currentTimeMillis();
            JSONObject record = new JSONObject();
            record.put("alarm_id", UUID.randomUUID().toString());
            record.put("hour", hour);
            record.put("minute", minute);
            record.put("message", result.optString("message"));
            record.put("requested_at_ms", now);
            record.put("status", "requested_to_system");
            record.put("created_confirmed", false);
            result.put("request_dispatched", true);
            result.put("record", record);
        } catch (Exception error) {
            result.put("error", ScreenshotService.shortMsg(error));
        }
        return result;
    }

    private static JSONObject listResult(Context ctx) throws Exception {
        JSONObject out = base(true, "list");
        JSONArray items = records(ctx);
        out.put("alarms", items);
        out.put("count", items.length());
        out.put("scope", "palm_window_requests_only");
        out.put("complete_system_list", false);
        out.put("note", "这里只列出掌心窗发出的请求记录，不代表系统闹钟 App 的完整实时列表。");
        return out;
    }

    private static JSONObject dismissResult(Context ctx, String operation, JSONObject command) throws Exception {
        JSONObject out = base(false, operation);
        out.put("dismiss_requested", false);
        out.put("alarm_deleted", false);
        out.put("alarm_disabled", false);
        out.put("confirmed", false);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            out.put("error", "dismiss_alarm_requires_android_23");
            out.put("requires_user_action", true);
            return out;
        }

        String alarmId = command.optString("alarm_id", "");
        int hour = command.optInt("hour", -1);
        int minute = command.optInt("minute", -1);
        JSONArray saved = records(ctx);
        JSONObject matched = null;
        for (int index = saved.length() - 1; index >= 0; index--) {
            JSONObject candidate = saved.optJSONObject(index);
            if (candidate == null) continue;
            boolean idMatches = alarmId.length() > 0 && alarmId.equals(candidate.optString("alarm_id", ""));
            boolean timeMatches = alarmId.length() == 0 && hour >= 0 && minute >= 0
                    && hour == candidate.optInt("hour", -2) && minute == candidate.optInt("minute", -2);
            if (idMatches || timeMatches) { matched = candidate; break; }
        }
        if (matched != null) {
            hour = matched.optInt("hour", hour);
            minute = matched.optInt("minute", minute);
            alarmId = matched.optString("alarm_id", alarmId);
        }
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            out.put("error", "alarm_id_or_time_required");
            return out;
        }

        boolean dismissDispatched = false;
        boolean managerOpened = false;
        try {
            Intent intent = new Intent(AlarmClock.ACTION_DISMISS_ALARM);
            intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME);
            intent.putExtra(AlarmClock.EXTRA_HOUR, hour);
            intent.putExtra(AlarmClock.EXTRA_MINUTES, minute);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.resolveActivity(ctx.getPackageManager()) != null) {
                ctx.startActivity(intent);
                dismissDispatched = true;
            }
        } catch (Exception error) {
            out.put("error", ScreenshotService.shortMsg(error));
        }
        if (!dismissDispatched && command.optBoolean("open_manager", false)) {
            try {
                Intent intent = new Intent(AlarmClock.ACTION_SHOW_ALARMS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (intent.resolveActivity(ctx.getPackageManager()) != null) {
                    ctx.startActivity(intent);
                    managerOpened = true;
                }
            } catch (Exception ignored) { }
        }
        if (dismissDispatched) {
            out.put("ok", true);
            out.put("dismiss_requested", true);
            out.put("alarm_id", alarmId);
            out.put("hour", hour);
            out.put("minute", minute);
            out.put("requires_user_action", true);
            out.put("note", "系统已接收关闭请求；若有多个同时间闹钟，可能需要用户在系统界面选择。掌心窗无法回读最终关闭结果。");
            if (matched != null) {
                matched.put("status", "dismiss_requested");
                matched.put("dismiss_requested_at_ms", System.currentTimeMillis());
                trimAndSave(ctx, saved);
            }
        } else if (!out.has("error")) {
            out.put("error", "no_system_alarm_handler");
            out.put("requires_user_action", true);
        }
        out.put("alarm_manager_opened", managerOpened);
        return out;
    }

    private static JSONObject base(boolean ok, String operation) throws Exception {
        JSONObject out = new JSONObject();
        out.put("ok", ok);
        out.put("operation", operation);
        out.put("completed", true);
        out.put("confirmed", ok);
        return out;
    }

    private static void trimAndSave(Context ctx, JSONArray input) {
        JSONArray output = new JSONArray();
        int start = Math.max(0, input.length() - MAX_RECORDS);
        for (int index = start; index < input.length(); index++) output.put(input.opt(index));
        SharedPreferences.Editor editor = AppPrefs.get(ctx).edit().putString(KEY_RECORDS, output.toString());
        editor.apply();
    }
}
