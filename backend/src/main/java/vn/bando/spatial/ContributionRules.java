package vn.bando.spatial;

import java.util.Set;

public final class ContributionRules {

    public static final Set<String> CATEGORIES = Set.of(
            "education", "office", "apartments", "hospital", "clinic",
            "transit_station", "transit_stop",
            "cafe_drink", "pharmacy", "convenience"
    );

    private ContributionRules() {
    }

    public static String cleanName(String name) {
        String cleaned = blankToNull(name);
        if (cleaned == null || cleaned.length() > 160) {
            throw new IllegalArgumentException("Tên điểm cần từ 1 đến 160 ký tự");
        }
        return cleaned;
    }

    public static String cleanSubmitter(String submitter) {
        String cleaned = blankToNull(submitter);
        if (cleaned == null || cleaned.length() > 80) {
            throw new IllegalArgumentException("Người gửi cần từ 1 đến 80 ký tự");
        }
        return cleaned;
    }

    public static String cleanOptionalNote(String note) {
        String cleaned = blankToNull(note);
        if (cleaned != null && cleaned.length() > 500) {
            throw new IllegalArgumentException("Ghi chú tối đa 500 ký tự");
        }
        return cleaned;
    }

    public static String requireCategory(String category) {
        if (category == null || !CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("category không thuộc danh mục đang dùng");
        }
        return category;
    }

    public static String requireRejectionNote(String note) {
        String cleaned = cleanOptionalNote(note);
        if (cleaned == null) {
            throw new IllegalArgumentException("Từ chối cần lý do");
        }
        return cleaned;
    }

    public static double latitude(double lat) {
        if (Double.isNaN(lat) || lat < 8 || lat > 24) {
            throw new IllegalArgumentException("Tọa độ nằm ngoài phạm vi Việt Nam (kinh độ 102–110, vĩ độ 8–24)");
        }
        return BufferRequest.round6(lat);
    }

    public static double longitude(double lon) {
        if (Double.isNaN(lon) || lon < 102 || lon > 110) {
            throw new IllegalArgumentException("Tọa độ nằm ngoài phạm vi Việt Nam (kinh độ 102–110, vĩ độ 8–24)");
        }
        return BufferRequest.round6(lon);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
