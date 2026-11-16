package vn.bando.spatial;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

public final class BufferRequest {

    public static final Set<String> BUSINESS_TYPES = Set.of("cafe_drink", "pharmacy", "convenience");

    private BufferRequest() {
    }

    public record Normalized(double lat, double lon, String businessType) {
    }

    public static Normalized normalize(double lat, double lon, String businessType) {
        if (businessType == null || !BUSINESS_TYPES.contains(businessType)) {
            throw new IllegalArgumentException(
                    "business_type phải là cafe_drink, pharmacy hoặc convenience");
        }
        if (Double.isNaN(lat) || Double.isNaN(lon)
                || lon < 102 || lon > 110
                || lat < 8 || lat > 24) {
            throw new IllegalArgumentException(
                    "Tọa độ nằm ngoài phạm vi Việt Nam (kinh độ 102–110, vĩ độ 8–24)");
        }
        return new Normalized(round6(lat), round6(lon), businessType);
    }

    static double round6(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }
}
