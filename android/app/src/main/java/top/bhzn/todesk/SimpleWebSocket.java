package top.bhzn.todesk;

import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

final class SimpleWebSocket {
    interface Listener {
        void onOpen();
        void onMessage(String text);
        void onClosed();
        void onError(Exception error);
    }

    private static final String TAG = "SimpleWebSocket";
    private final String url;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private volatile boolean running;
    private Socket socket;
    private OutputStream out;
    private Thread thread;

    SimpleWebSocket(String url, Listener listener) {
        this.url = url;
        this.listener = listener;
    }

    void connect() {
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                SimpleWebSocket.this.run();
            }
        }, "bhzn-websocket");
        thread.start();
    }

    void close() {
        running = false;
        try {
            sendFrame((byte) 0x8, new byte[0]);
        } catch (Exception ignored) {
        }
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
    }

    synchronized boolean send(String text) {
        if (!running || out == null) return false;
        try {
            sendFrame((byte) 0x1, text.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "send failed", e);
            return false;
        }
    }

    private void run() {
        try {
            URI uri = URI.create(url);
            boolean secure = "wss".equalsIgnoreCase(uri.getScheme());
            if (!secure && !"ws".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("Only ws:// and wss:// are supported");
            }
            int port = uri.getPort() > 0 ? uri.getPort() : (secure ? 443 : 80);
            String host = uri.getHost();
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();

            if (secure) {
                SSLSocket sslSocket = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, port);
                sslSocket.startHandshake();
                SSLSession session = sslSocket.getSession();
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)) {
                    throw new IllegalStateException("TLS hostname verification failed: " + host);
                }
                socket = sslSocket;
            } else {
                socket = new Socket(host, port);
            }
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(60_000);
            InputStream in = socket.getInputStream();
            out = socket.getOutputStream();

            byte[] nonce = new byte[16];
            random.nextBytes(nonce);
            String key = Base64.encodeToString(nonce, Base64.NO_WRAP);
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n";
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String responseHeaders = readHttpHeaders(in);
            String status = responseHeaders.split("\r\n", 2)[0];
            if (!status.contains("101")) {
                throw new IllegalStateException("Bad websocket response: " + status);
            }

            listener.onOpen();
            readLoop(in);
        } catch (Exception e) {
            if (running) listener.onError(e);
        } finally {
            running = false;
            try {
                if (socket != null) socket.close();
            } catch (Exception ignored) {
            }
            listener.onClosed();
        }
    }

    private void readLoop(InputStream in) throws Exception {
        while (running) {
            int b0 = in.read();
            if (b0 < 0) break;
            int b1 = readByte(in);
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = ((long) readByte(in) << 8) | readByte(in);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | readByte(in);
                }
            }
            byte[] mask = null;
            if (masked) {
                mask = readBytes(in, 4);
            }
            if (len > 2_000_000) {
                throw new IllegalStateException("Frame too large: " + len);
            }
            byte[] payload = readBytes(in, (int) len);
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i % 4];
                }
            }
            if (opcode == 0x1) {
                listener.onMessage(new String(payload, StandardCharsets.UTF_8));
            } else if (opcode == 0x8) {
                break;
            } else if (opcode == 0x9) {
                sendFrame((byte) 0xA, payload);
            }
        }
    }

    private synchronized void sendFrame(byte opcode, byte[] payload) throws Exception {
        if (out == null) return;
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x80 | opcode);
        int len = payload.length;
        if (len < 126) {
            frame.write(0x80 | len);
        } else if (len <= 65535) {
            frame.write(0x80 | 126);
            frame.write((len >> 8) & 0xFF);
            frame.write(len & 0xFF);
        } else {
            frame.write(0x80 | 127);
            long longLen = len & 0xffffffffL;
            for (int i = 7; i >= 0; i--) {
                frame.write((int) ((longLen >> (8 * i)) & 0xFF));
            }
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        frame.write(mask);
        byte[] masked = Arrays.copyOf(payload, payload.length);
        for (int i = 0; i < masked.length; i++) {
            masked[i] ^= mask[i % 4];
        }
        frame.write(masked);
        out.write(frame.toByteArray());
        out.flush();
    }

    private String readHttpHeaders(InputStream in) throws Exception {
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        byte[] end = new byte[]{'\r', '\n', '\r', '\n'};
        int matched = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new IllegalStateException("Unexpected EOF while reading handshake");
            headers.write(b);
            if (b == end[matched]) {
                matched++;
                if (matched == end.length) break;
            } else {
                matched = b == end[0] ? 1 : 0;
            }
            if (headers.size() > 8192) {
                throw new IllegalStateException("Websocket handshake too large");
            }
        }
        return headers.toString(StandardCharsets.US_ASCII.name());
    }

    private int readByte(InputStream in) throws Exception {
        int value = in.read();
        if (value < 0) throw new IllegalStateException("Unexpected EOF");
        return value;
    }

    private byte[] readBytes(InputStream in, int len) throws Exception {
        byte[] data = new byte[len];
        int offset = 0;
        while (offset < len) {
            int read = in.read(data, offset, len - offset);
            if (read < 0) throw new IllegalStateException("Unexpected EOF");
            offset += read;
        }
        return data;
    }
}
