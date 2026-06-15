package top.bhzn.todesk;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;

final class DiagnosticLog {
    private static final String PREF = "diagnostic_log";
    private static final String KEY_LINES = "lines";
    private static final int MAX_LINES = 120;

    private DiagnosticLog() {
    }

    static synchronized void info(Context context, String tag, String message) {
        write(context, tag, message, null);
    }

    static synchronized void error(Context context, String tag, String message, Throwable throwable) {
        write(context, tag, message, throwable);
    }

    static synchronized String recent(Context context, int limit) {
        JSONArray lines = readLines(context);
        StringBuilder builder = new StringBuilder();
        int start = Math.max(0, lines.length() - Math.max(1, limit));
        for (int i = start; i < lines.length(); i++) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(lines.optString(i));
        }
        return builder.length() == 0 ? "暂无诊断日志" : builder.toString();
    }

    static synchronized void clear(Context context) {
        prefs(context).edit().remove(KEY_LINES).apply();
    }

    private static void write(Context context, String tag, String message, Throwable throwable) {
        String line = timestamp() + " " + tag + " " + message;
        if (throwable != null) {
            String detail = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
            line = line + " | " + detail;
            Log.e(tag, message, throwable);
        } else {
            Log.i(tag, message);
        }

        JSONArray lines = readLines(context);
        lines.put(line);
        while (lines.length() > MAX_LINES) {
            JSONArray trimmed = new JSONArray();
            for (int i = lines.length() - MAX_LINES; i < lines.length(); i++) {
                trimmed.put(lines.optString(i));
            }
            lines = trimmed;
        }
        prefs(context).edit().putString(KEY_LINES, lines.toString()).apply();
    }

    private static JSONArray readLines(Context context) {
        try {
            return new JSONArray(prefs(context).getString(KEY_LINES, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static String timestamp() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA).format(new Date());
    }
}
