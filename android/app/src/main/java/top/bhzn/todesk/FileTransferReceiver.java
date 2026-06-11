package top.bhzn.todesk;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
                    FileTransferStore.add(context, result.fileName, result.path, result.uri, result.bytes);
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

        try (InputStream in = connection.getInputStream()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return saveToMediaStore(context, in, fileName, size, sha256);
            }
            return saveToPublicDownloads(in, fileName, size, sha256);
        } finally {
            connection.disconnect();
        }
    }

    private static Result saveToMediaStore(Context context, InputStream in, String fileName, long size, String sha256) throws Exception {
        String clean = safeName(fileName);
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, clean);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BHZN-ToDesk");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IllegalStateException("create download item failed");
        }
        try {
            long total;
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("open download item failed");
                total = copyAndVerify(in, out, size, sha256);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return new Result(clean, "Downloads/BHZN-ToDesk/" + clean, uri.toString(), total);
        } catch (Exception e) {
            resolver.delete(uri, null, null);
            throw e;
        }
    }

    private static Result saveToPublicDownloads(InputStream in, String fileName, long size, String sha256) throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BHZN-ToDesk");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("create download dir failed");
        }
        File output = uniqueFile(dir, fileName);
        File temp = new File(output.getParentFile(), output.getName() + ".download");
        try {
            long total;
            try (FileOutputStream out = new FileOutputStream(temp)) {
                total = copyAndVerify(in, out, size, sha256);
                out.getFD().sync();
            }
            if (!temp.renameTo(output)) {
                throw new IllegalStateException("save file failed");
            }
            return new Result(output.getName(), output.getAbsolutePath(), Uri.fromFile(output).toString(), total);
        } catch (Exception e) {
            temp.delete();
            throw e;
        }
    }

    private static long copyAndVerify(InputStream in, OutputStream out, long size, String sha256) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
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
        if (total != size) {
            throw new IllegalStateException("file size mismatch");
        }
        String actual = hex(digest.digest());
        if (!actual.equalsIgnoreCase(sha256)) {
            throw new IllegalStateException("file sha256 mismatch");
        }
        return total;
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
        final String fileName;
        final String path;
        final String uri;
        final long bytes;

        Result(String fileName, String path, String uri, long bytes) {
            this.fileName = fileName;
            this.path = path;
            this.uri = uri;
            this.bytes = bytes;
        }
    }
}
