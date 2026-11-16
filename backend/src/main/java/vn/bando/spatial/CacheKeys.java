package vn.bando.spatial;

public final class CacheKeys {

    private CacheKeys() {
    }

    public static String summary(long importId) {
        return "spatial:summary:v1:" + importId;
    }
}
