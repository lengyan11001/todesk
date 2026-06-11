package top.bhzn.todesk;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;

final class FileTransferReceiver {
    interface Callback {
        void onStatus(String status, String path, long bytes, String error);
    }

    private FileTransferReceiver() {
    }

    static void receive(final Context context, final String transferId, final String fileName, final long size,
            final String sha256, final String url, final Callback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                callback.onStatus("downloading", "", 0, "");
                try {
                    Result result = receiveInner(context, fileName, size, sha256, url);
                    callback.onStatus("saved", result.path, result.bytes, "");
                } catch (Exception e) {
                    callback.onStatus("failed", "", 0, e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        }, "bhzn-file-transfer-" + transferId).start();
    }

    private static Result receiveInner(Context context, String fileName, long size, String sha256, String urlText) throws Exception {
        Uri uri = Uri.parse(urlText);
        String scheme = String.valueOf(uri.getScheme()).toLowerCase(Locale.US);
        String host = String.valueOf(uri.getHost()).toLowerCase(Locale.US);
        boolean localHttp = "http".equals(scheme) && ("localhost".equals(host) || host.startsWith("127.") || "::1".equals(host));
        if (!"https".equals(scheme) && !localHttp) {
            throw new IllegalArgumentException("file transfer requires https url");
        }
        if (size <= 0 || size > 100L * 1024L * 1024L) {
            throw new IllegalArgumentException("bad file size");
        }
        if (sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("bad sha256");
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(false);
        if (connection instanceof HttpsURLConnection) {
            ((HttpsURLConnection) connection).setHostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier());
        }
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("download http " + code);
        }

        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            dir = new File(context.getFilesDir(), "downloads");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("create download dir failed");
        }
        File output = uniqueFile(dir, fileName);
        File temp = new File(output.getParentFile(), output.getName() + ".download");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(temp)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > size) {
                    throw new IllegalStateException("file larger than expected");
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
            out.getFD().sync();
        }
        if (total != size) {
            temp.delete();
            throw new IllegalStateException("file size mismatch");
        }
        String actual = hex(digest.digest());
        if (!actual.equalsIgnoreCase(sha256)) {
            temp.delete();
            throw new IllegalStateException("file sha256 mismatch");
        }
        if (!temp.renameTo(output)) {
            temp.delete();
            throw new IllegalStateException("save file failed");
        }
        return new Result(output.getAbsolutePath(), total);
    }

    private static File uniqueFile(File dir, String fileName) {
        String clean = safeName(fileName);
        File file = new File(dir, clean);
        if (!file.exists()) return file;
        String stem = clean;
        String ext = "";
        int dot = clean.lastIndexOf('.');
        if (dot > 0) {
            stem = clean.substring(0, dot);
            ext = clean.substring(dot);
        }
        for (int i = 1; i < 1000; i++) {
            File candidate = new File(dir, stem + " (" + i + ")" + ext);
            if (!candidate.exists()) return candidate;
        }
        return new File(dir, System.currentTimeMillis() + "-" + clean);
    }

    private static String safeName(String value) {
        String name = value == null ? "" : value.trim().replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
        if (name.length() > 160) name = name.substring(0, 160);
        return name.length() == 0 ? "file.bin" : name;
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return builder.toString();
    }

    private static final class Result {
        final String path;
        final long bytes;

        Result(String path, long bytes) {
            this.path = path;
            this.bytes = bytes;
        }
    }
}
