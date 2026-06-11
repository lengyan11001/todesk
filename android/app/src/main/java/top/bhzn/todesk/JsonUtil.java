package top.bhzn.todesk;

import org.json.JSONException;
import org.json.JSONObject;

final class JsonUtil {
    private JsonUtil() {
    }

    static JSONObject object() {
        return new JSONObject();
    }

    static JSONObject put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException ignored) {
        }
        return object;
    }

    static String string(JSONObject object, String key) {
        return object.optString(key, "");
    }
}
