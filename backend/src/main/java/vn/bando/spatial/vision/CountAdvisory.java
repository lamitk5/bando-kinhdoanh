package vn.bando.spatial.vision;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

/** Câu tư vấn chỉ ghép các số đã đo. Không kết luận doanh thu. */
public final class CountAdvisory {

    private CountAdvisory() {
    }

    public static String fromCounts(JsonNode requested) {
        return "Đếm trên ảnh: %s xe máy, %s ô tô, %s người, %s cột điện, %s cây. Biển báo cắt vùng giữa: %s. Đây là đầu ra mô hình, không phải khảo sát. Không kết luận doanh thu."
                .formatted(
                        integer(requested, "motorcycle"),
                        integer(requested, "car"),
                        integer(requested, "person"),
                        integer(requested, "utility_pole"),
                        integer(requested, "tree"),
                        integer(requested, "sign_blocking_visibility"));
    }

    public static String withPlace(String counts, JsonNode components, double spatial) {
        return counts + " Điểm hút khách %s, bão hòa đối thủ %s, tiếp cận đường %s, điểm không gian %s. Tiền thuê là mốc người gọi."
                .formatted(
                        score(components, "attractor_density"),
                        score(components, "competitor_saturation"),
                        score(components, "arterial_access"),
                        comma(spatial));
    }

    private static String integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new IllegalArgumentException("Thiếu số đếm " + field);
        }
        return Integer.toString(value.asInt());
    }

    private static String score(JsonNode components, String field) {
        return comma(components.path(field).path("score").asDouble());
    }

    private static String comma(double value) {
        return String.format(Locale.US, "%.1f", value).replace('.', ',');
    }
}
