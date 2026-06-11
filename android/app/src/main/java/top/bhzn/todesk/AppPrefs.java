package top.bhzn.todesk;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import java.util.Locale;
import java.security.SecureRandom;

final class AppPrefs {
    private static final String PREFS = "bhzn_todesk";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_DEVICE_CODE = "device_code";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_CONTROL_ENABLED = "control_enabled";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DEFAULT_HOST = decode(new int[] {
            46, 53, 62, 63, 41, 49, 116, 56, 50, 32, 52, 116, 46, 53, 42
    }, 0x5A);

    private AppPrefs() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String deviceId(Context context) {
        SharedPreferences prefs = prefs(context);
        String current = prefs.getString(KEY_DEVICE_ID, null);
        if (current != null && !current.isEmpty()) {
            return current;
        }
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        String compact = Integer.toHexString((androidId == null ? "bhzn" : androidId).hashCode()).toUpperCase(Locale.US);
        String id = compact.replace("-", "").replaceAll("[^A-Z0-9]", "");
        while (id.length() < 8) {
            id = id + "0";
        }
        if (id.length() > 8) {
            id = id.substring(0, 4) + "-" + id.substring(4, 8);
        }
        prefs.edit().putString(KEY_DEVICE_ID, id).apply();
        return id;
    }

    static String deviceCode(Context context) {
        SharedPreferences prefs = prefs(context);
        String current = prefs.getString(KEY_DEVICE_CODE, null);
        if (current != null && current.matches("\\d{6}")) {
            return current;
        }
        String code = String.format(Locale.US, "%06d", RANDOM.nextInt(1_000_000));
        prefs.edit().putString(KEY_DEVICE_CODE, code).apply();
        return code;
    }

    static String resetDeviceCode(Context context) {
        String code = String.format(Locale.US, "%06d", RANDOM.nextInt(1_000_000));
        prefs(context).edit().putString(KEY_DEVICE_CODE, code).apply();
        return code;
    }

    static String serverUrl(Context context) {
        String url = prefs(context).getString(KEY_SERVER_URL, BuildConfig.defaultServerUrl());
        return normalizeServerUrl(url);
    }

    static void setServerUrl(Context context, String url) {
        prefs(context).edit().putString(KEY_SERVER_URL, normalizeServerUrl(url)).apply();
    }

    private static String normalizeServerUrl(String url) {
        String value = url == null ? "" : url.trim();
        if (value.isEmpty()) {
            value = BuildConfig.defaultServerUrl();
        }
        while (value.endsWith("/") && value.length() > "https://x".length()) {
            value = value.substring(0, value.length() - 1);
        }
        if (!value.startsWith("http://") && !value.startsWith("https://") && !value.startsWith("ws://") && !value.startsWith("wss://")) {
            value = "https://" + value;
        }
        if (value.equals("http://" + DEFAULT_HOST) || value.equals("ws://" + DEFAULT_HOST)) {
            value = BuildConfig.defaultServerUrl();
        }
        if (value.startsWith("wss://")) {
            value = "https://" + value.substring("wss://".length());
        } else if (value.startsWith("ws://")) {
            value = "http://" + value.substring("ws://".length());
        }
        return value;
    }

    private static String decode(int[] data, int key) {
        StringBuilder builder = new StringBuilder(data.length);
        for (int value : data) {
            builder.append((char) (value ^ key));
        }
        return builder.toString();
    }

    static boolean controlEnabled(Context context) {
        return prefs(context).getBoolean(KEY_CONTROL_ENABLED, true);
    }

    static void setControlEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_CONTROL_ENABLED, enabled).apply();
    }
}
