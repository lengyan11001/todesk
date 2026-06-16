package top.bhzn.todesk;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
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
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public class RemoteService extends Service implements AndroidRtcManager.Bridge {
    static final String ACTION_START = "top.bhzn.todesk.START";
    static final String ACTION_STOP = "top.bhzn.todesk.STOP";
    static final String ACTION_STATUS = "top.bhzn.todesk.STATUS";
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";

    private static final String TAG = "RemoteService";
    private static final String CHANNEL_ID = "remote_status";
    private static final int NOTIFICATION_ID = 8021;
    private static final int IMAGE_READER_MAX_IMAGES = 3;
    private static final long HEARTBEAT_INTERVAL_MS = 8_000;
    private static final long RECONNECT_MIN_MS = 1_500;
    private static final long RECONNECT_MAX_MS = 30_000;
    private static volatile boolean running;
    private static volatile boolean mediaReady;
    private static volatile boolean serverConnected;
    private static volatile String lastError = "";

    private HandlerThread serviceThread;
    private Handler serviceHandler;
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
    private AndroidRtcManager rtcManager;
    private Bitmap latestFrame;
    private QualityProfile relayQuality = QualityProfile.relay();
    private QualityProfile captureQuality = QualityProfile.relay();
    private volatile int projectionGeneration;
    private final Set<String> relaySessions = new HashSet<>();
    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running || serviceHandler == null) return;
            sendHeartbeat();
            serviceHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
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

    static String lastError() {
        return lastError;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        DiagnosticLog.info(this, TAG, "service onCreate");
        createNotificationChannel();
        serviceThread = new HandlerThread("bhzn-service");
        serviceThread.start();
        serviceHandler = new Handler(serviceThread.getLooper());
        ensureCaptureThread();
        rtcManager = new AndroidRtcManager(this, this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            DiagnosticLog.info(this, TAG, "service stop requested");
            projectionGeneration++;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_STATUS.equals(intent.getAction())) {
            sendStatus();
            return START_STICKY;
        }

        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultCode != Activity.RESULT_OK || data == null) {
            lastError = "media_projection_denied";
            DiagnosticLog.info(this, TAG, "media projection denied or empty result");
            return START_NOT_STICKY;
        }

        try {
            DiagnosticLog.info(this, TAG, "foreground start begin");
            startRemoteForeground();
            running = true;
            lastError = "";
            acquireWakeLock();
            final int projectionResultCode = resultCode;
            final Intent projectionData = data;
            final int generation = ++projectionGeneration;
            if (serviceHandler == null) {
                throw new IllegalStateException("service handler not ready");
            }
            serviceHandler.post(new Runnable() {
                @Override
                public void run() {
                    startRemoteSession(generation, projectionResultCode, projectionData);
                }
            });
            DiagnosticLog.info(this, TAG, "foreground start posted generation=" + generation);
            return START_STICKY;
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
            Log.e(TAG, "start media projection service failed", e);
            DiagnosticLog.error(this, TAG, "start media projection service failed", e);
            running = false;
            mediaReady = false;
            releaseWakeLock();
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    @Override
    public void onDestroy() {
        DiagnosticLog.info(this, TAG, "service onDestroy");
        projectionGeneration++;
        running = false;
        mediaReady = false;
        closeProjection();
        if (rtcManager != null) {
            rtcManager.closeAll();
        }
        if (webSocket != null) {
            webSocket.close();
        }
        if (serviceHandler != null) {
            serviceHandler.removeCallbacksAndMessages(null);
        }
        if (captureHandler != null) {
            captureHandler.removeCallbacksAndMessages(null);
        }
        releaseWakeLock();
        serverConnected = false;
        if (serviceThread != null) {
            serviceThread.quitSafely();
            serviceThread = null;
            serviceHandler = null;
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
            captureHandler = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startRemoteSession(int generation, int resultCode, Intent data) {
        if (!running || generation != projectionGeneration) {
            DiagnosticLog.info(this, TAG, "stale remote session ignored generation=" + generation
                    + " current=" + projectionGeneration);
            return;
        }
        try {
            DiagnosticLog.info(this, TAG, "remote session start begin generation=" + generation);
            startProjectionBlocking(generation, resultCode, data);
            if (!running || generation != projectionGeneration) {
                DiagnosticLog.info(this, TAG, "remote session superseded after projection generation=" + generation
                        + " current=" + projectionGeneration);
                return;
            }
            DiagnosticLog.info(this, TAG, "projection initialized mediaReady=" + mediaReady
                    + " capture=" + screenWidth + "x" + screenHeight
                    + " input=" + inputWidth + "x" + inputHeight);
            connect();
            DiagnosticLog.info(this, TAG, "websocket connect requested");
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
            Log.e(TAG, "start remote session failed", e);
            DiagnosticLog.error(this, TAG, "start remote session failed", e);
            running = false;
            mediaReady = false;
            releaseWakeLock();
            stopSelf();
        }
    }

    private synchronized void ensureCaptureThread() {
        if (captureThread != null && captureThread.isAlive() && captureHandler != null) return;
        captureThread = new HandlerThread("bhzn-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        DiagnosticLog.info(this, TAG, "capture thread ready");
    }

    private void restartCaptureThread() {
        if (captureHandler != null) {
            captureHandler.removeCallbacksAndMessages(null);
        }
        if (captureThread != null) {
            captureThread.quitSafely();
        }
        captureThread = null;
        captureHandler = null;
        ensureCaptureThread();
    }

    private void startProjectionBlocking(final int generation, final int resultCode, final Intent data) throws Exception {
        ensureCaptureThread();
        final Object lock = new Object();
        final boolean[] done = new boolean[] { false };
        final Exception[] error = new Exception[1];
        captureHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    startProjection(generation, resultCode, data);
                } catch (Exception e) {
                    error[0] = e;
                } finally {
                    synchronized (lock) {
                        done[0] = true;
                        lock.notifyAll();
                    }
                }
            }
        });
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 8_000;
            while (!done[0]) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                lock.wait(remaining);
            }
        }
        if (!done[0]) {
            DiagnosticLog.info(this, TAG, "capture thread start timed out; restarting");
            restartCaptureThread();
            throw new IllegalStateException("capture_thread_timeout");
        }
        if (error[0] != null) throw error[0];
    }

    private void startProjection(final int generation, int resultCode, Intent data) {
        DiagnosticLog.info(this, TAG, "startProjection begin generation=" + generation);
        closeProjection();
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("MediaProjectionManager unavailable");
        }
        DiagnosticLog.info(this, TAG, "getMediaProjection begin");
        final MediaProjection projection = manager.getMediaProjection(resultCode, data);
        if (projection == null) {
            throw new IllegalStateException("MediaProjection unavailable");
        }
        if (!running || generation != projectionGeneration) {
            DiagnosticLog.info(this, TAG, "projection token superseded before use generation=" + generation
                    + " current=" + projectionGeneration);
            try {
                projection.stop();
            } catch (Exception ignored) {
            }
            return;
        }
        mediaProjection = projection;
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (mediaProjection != projection) {
                    DiagnosticLog.info(RemoteService.this, TAG, "stale media projection stop ignored generation=" + generation);
                    return;
                }
                DiagnosticLog.info(RemoteService.this, TAG, "active media projection stopped by system generation=" + generation);
                mediaProjection = null;
                mediaReady = false;
                lastError = "media_projection_stopped";
                releaseCaptureResources();
                sendStatus();
            }
        }, captureHandler);

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm != null) {
            wm.getDefaultDisplay().getRealMetrics(metrics);
        }
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0 || metrics.densityDpi <= 0) {
            metrics = getResources().getDisplayMetrics();
        }
        screenDpi = metrics.densityDpi;
        inputWidth = Math.max(1, metrics.widthPixels);
        inputHeight = Math.max(1, metrics.heightPixels);
        captureQuality = preferredCaptureQuality();
        int[] size = scaledCaptureSize(captureQuality);
        screenWidth = size[0];
        screenHeight = size[1];
        DiagnosticLog.info(this, TAG, "create ImageReader " + screenWidth + "x" + screenHeight + " dpi=" + screenDpi);
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES);
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                captureFrame(reader);
            }
        }, captureHandler);
        DiagnosticLog.info(this, TAG, "create VirtualDisplay begin");
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
        if (virtualDisplay == null) {
            throw new IllegalStateException("VirtualDisplay unavailable");
        }
        mediaReady = true;
        DiagnosticLog.info(this, TAG, "startProjection complete");
        sendStatus();
    }

    private void closeProjection() {
        MediaProjection projection = mediaProjection;
        mediaProjection = null;
        mediaReady = false;
        releaseCaptureResources();
        if (projection != null) {
            DiagnosticLog.info(this, TAG, "closing active media projection");
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
        synchronized (this) {
            if (latestFrame != null) {
                latestFrame.recycle();
                latestFrame = null;
            }
        }
    }

    private synchronized void configureCaptureQuality(QualityProfile quality) {
        if (quality == null) return;
        captureQuality = quality;
    }

    private synchronized void recreateCaptureSurface(QualityProfile quality) {
        if (mediaProjection == null || quality == null || inputWidth <= 0 || inputHeight <= 0) return;
        int[] size = scaledCaptureSize(quality);
        if (imageReader != null && virtualDisplay != null && screenWidth == size[0] && screenHeight == size[1]) {
            return;
        }
        releaseCaptureResources();
        screenWidth = size[0];
        screenHeight = size[1];
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES);
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                captureFrame(reader);
            }
        }, captureHandler);
        virtualDisplay = mediaProjection.createVirtualDisplay(
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
        lastFrameAt = 0;
        sendStatus();
    }

    private synchronized QualityProfile preferredCaptureQuality() {
        QualityProfile selected = null;
        if (!relaySessions.isEmpty()) selected = relayQuality;
        if (rtcManager != null) {
            QualityProfile rtcQuality = rtcManager.preferredQuality();
            if (rtcQuality != null && (selected == null
                    || rtcQuality.maxSide > selected.maxSide
                    || rtcQuality.fps > selected.fps)) {
                selected = rtcQuality;
            }
        }
        if (selected != null) return selected;
        return captureQuality == null ? QualityProfile.relay() : captureQuality;
    }

    private int[] scaledCaptureSize(QualityProfile quality) {
        int maxSide = quality == null ? QualityProfile.relay().maxSide : quality.maxSide;
        int sourceWidth = Math.max(1, inputWidth);
        int sourceHeight = Math.max(1, inputHeight);
        float scale = Math.min(1f, maxSide / (float) Math.max(sourceWidth, sourceHeight));
        return new int[] {
                Math.max(1, Math.round(sourceWidth * scale)),
                Math.max(1, Math.round(sourceHeight * scale))
        };
    }

    private void captureFrame(ImageReader reader) {
        long now = System.currentTimeMillis();
        QualityProfile activeQuality = preferredCaptureQuality();
        if (!hasRealtimeViewers()) {
            Image skipped = reader.acquireLatestImage();
            if (skipped != null) skipped.close();
            return;
        }
        if (now - lastFrameAt < activeQuality.frameIntervalMs()) {
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
            updateLatestFrame(cropped);
            if (hasRelaySessions()) {
                sendFrame(cropped, relayQuality);
            }
            cropped.recycle();
        } catch (Exception e) {
            Log.w(TAG, "capture frame failed", e);
        } finally {
            image.close();
        }
    }

    private void sendFrame(Bitmap bitmap, QualityProfile quality) {
        SimpleWebSocket socket = webSocket;
        if (socket == null) return;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality == null ? QualityProfile.relay().jpegQuality : quality.jpegQuality, out);
        String data = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        JSONObject msg = JsonUtil.object();
        JsonUtil.put(msg, "type", "frame");
        JsonUtil.put(msg, "frameKind", "jpeg");
        JsonUtil.put(msg, "image", data);
        JsonUtil.put(msg, "width", screenWidth);
        JsonUtil.put(msg, "height", screenHeight);
        JsonUtil.put(msg, "inputWidth", inputWidth);
        JsonUtil.put(msg, "inputHeight", inputHeight);
        JsonUtil.put(msg, "timestamp", System.currentTimeMillis());
        socket.send(msg.toString());
    }

    private void updateLatestFrame(Bitmap bitmap) {
        synchronized (this) {
            if (latestFrame != null) {
                latestFrame.recycle();
            }
            latestFrame = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        }
    }

    private void connect() {
        if (webSocket != null && webSocket.isRunning()) {
            return;
        }
        webSocket = null;
        String url = AppPrefs.serverUrl(this).replace("https://", "wss://").replace("http://", "ws://");
        if (!url.endsWith("/ws")) {
            url = url + "/ws";
        }
        if (url.startsWith("ws://") && !isLocalWebSocket(url)) {
            Log.w(TAG, "cleartext websocket is disabled for non-local servers: " + url);
            DiagnosticLog.info(this, TAG, "cleartext websocket blocked");
            scheduleReconnect();
            return;
        }
        DiagnosticLog.info(this, TAG, "websocket connecting " + url);
        webSocket = new SimpleWebSocket(url, new SimpleWebSocket.Listener() {
            @Override
            public void onOpen() {
                serverConnected = true;
                reconnectDelayMs = RECONNECT_MIN_MS;
                DiagnosticLog.info(RemoteService.this, TAG, "websocket open");
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
                DiagnosticLog.info(RemoteService.this, TAG, "websocket closed");
                scheduleReconnect();
            }

            @Override
            public void onError(Exception error) {
                Log.w(TAG, "websocket failed", error);
                serverConnected = false;
                RemoteService.this.webSocket = null;
                DiagnosticLog.error(RemoteService.this, TAG, "websocket failed", error);
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
        if (!running || serviceHandler == null) return;
        final long delay = reconnectDelayMs;
        reconnectDelayMs = Math.min(RECONNECT_MAX_MS, reconnectDelayMs * 2);
        DiagnosticLog.info(this, TAG, "websocket reconnect scheduled " + delay + "ms");
        serviceHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                connect();
            }
        }, delay);
    }

    private void startHeartbeat() {
        if (serviceHandler == null) return;
        serviceHandler.removeCallbacks(heartbeatRunnable);
        serviceHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
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
            DiagnosticLog.info(this, TAG, "heartbeat send failed");
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
        JsonUtil.put(msg, "permissionDiagnostics", PermissionState.diagnostics(this));
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
        boolean available = rtcManager != null && rtcManager.isAvailable();
        JsonUtil.put(value, "webrtc", available);
        JsonUtil.put(value, "video", false);
        JsonUtil.put(value, "dataChannel", available);
        JsonUtil.put(value, "frameChannel", available);
        JsonUtil.put(value, "localNetwork", true);
        JsonUtil.put(value, "codecs", new JSONArray());
        JsonUtil.put(value, "maxFps", available ? 30 : 0);
        JsonUtil.put(value, "version", BuildConfig.VERSION_NAME + (available ? ";rtc-frame-channel" : ";native-webrtc-missing"));
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
            String sessionId = msg.optString("sessionId", "legacy-relay");
            if (sessionId.length() == 0) sessionId = "legacy-relay";
            relayQuality = QualityProfile.fromJson(msg.optJSONObject("quality"), QualityProfile.relay());
            synchronized (this) {
                relaySessions.add(sessionId);
            }
            configureCaptureQuality(relayQuality);
            Log.i(TAG, "control-request received: session=" + sessionId
                    + ", profile=" + relayQuality.profile
                    + ", maxSide=" + relayQuality.maxSide
                    + ", fps=" + relayQuality.fps);
            sendStatus();
        } else if ("stop-control".equals(type)) {
            String sessionId = msg.optString("sessionId", "");
            synchronized (this) {
                if (sessionId.length() == 0) relaySessions.clear();
                else relaySessions.remove(sessionId);
            }
            configureCaptureQuality(preferredCaptureQuality());
            sendStatus();
        } else if ("file-transfer".equals(type)) {
            handleFileTransfer(msg);
        } else if ("rtc-request".equals(type)) {
            String sessionId = msg.optString("sessionId");
            QualityProfile quality = QualityProfile.fromJson(msg.optJSONObject("quality"), QualityProfile.balanced());
            configureCaptureQuality(quality);
            if (rtcManager != null) {
                rtcManager.startSession(sessionId, msg.optJSONArray("iceServers"), quality);
            }
        } else if ("rtc-offer".equals(type)) {
            String sessionId = msg.optString("sessionId");
            if (rtcManager != null) {
                rtcManager.handleOffer(sessionId, msg.optString("sdp"));
            }
        } else if ("rtc-ice-candidate".equals(type)) {
            if (rtcManager != null) {
                rtcManager.handleCandidate(msg.optString("sessionId"), msg.optJSONObject("candidate"));
            }
        } else if ("rtc-stopped".equals(type)) {
            Log.i(TAG, "rtc-stopped received: session=" + msg.optString("sessionId"));
            if (rtcManager != null) {
                rtcManager.closeSession(msg.optString("sessionId"), msg.optString("reason", "peer_stopped"), false);
            }
        }
    }

    private synchronized boolean hasRelaySessions() {
        return !relaySessions.isEmpty();
    }

    private synchronized boolean hasRealtimeViewers() {
        return !relaySessions.isEmpty() || (rtcManager != null && rtcManager.hasActiveSessions());
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
        JsonUtil.put(msg, "bytesSent", 0);
        JsonUtil.put(msg, "bytesReceived", 0);
        JsonUtil.put(msg, "packetsLost", 0);
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
            JsonUtil.put(msg, "permissionDiagnostics", PermissionState.diagnostics(this));
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

    @Override
    public void sendRtcJson(JSONObject message) {
        SimpleWebSocket socket = webSocket;
        if (socket != null && message != null) {
            socket.send(message.toString());
        }
    }

    @Override
    public void onRtcInput(JSONObject msg, AndroidRtcManager.RtcInputResult callback) {
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
        if (!ok) {
            Log.w(TAG, "rtc remote input failed: action=" + action
                    + ", accessibility=" + PermissionState.isAccessibilityEnabled(this)
                    + ", inputReady=" + PermissionState.isInputControlReady(this));
            sendStatus();
        }
        if (callback != null) {
            callback.complete(ok, ok ? "" : (PermissionState.isInputControlReady(this) ? "dispatch_failed" : "input_service_not_ready"));
        }
    }

    @Override
    public synchronized Bitmap captureRtcBitmap() {
        if (latestFrame == null || latestFrame.isRecycled()) return null;
        return latestFrame.copy(Bitmap.Config.ARGB_8888, false);
    }

    @Override
    public String deviceId() {
        return AppPrefs.deviceId(this);
    }

    @Override
    public int screenWidth() {
        return screenWidth;
    }

    @Override
    public int screenHeight() {
        return screenHeight;
    }

    @Override
    public int inputWidth() {
        return inputWidth;
    }

    @Override
    public int inputHeight() {
        return inputHeight;
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

    private void startRemoteForeground() {
        Notification value = notification("远控服务已开启");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, value, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, value);
        }
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
