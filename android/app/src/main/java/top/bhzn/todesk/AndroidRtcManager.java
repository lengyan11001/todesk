package top.bhzn.todesk;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SessionDescription;
import org.webrtc.SdpObserver;
import org.webrtc.JavaI420Buffer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

final class AndroidRtcManager {
    interface Bridge {
        void sendRtcJson(JSONObject message);
        void onRtcInput(JSONObject message, RtcInputResult callback);
        Bitmap captureRtcBitmap();
        String deviceId();
        int screenWidth();
        int screenHeight();
        int inputWidth();
        int inputHeight();
    }

    interface RtcInputResult {
        void complete(boolean ok, String error);
    }

    private static final String TAG = "AndroidRtcManager";
    private static final long FAST_FRAME_MS = 900;

    private final Context context;
    private final Bridge bridge;
    private final Map<String, RtcSession> sessions = new HashMap<>();
    private PeerConnectionFactory factory;
    private boolean initialized;

    AndroidRtcManager(Context context, Bridge bridge) {
        this.context = context.getApplicationContext();
        this.bridge = bridge;
    }

    boolean isAvailable() {
        try {
            Class.forName("org.webrtc.PeerConnectionFactory");
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    synchronized boolean hasActiveSessions() {
        return !sessions.isEmpty();
    }

    synchronized QualityProfile preferredQuality() {
        QualityProfile best = null;
        for (RtcSession session : sessions.values()) {
            if (best == null || session.quality.fps > best.fps || session.quality.maxSide > best.maxSide) {
                best = session.quality;
            }
        }
        return best;
    }

    synchronized void startSession(String sessionId, JSONArray iceServers, QualityProfile quality) {
        if (sessionId == null || sessionId.length() == 0) return;
        closeSession(sessionId, "replaced", false);
        final QualityProfile sessionQuality = quality == null ? QualityProfile.balanced() : quality;
        if (!ensureFactory()) {
            sendState(sessionId, "failed", "unknown", "webrtc_unavailable");
            sendStop(sessionId, "webrtc_unavailable");
            return;
        }
        try {
            PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(toIceServers(iceServers));
            config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
            RtcSession session = new RtcSession(sessionId, sessionQuality);
            PeerConnection pc = factory.createPeerConnection(config, session);
            if (pc == null) {
                sendState(sessionId, "failed", "unknown", "peer_connection_failed");
                sendStop(sessionId, "peer_connection_failed");
                return;
            }
            session.pc = pc;
            session.videoSource = factory.createVideoSource(false);
            session.videoSource.setIsScreencast(true);
            session.videoTrack = factory.createVideoTrack("screen", session.videoSource);
            if (pc.addTrack(session.videoTrack, Arrays.asList("bhzn")) == null) {
                throw new IllegalStateException("add video track failed");
            }
            sessions.put(sessionId, session);
            Log.i(TAG, "rtc session quality: session=" + sessionId
                    + ", profile=" + sessionQuality.profile
                    + ", maxSide=" + sessionQuality.maxSide
                    + ", fps=" + sessionQuality.fps
                    + ", jpeg=" + sessionQuality.jpegQuality);
            sendState(sessionId, "checking", "unknown", "");
        } catch (Throwable error) {
            Log.w(TAG, "start rtc failed", error);
            sendState(sessionId, "failed", "unknown", error.getMessage());
            sendStop(sessionId, "start_failed");
        }
    }

    synchronized void handleOffer(String sessionId, String sdp) {
        final RtcSession session = sessions.get(sessionId);
        if (session == null || session.pc == null) {
            sendState(sessionId, "failed", "unknown", "bad_rtc_session");
            return;
        }
        if (sdp == null || sdp.trim().length() == 0) {
            sendState(sessionId, "failed", "unknown", "empty_offer");
            return;
        }
        session.pc.setRemoteDescription(new SdpObserverAdapter() {
            @Override
            public void onSetSuccess() {
                createAnswer(session);
            }

            @Override
            public void onSetFailure(String error) {
                sendState(session.sessionId, "failed", "unknown", error);
                closeSession(session.sessionId, "offer_failed", true);
            }
        }, new SessionDescription(SessionDescription.Type.OFFER, sdp));
    }

    synchronized void handleCandidate(String sessionId, JSONObject value) {
        RtcSession session = sessions.get(sessionId);
        if (session == null || session.pc == null || value == null) return;
        String candidate = value.optString("candidate", "");
        if (candidate.length() == 0) return;
        String mid = value.optString("sdpMid", "");
        int index = value.optInt("sdpMLineIndex", 0);
        try {
            session.pc.addIceCandidate(new IceCandidate(mid, index, candidate));
        } catch (Throwable error) {
            Log.w(TAG, "add ice candidate failed", error);
        }
    }

    synchronized void closeSession(String sessionId, String reason, boolean notify) {
        RtcSession session = sessions.remove(sessionId);
        if (session == null) return;
        session.closed.set(true);
        if (session.videoTrack != null) session.videoTrack.dispose();
        if (session.videoSource != null) session.videoSource.dispose();
        if (session.controlChannel != null) {
            try {
                session.controlChannel.close();
            } catch (Throwable ignored) {
            }
        }
        if (session.pc != null) {
            try {
                session.pc.close();
                session.pc.dispose();
            } catch (Throwable ignored) {
            }
        }
        if (notify) sendStop(sessionId, reason);
    }

    synchronized void closeAll() {
        List<String> ids = new ArrayList<>(sessions.keySet());
        for (String id : ids) {
            closeSession(id, "service_stopped", true);
        }
        if (factory != null) {
            try {
                factory.dispose();
            } catch (Throwable ignored) {
            }
            factory = null;
        }
        initialized = false;
    }

    private boolean ensureFactory() {
        if (factory != null) return true;
        if (!isAvailable()) return false;
        try {
            if (!initialized) {
                PeerConnectionFactory.InitializationOptions options =
                        PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions();
                PeerConnectionFactory.initialize(options);
                initialized = true;
            }
            factory = PeerConnectionFactory.builder().createPeerConnectionFactory();
            return factory != null;
        } catch (Throwable error) {
            Log.w(TAG, "initialize webrtc failed", error);
            return false;
        }
    }

    private void createAnswer(final RtcSession session) {
        session.pc.createAnswer(new SdpObserverAdapter() {
            @Override
            public void onCreateSuccess(final SessionDescription description) {
                session.pc.setLocalDescription(new SdpObserverAdapter() {
                    @Override
                    public void onSetSuccess() {
                        JSONObject msg = JsonUtil.object();
                        JsonUtil.put(msg, "type", "rtc-answer");
                        JsonUtil.put(msg, "sessionId", session.sessionId);
                        JsonUtil.put(msg, "deviceId", bridge.deviceId());
                        JsonUtil.put(msg, "sdp", description.description);
                        bridge.sendRtcJson(msg);
                        startVideoLoop(session);
                    }

                    @Override
                    public void onSetFailure(String error) {
                        sendState(session.sessionId, "failed", "unknown", error);
                    }
                }, description);
            }

            @Override
            public void onCreateFailure(String error) {
                sendState(session.sessionId, "failed", "unknown", error);
            }
        }, new MediaConstraints());
    }

    private List<PeerConnection.IceServer> toIceServers(JSONArray values) {
        List<PeerConnection.IceServer> out = new ArrayList<>();
        if (values == null) return out;
        for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.optJSONObject(i);
            if (item == null) continue;
            List<String> urls = new ArrayList<>();
            Object rawUrls = item.opt("urls");
            if (rawUrls instanceof JSONArray) {
                JSONArray array = (JSONArray) rawUrls;
                for (int j = 0; j < array.length(); j++) {
                    String url = array.optString(j, "");
                    if (url.trim().length() > 0) urls.add(url);
                }
            } else {
                String url = item.optString("urls", "");
                if (url.trim().length() > 0) urls.add(url);
            }
            if (urls.isEmpty()) continue;
            PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder(urls);
            String username = item.optString("username", "");
            String credential = item.optString("credential", "");
            if (username.length() > 0) builder.setUsername(username);
            if (credential.length() > 0) builder.setPassword(credential);
            out.add(builder.createIceServer());
        }
        return out;
    }

    private void attachControlChannel(final RtcSession session, final DataChannel channel) {
        session.controlChannel = channel;
        channel.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(long previousAmount) {
            }

            @Override
            public void onStateChange() {
                if (channel.state() == DataChannel.State.CLOSED) {
                    closeSession(session.sessionId, "control_closed", true);
                }
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                if (buffer.binary) return;
                ByteBuffer data = buffer.data.slice();
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                handleControlText(session, new String(bytes, StandardCharsets.UTF_8));
            }
        });
    }

    private void handleControlText(final RtcSession session, String text) {
        JSONObject msg;
        try {
            msg = new JSONObject(text);
        } catch (Exception ignored) {
            return;
        }
        if (!"input".equals(msg.optString("type"))) return;
        JsonUtil.put(msg, "sessionId", session.sessionId);
        session.fastUntil = System.currentTimeMillis() + FAST_FRAME_MS;
        bridge.onRtcInput(msg, new RtcInputResult() {
            @Override
            public void complete(boolean ok, String error) {
                sendInputResult(session, msg, ok, error);
            }
        });
    }

    private void sendInputResult(RtcSession session, JSONObject input, boolean ok, String error) {
        DataChannel channel = session.controlChannel;
        if (channel == null || channel.state() != DataChannel.State.OPEN) return;
        JSONObject msg = JsonUtil.object();
        JsonUtil.put(msg, "type", "input-result");
        JsonUtil.put(msg, "sessionId", session.sessionId);
        JsonUtil.put(msg, "inputId", input.optString("inputId"));
        JsonUtil.put(msg, "action", input.optString("action"));
        JsonUtil.put(msg, "ok", ok);
        if (!ok) {
            JsonUtil.put(msg, "error", error == null ? "dispatch_failed" : error);
            JsonUtil.put(msg, "permissionDiagnostics", PermissionState.diagnostics(context));
        }
        byte[] bytes = msg.toString().getBytes(StandardCharsets.UTF_8);
        channel.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
    }

    private void startVideoLoop(final RtcSession session) {
        if (session.senderStarted.getAndSet(true)) return;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!session.closed.get()) {
                    Bitmap captured = bridge.captureRtcBitmap();
                    Bitmap bitmap = scaleBitmap(captured, session.quality.maxSide);
                    if (bitmap != null) {
                        try {
                            VideoFrame videoFrame = bitmapToVideoFrame(bitmap);
                            if (videoFrame != null) {
                                session.videoSource.getCapturerObserver().onFrameCaptured(videoFrame);
                                videoFrame.release();
                            }
                        } catch (Throwable error) {
                            Log.w(TAG, "publish rtc video frame failed", error);
                        } finally {
                            if (bitmap != captured && !bitmap.isRecycled()) bitmap.recycle();
                            if (captured != null && captured != bitmap && !captured.isRecycled()) captured.recycle();
                        }
                    }
                    long delay = System.currentTimeMillis() < session.fastUntil ? 33 : session.quality.frameIntervalMs();
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                closeSession(session.sessionId, "video_loop_stopped", true);
            }
        }, "bhzn-rtc-video-" + session.sessionId);
        thread.start();
    }

    private Bitmap scaleBitmap(Bitmap bitmap, int maxSide) {
        if (bitmap == null || bitmap.isRecycled()) return null;
        int side = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (maxSide <= 0 || side <= maxSide) return bitmap;
        float scale = maxSide / (float) side;
        int width = Math.max(2, Math.round(bitmap.getWidth() * scale) & ~1);
        int height = Math.max(2, Math.round(bitmap.getHeight() * scale) & ~1);
        return Bitmap.createScaledBitmap(bitmap, width, height, true);
    }

    private VideoFrame bitmapToVideoFrame(Bitmap bitmap) {
        int width = bitmap.getWidth() & ~1;
        int height = bitmap.getHeight() & ~1;
        if (width < 2 || height < 2) return null;
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int chromaWidth = width / 2;
        int chromaHeight = height / 2;
        ByteBuffer y = ByteBuffer.allocateDirect(width * height);
        ByteBuffer u = ByteBuffer.allocateDirect(chromaWidth * chromaHeight);
        ByteBuffer v = ByteBuffer.allocateDirect(chromaWidth * chromaHeight);
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int color = pixels[row * width + col];
                y.put(row * width + col, (byte) rgbToY((color >> 16) & 0xff, (color >> 8) & 0xff, color & 0xff));
            }
        }
        for (int row = 0; row < height; row += 2) {
            for (int col = 0; col < width; col += 2) {
                int red = 0;
                int green = 0;
                int blue = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int color = pixels[(row + dy) * width + col + dx];
                        red += (color >> 16) & 0xff;
                        green += (color >> 8) & 0xff;
                        blue += color & 0xff;
                    }
                }
                int index = (row / 2) * chromaWidth + (col / 2);
                u.put(index, (byte) rgbToU(red / 4, green / 4, blue / 4));
                v.put(index, (byte) rgbToV(red / 4, green / 4, blue / 4));
            }
        }
        JavaI420Buffer buffer = JavaI420Buffer.wrap(width, height, y, width, u, chromaWidth, v, chromaWidth, new Runnable() {
            @Override
            public void run() {
            }
        });
        return new VideoFrame(buffer, 0, System.nanoTime());
    }

    private static int rgbToY(int red, int green, int blue) {
        return clamp(((66 * red + 129 * green + 25 * blue + 128) >> 8) + 16);
    }

    private static int rgbToU(int red, int green, int blue) {
        return clamp(((-38 * red - 74 * green + 112 * blue + 128) >> 8) + 128);
    }

    private static int rgbToV(int red, int green, int blue) {
        return clamp(((112 * red - 94 * green - 18 * blue + 128) >> 8) + 128);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private void sendState(String sessionId, String state, String candidateType, String error) {
        JSONObject msg = JsonUtil.object();
        JsonUtil.put(msg, "type", "rtc-state");
        JsonUtil.put(msg, "sessionId", sessionId == null ? "" : sessionId);
        JsonUtil.put(msg, "deviceId", bridge.deviceId());
        JsonUtil.put(msg, "state", state);
        JsonUtil.put(msg, "selectedCandidateType", candidateType == null ? "unknown" : candidateType);
        JsonUtil.put(msg, "rttMs", 0);
        JsonUtil.put(msg, "bitrateKbps", 0);
        JsonUtil.put(msg, "bytesSent", 0);
        JsonUtil.put(msg, "bytesReceived", 0);
        JsonUtil.put(msg, "packetsLost", 0);
        JsonUtil.put(msg, "error", error == null ? "" : error);
        bridge.sendRtcJson(msg);
    }

    private void sendState(RtcSession session, String state, int bitrateKbps, String error) {
        if (session == null) return;
        JSONObject msg = JsonUtil.object();
        JsonUtil.put(msg, "type", "rtc-state");
        JsonUtil.put(msg, "sessionId", session.sessionId);
        JsonUtil.put(msg, "deviceId", bridge.deviceId());
        JsonUtil.put(msg, "state", state);
        JsonUtil.put(msg, "selectedCandidateType", session.selectedCandidateType);
        JsonUtil.put(msg, "rttMs", 0);
        JsonUtil.put(msg, "bitrateKbps", Math.max(0, bitrateKbps));
        JsonUtil.put(msg, "bytesSent", session.bytesSent);
        JsonUtil.put(msg, "bytesReceived", session.bytesReceived);
        JsonUtil.put(msg, "packetsLost", session.packetsLost);
        JsonUtil.put(msg, "error", error == null ? "" : error);
        bridge.sendRtcJson(msg);
    }

    private void sendStop(String sessionId, String reason) {
        JSONObject msg = JsonUtil.object();
        JsonUtil.put(msg, "type", "rtc-stop");
        JsonUtil.put(msg, "sessionId", sessionId == null ? "" : sessionId);
        JsonUtil.put(msg, "deviceId", bridge.deviceId());
        JsonUtil.put(msg, "reason", reason == null ? "stopped" : reason);
        bridge.sendRtcJson(msg);
    }

    private final class RtcSession implements PeerConnection.Observer {
        final String sessionId;
        final QualityProfile quality;
        final AtomicBoolean closed = new AtomicBoolean(false);
        final AtomicBoolean senderStarted = new AtomicBoolean(false);
        PeerConnection pc;
        DataChannel controlChannel;
        VideoSource videoSource;
        VideoTrack videoTrack;
        volatile long fastUntil;
        volatile String selectedCandidateType = "unknown";
        volatile long bytesSent;
        volatile long bytesReceived;
        volatile long packetsLost;
        volatile long lastStatsAt = System.currentTimeMillis();
        volatile long lastStatsBytes;

        RtcSession(String sessionId, QualityProfile quality) {
            this.sessionId = sessionId;
            this.quality = quality == null ? QualityProfile.balanced() : quality;
        }

        @Override
        public void onSignalingChange(PeerConnection.SignalingState state) {
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
        }

        @Override
        public void onConnectionChange(PeerConnection.PeerConnectionState state) {
            String value = state == PeerConnection.PeerConnectionState.CONNECTED ? "connected"
                    : state == PeerConnection.PeerConnectionState.CONNECTING ? "checking"
                    : state == PeerConnection.PeerConnectionState.FAILED ? "failed"
                    : state == PeerConnection.PeerConnectionState.DISCONNECTED ? "disconnected"
                    : state == PeerConnection.PeerConnectionState.CLOSED ? "closed"
                    : "new";
            sendState(this, value, 0, "");
            if (state == PeerConnection.PeerConnectionState.FAILED || state == PeerConnection.PeerConnectionState.CLOSED) {
                closeSession(sessionId, "peer_" + value, true);
            }
        }

        @Override
        public void onIceConnectionReceivingChange(boolean receiving) {
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
        }

        @Override
        public void onIceCandidate(IceCandidate candidate) {
            JSONObject item = JsonUtil.object();
            JsonUtil.put(item, "candidate", candidate.sdp);
            JsonUtil.put(item, "sdpMid", candidate.sdpMid);
            JsonUtil.put(item, "sdpMLineIndex", candidate.sdpMLineIndex);
            JSONObject msg = JsonUtil.object();
            JsonUtil.put(msg, "type", "rtc-ice-candidate");
            JsonUtil.put(msg, "sessionId", sessionId);
            JsonUtil.put(msg, "deviceId", bridge.deviceId());
            JsonUtil.put(msg, "candidate", item);
            bridge.sendRtcJson(msg);
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] candidates) {
        }

        @Override
        public void onAddStream(MediaStream stream) {
        }

        @Override
        public void onRemoveStream(MediaStream stream) {
        }

        @Override
        public void onDataChannel(DataChannel channel) {
            String label = channel.label();
            if ("control".equals(label)) {
                attachControlChannel(this, channel);
            }
        }

        @Override
        public void onRenegotiationNeeded() {
        }

        @Override
        public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
        }
    }

    private static class SdpObserverAdapter implements SdpObserver {
        @Override
        public void onCreateSuccess(SessionDescription description) {
        }

        @Override
        public void onSetSuccess() {
        }

        @Override
        public void onCreateFailure(String error) {
        }

        @Override
        public void onSetFailure(String error) {
        }
    }
}
