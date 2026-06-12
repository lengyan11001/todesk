package top.bhzn.todesk;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import java.util.List;
import org.json.JSONObject;

final class PermissionState {
    private PermissionState() {
    }

    static boolean isAccessibilityEnabled(Context context) {
        AccessibilityManager manager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        List<AccessibilityServiceInfo> services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        String serviceName = context.getPackageName() + "/" + RemoteInputService.class.getName();
        String shortServiceName = context.getPackageName() + "/." + RemoteInputService.class.getSimpleName();
        for (AccessibilityServiceInfo service : services) {
            String id = service.getId();
            if (id != null && (id.equals(serviceName) || id.equals(shortServiceName))) {
                return true;
            }
        }
        return false;
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
