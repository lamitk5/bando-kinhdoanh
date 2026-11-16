package vn.bando.spatial;

import java.util.Locale;

public final class CacheKeys {

    private CacheKeys() {
    }

    public static String summary(long importId) {
        return "spatial:summary:v1:" + importId;
    }

    public static String buffer(long importId, String businessType, double lat, double lon) {
        return "spatial:buffer:v1:" + importId + ":" + businessType + ":"
                + String.format(Locale.US, "%.6f", lat) + ":"
                + String.format(Locale.US, "%.6f", lon);
    }
}
