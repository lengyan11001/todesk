package top.bhzn.todesk;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public class RemoteService extends Service {
    static final String ACTION_START = "top.bhzn.todesk.START";
    static final String ACTION_STOP = "top.bhzn.todesk.STOP";
    static final String ACTION_STATUS = "top.bhzn.todesk.STATUS";
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";

    private static final String TAG = "RemoteService";
    private static final String CHANNEL_ID = "remote_status";
    private static final int NOTIFICATION_ID = 8021;
    private static final long FRAME_INTERVAL_MS = 90;
    private static final long HEARTBEAT_INTERVAL_MS = 15_000;
    private static final long RECONNECT_MIN_MS = 1_500;
    private static final long RECONNECT_MAX_MS = 30_000;
    private static final int JPEG_QUALITY = 48;
    private static volatile boolean running;
    private static volatile boolean mediaReady;
    private static volatile boolean serverConnected;

    private HandlerThread captureThread;
    private Handler captureHandler;
    private SimpleWebSocket webSocket;
    private PowerManager.WakeLock wakeLock;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenWidth;
    private int screenHeight;
    private int inputWidth;
    private int inputHeight;
    private int screenDpi;
    private long lastFrameAt;
    private long reconnectDelayMs = RECONNECT_MIN_MS;
    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running || captureHandler == null) return;
            sendHeartbeat();
            captureHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    static boolean isRunning() {
        return running;
    }

    static boolean isMediaReady() {
        return mediaReady;
    }

    static boolean isServerConnected() {
        return serverConnected;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        captureThread = new HandlerThread("bhzn-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_STATUS.equals(intent.getAction())) {
            sendStatus();
            return START_STICKY;
        }

        startForeground(NOTIFICATION_ID, notification("远控服务已开启"));
        running = true;
        acquireWakeLock();

        if (intent != null && ACTION_START.equals(intent.getAction())) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (resultCode == Activity.RESULT_OK && data != null) {
                startProjection(resultCode, data);
            }
        }

        connect();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        mediaReady = false;
        closeProjection();
        if (webSocket != null) {
            webSocket.close();
        }
        releaseWakeLock();
        serverConnected = false;
        if (captureThread != null) {
            captureThread.quitSafely();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startProjection(int resultCode, Intent data) {
        closeProjection();
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) return;
        final MediaProjection projection = manager.getMediaProjection(resultCode, data);
        if (projection == null) return;
        mediaProjection = projection;
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (mediaProjection != projection) {
                    return;
                }
                mediaProjection = null;
                mediaReady = false;
                releaseCaptureResources();
                sendStatus();
            }
        }, captureHandler);

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm != null) {
            wm.getDefaultDisplay().getRealMetrics(metrics);
        }
        screenDpi = metrics.densityDpi;
        inputWidth = Math.max(1, metrics.widthPixels);
        inputHeight = Math.max(1, metrics.heightPixels);
        int maxSide = 720;
        float scale = Math.min(1f, maxSide / (float) Math.max(metrics.widthPixels, metrics.heightPixels));
        screenWidth = Math.max(1, Math.round(metrics.widthPixels * scale));
        screenHeight = Math.max(1, Math.round(metrics.heightPixels * scale));
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                captureFrame(reader);
            }
        }, captureHandler);
        virtualDisplay = projection.createVirtualDisplay(
                "BHZN-ToDesk",
                screenWidth,
                screenHeight,
                screenDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler
        );
        mediaReady = true;
        sendStatus();
    }

    private void closeProjection() {
        MediaProjection projection = mediaProjection;
        mediaProjection = null;
        mediaReady = false;
        releaseCaptureResources();
        if (projection != null) {
            try {
                projection.stop();
            } catch (Exception e) {
                Log.w(TAG, "stop projection failed", e);
            }
        }
    }

    private void releaseCaptureResources() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    private void captureFrame(ImageReader reader) {
        long now = System.currentTimeMillis();
        if (now - lastFrameAt < FRAME_INTERVAL_MS) {
            Image skipped = reader.acquireLatestImage();
            if (skipped != null) skipped.close();
            return;
        }
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        lastFrameAt = now;
        try {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;
            Bitmap bitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight);
            bitmap.recycle();
            sendFrame(cropped);
            cropped.recycle();
        } catch (Exception e) {
            Log.w(TAG, "capture frame failed", e);
        } finally {
            image.close();
        }
    }

    private void sendFrame(Bitmap bitmap) {
        SimpleWebSocket socket = webSocket;
        if (socket == null) return;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
        String data = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        JSONObject msg = JsonUtil.object();
        JsonUtil.put(msg, "type", "frame");
        JsonUtil.put(msg, "image", data);
        JsonUtil.put(msg, "width", screenWidth);
        JsonUtil.put(msg, "height", screenHeight);
        JsonUtil.put(msg, "timestamp", System.currentTimeMillis());
        socket.send(msg.toString());
    }

    private void connect() {
        if (webSocket != null) {
            return;
        }
        String url = AppPrefs.serverUrl(this).replace("https://", "wss://").replace("http://", "ws://");
        if (!url.endsWith("/ws")) {
            url = url + "/ws";
        }
        if (url.startsWith("ws://") && !isLocalWebSocket(url)) {
            Log.w(TAG, "cleartext websocket is disabled for non-local servers: " + url);
            scheduleReconnect();
            return;
        }
        webSocket = new SimpleWebSocket(url, new SimpleWebSocket.Listener() {
            @Override
            public void onOpen() {
                serverConnected = true;
                reconnectDelayMs = RECONNECT_MIN_MS;
                sendHello();
                startHeartbeat();
            }

            @Override
            public void onMessage(String text) {
                handleMessage(text);
            }

            @Override
            public void onClosed() {
                serverConnected = false;
                RemoteService.this.webSocket = null;
                scheduleReconnect();
            }

            @Override
            public void onError(Exception error) {
                Log.w(TAG, "websocket failed", error);
                serverConnected = false;
                RemoteService.this.webSocket = null;
                scheduleReconnect();
            }
        });
        webSocket.connect();
    }

    private boolean isLocalWebSocket(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.US);
            return host.equals("localhost") || host.equals("127.0.0.1") || host.startsWith("127.") || host.equals("::1");
        } catch (Exception e) {
            return false;
        }
    }

    private void scheduleReconnect() {
        if (!running || captureHandler == null) return;
        final long delay = reconnectDelayMs;
        reconnectDelayMs = Math.min(RECONNECT_MAX_MS, reconnectDelayMs * 2);
        captureHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                connect();
            }
        }, delay);
    }

    private void startHeartbeat() {
        if (captureHandler == null) return;
        captureHandler.removeCallbacks(heartbeatRunnable);
        captureHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
    }

    private void sendHello() {
        JSONObject msg = baseStatus("hello-device");
        SimpleWebSocket socket = webSocket;
        if (socket != null) {
            socket.send(msg.toString());
        }
    }

    private void sendStatus() {
        JSONObject msg = baseStatus("status");
        SimpleWebSocket socket = webSocket;
        if (socket != null) {
            socket.send(msg.toString());
        }
    }

    private void sendHeartbeat() {
        JSONObject msg = baseStatus("heartbeat");
        SimpleWebSocket socket = webSocket;
        if (socket != null && !socket.send(msg.toString())) {
            serverConnected = false;
            webSocket = null;
            scheduleReconnect();
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
        if (manager == null) return;
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BHZN-ToDesk:remote");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    private JSONObject baseStatus(String type) {
        JSONObject msg = JsonUtil.object();
        JsonUtil.put(msg, "type", type);
        JsonUtil.put(msg, "id", AppPrefs.deviceId(this));
        JsonUtil.put(msg, "verificationCode", AppPrefs.deviceCode(this));
        JsonUtil.put(msg, "name", "BHZN Android");
        JsonUtil.put(msg, "model", Build.MANUFACTURER + " " + Build.MODEL);
        JsonUtil.put(msg, "platform", "android");
        JsonUtil.put(msg, "agentVersion", BuildConfig.VERSION_NAME);
        JsonUtil.put(msg, "androidVersion", Build.VERSION.RELEASE);
        JsonUtil.put(msg, "permissions", PermissionState.toJson(this, mediaReady));
        JsonUtil.put(msg, "controlEnabled", mediaReady && PermissionState.isInputControlReady(this));
        JSONObject screen = JsonUtil.object();
        JsonUtil.put(screen, "width", screenWidth);
        JsonUtil.put(screen, "height", screenHeight);
        JsonUtil.put(screen, "inputWidth", inputWidth);
        JsonUtil.put(screen, "inputHeight", inputHeight);
        JsonUtil.put(msg, "screen", screen);
        JsonUtil.put(msg, "rtcCapabilities", rtcCapabilities());
        return msg;
    }

    private JSONObject rtcCapabilities() {
        JSONObject value = JsonUtil.object();
        JsonUtil.put(value, "webrtc", false);
        JsonUtil.put(value, "video", false);
        JsonUtil.put(value, "dataChannel", false);
        JsonUtil.put(value, "localNetwork", true);
        JsonUtil.put(value, "codecs", new JSONArray());
        JsonUtil.put(value, "maxFps", 0);
        JsonUtil.put(value, "version", BuildConfig.VERSION_NAME + ";native-webrtc-pending");
        return value;
    }

    private int scaleInputX(JSONObject msg, String key) {
        int value = msg.optInt(key);
        if (screenWidth <= 0 || inputWidth <= 0) return value;
        return Math.max(0, Math.round(value * (inputWidth / (float) screenWidth)));
    }

    private int scaleInputY(JSONObject msg, String key) {
        int value = msg.optInt(key);
        if (screenHeight <= 0 || inputHeight <= 0) return value;
        return Math.max(0, Math.round(value * (inputHeight / (float) screenHeight)));
    }

    private void handleMessage(String text) {
        JSONObject msg;
        try {
            msg = new JSONObject(text);
        } catch (Exception ignored) {
            return;
        }
        String type = msg.optString("type");
        if ("input".equals(type)) {
            String action = msg.optString("action");
            boolean ok = RemoteInputService.dispatchRemoteInput(
                    action,
                    scaleInputX(msg, "x"),
                    scaleInputY(msg, "y"),
                    scaleInputX(msg, "x2"),
                    scaleInputY(msg, "y2"),
                    msg.optLong("duration", 80),
                    msg.optString("key"),
                    msg.optString("code"),
                    msg.optString("text")
            );
            sendInputResult(msg, ok);
            if (!ok) {
                Log.w(TAG, "remote input failed: action=" + action
                        + ", accessibility=" + PermissionState.isAccessibilityEnabled(this)
                        + ", inputReady=" + PermissionState.isInputControlReady(this));
                sendStatus();
            }
        } else if ("control-request".equals(type)) {
            sendStatus();
        } else if ("stop-control".equals(type)) {
            sendStatus();
        } else if ("file-transfer".equals(type)) {
            handleFileTransfer(msg);
        } else if ("rtc-request".equals(type)) {
            String sessionId = msg.optString("sessionId");
            Log.w(TAG, "rtc-request rejected: session=" + sessionId + ", reason=native_webrtc_pending");
            sendRtcState(sessionId, "failed", "native_webrtc_pending");
            sendRtcStop(sessionId, "native_webrtc_pending");
        } else if ("rtc-offer".equals(type)) {
            String sessionId = msg.optString("sessionId");
            Log.w(TAG, "rtc-offer ignored: session=" + sessionId + ", reason=native_webrtc_pending");
            sendRtcState(sessionId, "failed", "native_webrtc_pending");
        } else if ("rtc-ice-candidate".equals(type)) {
            Log.i(TAG, "rtc-ice-candidate ignored: session=" + msg.optString("sessionId"));
        } else if ("rtc-stopped".equals(type)) {
            Log.i(TAG, "rtc-stopped received: session=" + msg.optString("sessionId"));
        }
    }

    private void sendRtcState(String sessionId, String state, String error) {
        JSONObject msg = JsonUtil.object();
        JsonUtil.put(msg, "type", "rtc-state");
        JsonUtil.put(msg, "sessionId", sessionId == null ? "" : sessionId);
        JsonUtil.put(msg, "deviceId", AppPrefs.deviceId(this));
        JsonUtil.put(msg, "state", state);
        JsonUtil.put(msg, "selectedCandidateType", "unknown");
        JsonUtil.put(msg, "rttMs", 0);
        JsonUtil.put(msg, "bitrateKbps", 0);
        JsonUtil.put(msg, "error", error == null ? "" : error);
        SimpleWebSocket socket = webSocket;
        if (socket != null) {
            socket.send(msg.toString());
        }
    }

    private void sendRtcStop(String sessionId, String reason) {
        JSONObject msg = JsonUtil.object();
        JsonUtil.put(msg, "type", "rtc-stop");
        JsonUtil.put(msg, "sessionId", sessionId == null ? "" : sessionId);
        JsonUtil.put(msg, "deviceId", AppPrefs.deviceId(this));
        JsonUtil.put(msg, "reason", reason == null ? "stopped" : reason);
        SimpleWebSocket socket = webSocket;
        if (socket != null) {
            socket.send(msg.toString());
        }
    }

    private void sendInputResult(JSONObject input, boolean ok) {
        JSONObject msg = JsonUtil.object();
        JsonUtil.put(msg, "type", "input-result");
        JsonUtil.put(msg, "sessionId", input.optString("sessionId"));
        JsonUtil.put(msg, "inputId", input.optString("inputId"));
        JsonUtil.put(msg, "action", input.optString("action"));
        JsonUtil.put(msg, "ok", ok);
        if (!ok) {
            JsonUtil.put(msg, "error", PermissionState.isInputControlReady(this) ? "dispatch_failed" : "input_service_not_ready");
        }
        SimpleWebSocket socket = webSocket;
        if (socket != null) {
            socket.send(msg.toString());
        }
    }

    private void handleFileTransfer(final JSONObject msg) {
        final String transferId = msg.optString("transferId");
        final String fileName = msg.optString("fileName", "file.bin");
        final long size = msg.optLong("size", 0);
        final String sha256 = msg.optString("sha256");
        final String url = msg.optString("url");
        FileTransferReceiver.receive(this, transferId, fileName, size, sha256, url, new FileTransferReceiver.Callback() {
            @Override
            public void onStatus(String status, String path, long bytes, String error) {
                sendFileTransferStatus(transferId, status, path, bytes, error);
            }
        });
    }

    private void sendFileTransferStatus(String transferId, String status, String path, long bytes, String error) {
        JSONObject msg = JsonUtil.object();
        JsonUtil.put(msg, "type", "file-transfer-status");
        JsonUtil.put(msg, "transferId", transferId);
        JsonUtil.put(msg, "status", status);
        JsonUtil.put(msg, "path", path == null ? "" : path);
        JsonUtil.put(msg, "bytes", bytes);
        JsonUtil.put(msg, "error", error == null ? "" : error);
        SimpleWebSocket socket = webSocket;
        if (socket != null) {
            socket.send(msg.toString());
        }
    }

    private Notification notification(String text) {
        Intent launchIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        Intent stopIntent = new Intent(this, RemoteService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("BHZN ToDesk")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "远控服务", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示远程控制服务运行状态");
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
