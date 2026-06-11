package top.bhzn.todesk;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class FileTransferStore {
    static final class Record {
        final String fileName;
        final String path;
        final String uri;
        final long bytes;
        final long receivedAt;

        Record(String fileName, String path, String uri, long bytes, long receivedAt) {
            this.fileName = fileName;
            this.path = path;
            this.uri = uri;
            this.bytes = bytes;
            this.receivedAt = receivedAt;
        }
    }

    private static final String PREF = "file_transfers";
    private static final String KEY_RECORDS = "records";

    private FileTransferStore() {
    }

    static synchronized void add(Context context, String fileName, String path, String uri, long bytes) {
        List<Record> records = list(context);
        records.add(0, new Record(fileName, path, uri, bytes, System.currentTimeMillis()));
        while (records.size() > 100) {
            records.remove(records.size() - 1);
        }
        save(context, records);
    }

    static synchronized List<Record> list(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String text = prefs.getString(KEY_RECORDS, "[]");
        List<Record> records = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(text);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                records.add(new Record(
                        item.optString("fileName", "file"),
                        item.optString("path", ""),
                        item.optString("uri", ""),
                        item.optLong("bytes", 0),
                        item.optLong("receivedAt", 0)
                ));
            }
        } catch (Exception ignored) {
        }
        return records;
    }

    static synchronized void clear(Context context) {
        save(context, new ArrayList<Record>());
    }

    private static void save(Context context, List<Record> records) {
        JSONArray array = new JSONArray();
        for (Record record : records) {
            JSONObject item = JsonUtil.object();
            JsonUtil.put(item, "fileName", record.fileName);
            JsonUtil.put(item, "path", record.path);
            JsonUtil.put(item, "uri", record.uri);
            JsonUtil.put(item, "bytes", record.bytes);
            JsonUtil.put(item, "receivedAt", record.receivedAt);
            array.put(item);
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_RECORDS, array.toString())
                .apply();
    }
}
