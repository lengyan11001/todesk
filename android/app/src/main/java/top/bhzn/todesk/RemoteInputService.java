package top.bhzn.todesk;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public class RemoteInputService extends AccessibilityService {
    private static final String TAG = "RemoteInputService";
    private static volatile RemoteInputService instance;
    private Path dragPath;
    private GestureDescription.StrokeDescription dragStroke;
    private long dragSegmentStartAt;

    static boolean isReady() {
        return instance != null;
    }

    static boolean dispatchRemoteInput(String action, int x, int y, int x2, int y2, long duration) {
        RemoteInputService service = instance;
        if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        return service.handleInput(action, x, y, x2, y2, duration);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "accessibility connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    private boolean handleInput(String action, int x, int y, int x2, int y2, long duration) {
        if ("back".equals(action)) {
            return performGlobalAction(GLOBAL_ACTION_BACK);
        }
        if ("home".equals(action)) {
            return performGlobalAction(GLOBAL_ACTION_HOME);
        }
        if ("recents".equals(action)) {
            return performGlobalAction(GLOBAL_ACTION_RECENTS);
        }
        if ("tap".equals(action)) {
            return gesture(x, y, x, y, Math.max(50, duration));
        }
        if ("swipe".equals(action)) {
            return gesture(x, y, x2, y2, Math.max(120, duration));
        }
        if ("dragStart".equals(action)) {
            return dragStart(x, y);
        }
        if ("dragMove".equals(action)) {
            return dragMove(x, y, Math.max(16, duration));
        }
        if ("dragEnd".equals(action)) {
            return dragEnd(x, y, Math.max(16, duration));
        }
        if ("homeSwipe".equals(action)) {
            return gesture(x, y, x2, y2, Math.max(220, duration));
        }
        return false;
    }

    private boolean dragStart(int x, int y) {
        dragPath = new Path();
        dragPath.moveTo(Math.max(0, x), Math.max(0, y));
        dragStroke = null;
        dragSegmentStartAt = System.currentTimeMillis();
        return true;
    }

    private boolean dragMove(int x, int y, long duration) {
        if (dragPath == null) {
            return dragStart(x, y);
        }
        dragPath.lineTo(Math.max(0, x), Math.max(0, y));
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true;
        }
        boolean result = dispatchContinuedStroke(true);
        resetDragPathAt(x, y);
        return result;
    }

    private boolean dragEnd(int x, int y, long duration) {
        if (dragPath == null) {
            return gesture(x, y, x, y, 60);
        }
        dragPath.lineTo(Math.max(0, x), Math.max(0, y));
        boolean result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && dragStroke != null) {
            result = dispatchContinuedStroke(false);
        } else {
            long total = Math.max(120, System.currentTimeMillis() - dragSegmentStartAt + duration);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(dragPath, 0, total);
            result = dispatchStroke(stroke);
        }
        dragPath = null;
        dragStroke = null;
        return result;
    }

    private boolean dispatchContinuedStroke(boolean willContinue) {
        long duration = System.currentTimeMillis() - dragSegmentStartAt;
        if (duration <= 0) {
            duration = 1;
        }
        if (dragStroke == null) {
            dragStroke = new GestureDescription.StrokeDescription(dragPath, 0, duration, willContinue);
        } else {
            dragStroke = dragStroke.continueStroke(dragPath, 0, duration, willContinue);
        }
        return dispatchStroke(dragStroke);
    }

    private void resetDragPathAt(int x, int y) {
        dragPath.reset();
        dragPath.moveTo(Math.max(0, x), Math.max(0, y));
        dragSegmentStartAt = System.currentTimeMillis();
    }

    private boolean gesture(int x, int y, int x2, int y2, long duration) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        Path path = new Path();
        path.moveTo(Math.max(0, x), Math.max(0, y));
        path.lineTo(Math.max(0, x2), Math.max(0, y2));
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, duration);
        return dispatchStroke(stroke);
    }

    private boolean dispatchStroke(GestureDescription.StrokeDescription stroke) {
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);
        return dispatchGesture(builder.build(), null, null);
    }
}
