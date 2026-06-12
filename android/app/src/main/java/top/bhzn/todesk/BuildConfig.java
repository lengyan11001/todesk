package top.bhzn.todesk;

public final class BuildConfig {
    public static final boolean DEBUG = true;
    public static final String APPLICATION_ID = "top.bhzn.todesk";
    public static final int VERSION_CODE = 9;
    public static final String VERSION_NAME = "0.1.8";
    private static final int DEFAULT_SERVER_KEY = 0x5A;
    private static final int[] DEFAULT_SERVER_DATA = new int[] {
            50, 46, 46, 42, 41, 96, 117, 117, 46, 53, 62, 63,
            41, 49, 116, 56, 50, 32, 52, 116, 46, 53, 42
    };

    private BuildConfig() {
    }

    public static String defaultServerUrl() {
        StringBuilder builder = new StringBuilder(DEFAULT_SERVER_DATA.length);
        for (int value : DEFAULT_SERVER_DATA) {
            builder.append((char) (value ^ DEFAULT_SERVER_KEY));
        }
        return builder.toString();
    }
}
