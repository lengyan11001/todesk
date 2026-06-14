package top.bhzn.todesk;

import java.util.Locale;
import org.json.JSONObject;

final class QualityProfile {
    final String profile;
    final int maxSide;
    final int fps;
    final int jpegQuality;
    final int bitrateKbps;

    private QualityProfile(String profile, int maxSide, int fps, int jpegQuality, int bitrateKbps) {
        this.profile = profile;
        this.maxSide = maxSide;
        this.fps = fps;
        this.jpegQuality = jpegQuality;
        this.bitrateKbps = bitrateKbps;
    }

    static QualityProfile relay() {
        return new QualityProfile("relay", 1280, 10, 42, 1800);
    }

    static QualityProfile balanced() {
        return new QualityProfile("balanced", 1600, 18, 56, 3200);
    }

    static QualityProfile fromJson(JSONObject value, QualityProfile fallback) {
        QualityProfile base = fallback == null ? balanced() : fallback;
        if (value == null) return base;
        String profile = sanitizeProfile(value.optString("profile", base.profile), base.profile);
        int maxSide = value.has("maxSide") ? value.optInt("maxSide", base.maxSide) : base.maxSide;
        int fps = value.has("fps") ? value.optInt("fps", base.fps) : base.fps;
        int jpegQuality = value.has("jpegQuality") ? value.optInt("jpegQuality", base.jpegQuality) : base.jpegQuality;
        int bitrateKbps = value.has("bitrateKbps") ? value.optInt("bitrateKbps", base.bitrateKbps) : base.bitrateKbps;
        return new QualityProfile(
                profile,
                clamp(maxSide, 480, 2560),
                clamp(fps, 3, 30),
                clamp(jpegQuality, 30, 85),
                clamp(bitrateKbps, 300, 20_000)
        );
    }

    long frameIntervalMs() {
        return clamp(Math.round(1000f / Math.max(1, fps)), 16, 333);
    }

    private static String sanitizeProfile(String value, String fallback) {
        String profile = value == null ? "" : value.trim().toLowerCase(Locale.US);
        if ("hd".equals(profile) || "balanced".equals(profile) || "data".equals(profile)
                || "lan".equals(profile) || "relay".equals(profile)) {
            return profile;
        }
        return fallback == null ? "balanced" : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
