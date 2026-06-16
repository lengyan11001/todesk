package top.bhzn.todesk;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityManager;
import org.json.JSONArray;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

final class PermissionState {
    private PermissionState() {
    }

    static boolean isAccessibilityEnabled(Context context) {
        if (RemoteInputService.isReady()) return true;
        if (isAccessibilityEnabledFromManager(context)) return true;
        return isAccessibilityEnabledFromSecureSettings(context);
    }

    static JSONObject diagnostics(Context context) {
        JSONObject value = JsonUtil.object();
        JsonUtil.put(value, "remoteInputReady", RemoteInputService.isReady());
        JsonUtil.put(value, "accessibilityByManager", isAccessibilityEnabledFromManager(context));
        JsonUtil.put(value, "accessibilityBySecureSettings", isAccessibilityEnabledFromSecureSettings(context));
        JsonUtil.put(value, "accessibilityEnabled", isAccessibilityEnabled(context));
        JsonUtil.put(value, "inputControlReady", isInputControlReady(context));
        JsonUtil.put(value, "overlay", canDrawOverlays(context));
        JsonUtil.put(value, "enabledServices", enabledAccessibilityServices(context));
        return value;
    }

    private static boolean isAccessibilityEnabledFromManager(Context context) {
        AccessibilityManager manager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        List<AccessibilityServiceInfo> services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo service : services) {
            if (matchesAccessibilityService(context, service.getId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAccessibilityEnabledFromSecureSettings(Context context) {
        String enabled = enabledAccessibilityServicesRaw(context);
        if (TextUtils.isEmpty(enabled)) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        for (String id : splitter) {
            if (matchesAccessibilityService(context, id)) return true;
        }
        return false;
    }

    private static String enabledAccessibilityServicesRaw(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        return enabled == null ? "" : enabled;
    }

    private static JSONArray enabledAccessibilityServices(Context context) {
        JSONArray services = new JSONArray();
        String enabled = enabledAccessibilityServicesRaw(context);
        if (TextUtils.isEmpty(enabled)) return services;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        for (String id : splitter) {
            services.put(id);
        }
        return services;
    }

    private static boolean matchesAccessibilityService(Context context, String id) {
        if (id == null) return false;
        String normalized = id.trim();
        String lower = normalized.toLowerCase(Locale.US);
        String packageName = context.getPackageName();
        String className = RemoteInputService.class.getName();
        String simpleName = RemoteInputService.class.getSimpleName();
        return normalized.equals(packageName + "/" + className)
                || normalized.equals(packageName + "/." + simpleName)
                || lower.equals((packageName + "/" + className).toLowerCase(Locale.US))
                || lower.equals((packageName + "/." + simpleName).toLowerCase(Locale.US))
                || (lower.contains(packageName.toLowerCase(Locale.US)) && lower.contains(simpleName.toLowerCase(Locale.US)));
    }

    static boolean isInputControlReady(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && isAccessibilityEnabled(context)
                && RemoteInputService.isReady();
    }

    static boolean canDrawOverlays(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context);
    }

    static boolean canPostNotifications(Context context) {
        return Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    static JSONObject toJson(Context context, boolean mediaReady) {
        JSONObject permissions = JsonUtil.object();
        JsonUtil.put(permissions, "accessibility", isAccessibilityEnabled(context));
        JsonUtil.put(permissions, "inputControl", isInputControlReady(context));
        JsonUtil.put(permissions, "overlay", canDrawOverlays(context));
        JsonUtil.put(permissions, "notification", canPostNotifications(context));
        JsonUtil.put(permissions, "mediaProjection", mediaReady);
        return permissions;
    }
}
