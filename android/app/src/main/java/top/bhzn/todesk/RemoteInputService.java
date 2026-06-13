package top.bhzn.todesk;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class RemoteInputService extends AccessibilityService {
    private static final String TAG = "RemoteInputService";
    private static volatile RemoteInputService instance;
    private Path dragPath;
    private GestureDescription.StrokeDescription dragStroke;
    private long dragSegmentStartAt;

    static boolean isReady() {
        return instance != null;
    }

    static boolean dispatchRemoteInput(String action, int x, int y, int x2, int y2, long duration,
                                       String key, String code, String text) {
        RemoteInputService service = instance;
        if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "input service is not ready");
            return false;
        }
        return service.handleInput(action, x, y, x2, y2, duration, key, code, text);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            if (Build.VERSION.SDK_INT >= 33) {
                info.flags |= AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR;
            }
            setServiceInfo(info);
        }
        Log.i(TAG, "accessibility connected");
        notifyRemoteService();
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
            notifyRemoteService();
        }
        super.onDestroy();
    }

    private void notifyRemoteService() {
        if (RemoteService.isRunning()) {
            startService(new Intent(this, RemoteService.class).setAction(RemoteService.ACTION_STATUS));
        }
    }

    private boolean handleInput(String action, int x, int y, int x2, int y2, long duration,
                                String key, String code, String text) {
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
        if ("text".equals(action)) {
            return commitText(text);
        }
        if ("key".equals(action)) {
            return handleKey(key, code);
        }
        return false;
    }

    private boolean commitText(String text) {
        if (text == null || text.length() == 0) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                android.accessibilityservice.InputMethod inputMethod = getInputMethod();
                if (inputMethod != null && inputMethod.getCurrentInputConnection() != null) {
                    inputMethod.getCurrentInputConnection().commitText(text, 1, null);
                    return true;
                }
            } catch (Exception error) {
                Log.w(TAG, "commit text through input method failed", error);
            }
        }
        return runOnMainThreadSync(new InputWork() {
            @Override
            public boolean run() {
                AccessibilityNodeInfo node = findEditableNode();
                if (node == null) {
                    Log.w(TAG, "no editable node for text input");
                    return false;
                }
                try {
                    return insertTextIntoNode(node, text);
                } finally {
                    recycleNode(node);
                }
            }
        });
    }

    private boolean handleKey(String key, String code) {
        if (key != null && key.length() > 0 && key.codePointCount(0, key.length()) == 1
                && !" ".equals(key) && androidKeyCode(key, code) == KeyEvent.KEYCODE_UNKNOWN) {
            return commitText(key);
        }
        int keyCode = androidKeyCode(key, code);
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                android.accessibilityservice.InputMethod inputMethod = getInputMethod();
                if (inputMethod != null && inputMethod.getCurrentInputConnection() != null) {
                    android.accessibilityservice.InputMethod.AccessibilityInputConnection connection =
                            inputMethod.getCurrentInputConnection();
                    if (keyCode == KeyEvent.KEYCODE_DEL) {
                        connection.deleteSurroundingText(1, 0);
                    } else if (keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
                        connection.deleteSurroundingText(0, 1);
                    } else if (keyCode == KeyEvent.KEYCODE_ENTER) {
                        connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
                        connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
                    } else {
                        connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
                        connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
                    }
                    return true;
                }
            } catch (Exception error) {
                Log.w(TAG, "key through input method failed", error);
            }
        }
        return runOnMainThreadSync(new InputWork() {
            @Override
            public boolean run() {
                AccessibilityNodeInfo node = findEditableNode();
                if (node == null) {
                    Log.w(TAG, "no editable node for key input");
                    return false;
                }
                try {
                    return applyKeyToNode(node, keyCode);
                } finally {
                    recycleNode(node);
                }
            }
        });
    }

    private boolean runOnMainThreadSync(final InputWork work) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return work.run();
        }
        final boolean[] result = new boolean[] { false };
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    result[0] = work.run();
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(900, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return result[0];
    }

    private interface InputWork {
        boolean run();
    }

    private AccessibilityNodeInfo findEditableNode() {
        AccessibilityNodeInfo focusInput = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        AccessibilityNodeInfo candidate = editableOrChild(focusInput);
        if (candidate != null) {
            recycleIfDifferent(focusInput, candidate);
            return candidate;
        }
        recycleNode(focusInput);

        AccessibilityNodeInfo focusAccessibility = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        candidate = editableOrChild(focusAccessibility);
        if (candidate != null) {
            recycleIfDifferent(focusAccessibility, candidate);
            return candidate;
        }
        recycleNode(focusAccessibility);

        AccessibilityNodeInfo root = getRootInActiveWindow();
        candidate = editableOrChild(root);
        if (candidate != null) {
            recycleIfDifferent(root, candidate);
            return candidate;
        }
        recycleNode(root);
        return null;
    }

    private AccessibilityNodeInfo editableOrChild(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() && node.isFocusable()) return node;
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo result = editableOrChild(child);
            if (result != null) {
                recycleIfDifferent(child, result);
                return result;
            }
            recycleNode(child);
        }
        return null;
    }

    private boolean insertTextIntoNode(AccessibilityNodeInfo node, String inserted) {
        node.refresh();
        if (!node.isFocused()) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            node.refresh();
        }
        CharSequence existingText = node.getText();
        String existing = existingText == null || isHintText(node) ? "" : existingText.toString();
        int start = node.getTextSelectionStart();
        int end = node.getTextSelectionEnd();
        if (start < 0 || end < 0) {
            start = existing.length();
            end = existing.length();
        }
        start = clamp(start, 0, existing.length());
        end = clamp(end, 0, existing.length());
        int from = Math.min(start, end);
        int to = Math.max(start, end);
        String next = existing.substring(0, from) + inserted + existing.substring(to);
        int cursor = from + inserted.length();
        return setNodeTextAndSelection(node, next, cursor, cursor);
    }

    private boolean applyKeyToNode(AccessibilityNodeInfo node, int keyCode) {
        node.refresh();
        if (!node.isFocused()) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            node.refresh();
        }
        CharSequence existingText = node.getText();
        String existing = existingText == null || isHintText(node) ? "" : existingText.toString();
        int start = node.getTextSelectionStart();
        int end = node.getTextSelectionEnd();
        if (start < 0 || end < 0) {
            start = existing.length();
            end = existing.length();
        }
        start = clamp(start, 0, existing.length());
        end = clamp(end, 0, existing.length());
        int from = Math.min(start, end);
        int to = Math.max(start, end);
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            if (from != to) {
                String next = existing.substring(0, from) + existing.substring(to);
                return setNodeTextAndSelection(node, next, from, from);
            }
            if (from <= 0) return true;
            int removeFrom = previousCodePointIndex(existing, from);
            String next = existing.substring(0, removeFrom) + existing.substring(from);
            return setNodeTextAndSelection(node, next, removeFrom, removeFrom);
        }
        if (keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
            if (from != to) {
                String next = existing.substring(0, from) + existing.substring(to);
                return setNodeTextAndSelection(node, next, from, from);
            }
            if (from >= existing.length()) return true;
            int removeTo = nextCodePointIndex(existing, from);
            String next = existing.substring(0, from) + existing.substring(removeTo);
            return setNodeTextAndSelection(node, next, from, from);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            int cursor = from <= 0 ? 0 : previousCodePointIndex(existing, from);
            return setNodeSelection(node, cursor, cursor);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int cursor = from >= existing.length() ? existing.length() : nextCodePointIndex(existing, from);
            return setNodeSelection(node, cursor, cursor);
        }
        if (keyCode == KeyEvent.KEYCODE_MOVE_HOME) {
            return setNodeSelection(node, 0, 0);
        }
        if (keyCode == KeyEvent.KEYCODE_MOVE_END) {
            return setNodeSelection(node, existing.length(), existing.length());
        }
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            return insertTextIntoNode(node, "\n");
        }
        if (keyCode == KeyEvent.KEYCODE_TAB) {
            return insertTextIntoNode(node, "\t");
        }
        return false;
    }

    private boolean setNodeTextAndSelection(AccessibilityNodeInfo node, String text, int start, int end) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (!ok) return false;
        return setNodeSelection(node, start, end);
    }

    private boolean setNodeSelection(AccessibilityNodeInfo node, int start, int end) {
        Bundle args = new Bundle();
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, Math.max(0, start));
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, Math.max(0, end));
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args);
    }

    private int androidKeyCode(String key, String code) {
        String value = key == null || key.length() == 0 ? code : key;
        if (value == null) return KeyEvent.KEYCODE_UNKNOWN;
        String normalized = value.toLowerCase(Locale.US);
        if ("backspace".equals(normalized)) return KeyEvent.KEYCODE_DEL;
        if ("delete".equals(normalized)) return KeyEvent.KEYCODE_FORWARD_DEL;
        if ("enter".equals(normalized) || "return".equals(normalized)) return KeyEvent.KEYCODE_ENTER;
        if ("tab".equals(normalized)) return KeyEvent.KEYCODE_TAB;
        if ("escape".equals(normalized) || "esc".equals(normalized)) return KeyEvent.KEYCODE_ESCAPE;
        if ("arrowleft".equals(normalized) || "left".equals(normalized)) return KeyEvent.KEYCODE_DPAD_LEFT;
        if ("arrowright".equals(normalized) || "right".equals(normalized)) return KeyEvent.KEYCODE_DPAD_RIGHT;
        if ("arrowup".equals(normalized) || "up".equals(normalized)) return KeyEvent.KEYCODE_DPAD_UP;
        if ("arrowdown".equals(normalized) || "down".equals(normalized)) return KeyEvent.KEYCODE_DPAD_DOWN;
        if ("home".equals(normalized)) return KeyEvent.KEYCODE_MOVE_HOME;
        if ("end".equals(normalized)) return KeyEvent.KEYCODE_MOVE_END;
        if ("space".equals(normalized) || " ".equals(value)) return KeyEvent.KEYCODE_SPACE;
        return KeyEvent.KEYCODE_UNKNOWN;
    }

    private boolean isHintText(AccessibilityNodeInfo node) {
        return Build.VERSION.SDK_INT >= 26 && node.isShowingHintText();
    }

    private int previousCodePointIndex(String text, int index) {
        if (index <= 0) return 0;
        return text.offsetByCodePoints(index, -1);
    }

    private int nextCodePointIndex(String text, int index) {
        if (index >= text.length()) return text.length();
        return text.offsetByCodePoints(index, 1);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void recycleIfDifferent(AccessibilityNodeInfo owned, AccessibilityNodeInfo keep) {
        if (owned != null && owned != keep) recycleNode(owned);
    }

    private void recycleNode(AccessibilityNodeInfo node) {
        if (node != null && Build.VERSION.SDK_INT < 33) {
            node.recycle();
        }
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
