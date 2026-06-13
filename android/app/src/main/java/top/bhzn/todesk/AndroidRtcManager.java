package top.bhzn.todesk;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    private static final byte[] FRAME_MAGIC = new byte[] { 'B', 'H', 'Z', 'F', '1' };
    private static final long FRAME_INTERVAL_MS = 66;
    private static final long FAST_FRAME_MS = 900;
    private static final int JPEG_QUALITY = 48;

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

    synchronized void startSession(String sessionId, JSONArray iceServers) {
        if (sessionId == null || sessionId.length() == 0) return;
        closeSession(sessionId, "replaced", false);
        if (!ensureFactory()) {
            sendState(sessionId, "failed", "unknown", "webrtc_unavailable");
            sendStop(sessionId, "webrtc_unavailable");
            return;
        }
        try {
            PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(toIceServers(iceServers));
            config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
            RtcSession session = new RtcSession(sessionId);
            PeerConnection pc = factory.createPeerConnection(config, session);
            if (pc == null) {
                sendState(sessionId, "failed", "unknown", "peer_connection_failed");
                sendStop(sessionId, "peer_connection_failed");
                return;
            }
            session.pc = pc;
            sessions.put(sessionId, session);
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
        if (session.frameChannel != null) {
            try {
                session.frameChannel.close();
            } catch (Throwable ignored) {
            }
        }
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
        if (!ok) JsonUtil.put(msg, "error", error == null ? "dispatch_failed" : error);
        byte[] bytes = msg.toString().getBytes(StandardCharsets.UTF_8);
        channel.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
    }

    private void attachFrameChannel(final RtcSession session, final DataChannel channel) {
        session.frameChannel = channel;
        channel.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(long previousAmount) {
            }

            @Override
            public void onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) {
                    sendState(session.sessionId, "connected", "unknown", "");
                    startFrameLoop(session);
                } else if (channel.state() == DataChannel.State.CLOSED) {
                    closeSession(session.sessionId, "frame_closed", true);
                }
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
            }
        });
    }

    private void startFrameLoop(final RtcSession session) {
        if (session.senderStarted.getAndSet(true)) return;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!session.closed.get()) {
                    DataChannel channel = session.frameChannel;
                    if (channel == null || channel.state() != DataChannel.State.OPEN) break;
                    Bitmap bitmap = bridge.captureRtcBitmap();
                    if (bitmap != null) {
                        try {
                            byte[] frame = encodeFrame(bitmap);
                            channel.send(new DataChannel.Buffer(ByteBuffer.wrap(frame), true));
                        } catch (Throwable error) {
                            Log.w(TAG, "send rtc frame failed", error);
                        } finally {
                            bitmap.recycle();
                        }
                    }
                    long delay = System.currentTimeMillis() < session.fastUntil ? 33 : FRAME_INTERVAL_MS;
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                closeSession(session.sessionId, "frame_loop_stopped", true);
            }
        }, "bhzn-rtc-frames-" + session.sessionId);
        thread.start();
    }

    private byte[] encodeFrame(Bitmap bitmap) throws Exception {
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, image);
        JSONObject header = JsonUtil.object();
        JsonUtil.put(header, "type", "frame");
        JsonUtil.put(header, "deviceId", bridge.deviceId());
        JsonUtil.put(header, "frameId", System.currentTimeMillis());
        JsonUtil.put(header, "frameKind", "jpeg");
        JsonUtil.put(header, "width", bridge.screenWidth());
        JsonUtil.put(header, "height", bridge.screenHeight());
        JsonUtil.put(header, "inputWidth", bridge.inputWidth());
        JsonUtil.put(header, "inputHeight", bridge.inputHeight());
        JsonUtil.put(header, "timestamp", System.currentTimeMillis());
        JsonUtil.put(header, "transport", "rtc-datachannel");
        byte[] headerBytes = header.toString().getBytes(StandardCharsets.UTF_8);
        byte[] imageBytes = image.toByteArray();
        ByteBuffer out = ByteBuffer.allocate(FRAME_MAGIC.length + 4 + headerBytes.length + imageBytes.length);
        out.put(FRAME_MAGIC);
        out.order(ByteOrder.LITTLE_ENDIAN).putInt(headerBytes.length);
        out.put(headerBytes);
        out.put(imageBytes);
        return out.array();
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
        final AtomicBoolean closed = new AtomicBoolean(false);
        final AtomicBoolean senderStarted = new AtomicBoolean(false);
        PeerConnection pc;
        DataChannel controlChannel;
        DataChannel frameChannel;
        volatile long fastUntil;

        RtcSession(String sessionId) {
            this.sessionId = sessionId;
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
            sendState(sessionId, value, "unknown", "");
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
            } else if ("frames".equals(label)) {
                attachFrameChannel(this, channel);
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
