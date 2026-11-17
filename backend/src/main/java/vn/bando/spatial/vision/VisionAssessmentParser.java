package vn.bando.spatial.vision;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class VisionAssessmentParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Integer> SIDEWALK = Map.of(
            "none", 0,
            "narrow", 40,
            "enough", 70,
            "wide", 100);
    private static final Map<String, Integer> VISIBILITY = Map.of(
            "blocked", 0,
            "partial", 50,
            "clear", 100);

    private VisionAssessmentParser() {
    }

    public record Assessment(
            boolean assessed,
            List<String> qualityReasons,
            String sidewalkCapacity,
            String visibility,
            String recommendation,
            Integer sidewalkScore,
            Integer visibilityScore) {
    }

    public static Assessment parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Mô hình không trả JSON");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(stripFence(raw));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("Mô hình không trả JSON hợp lệ");
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Mô hình không trả JSON hợp lệ");
        }
        String quality = requiredText(node, "image_quality");
        List<String> reasons = reasons(node);
        String recommendation = requiredText(node, "recommendation");
        if (recommendation.length() > 500) {
            throw new IllegalArgumentException("recommendation dài hơn 500 ký tự");
        }
        if ("poor".equals(quality)) {
            if (present(node, "sidewalk_capacity") || present(node, "visibility")) {
                throw new IllegalArgumentException("Ảnh poor không được kèm nhận định vỉa hè hoặc tầm nhìn");
            }
            return new Assessment(false, reasons, null, null, recommendation, null, null);
        }
        if (!"ok".equals(quality)) {
            throw new IllegalArgumentException("image_quality phải là ok hoặc poor");
        }
        String sidewalk = requiredText(node, "sidewalk_capacity");
        String visibility = requiredText(node, "visibility");
        if (!SIDEWALK.containsKey(sidewalk) || !VISIBILITY.containsKey(visibility)) {
            throw new IllegalArgumentException("sidewalk_capacity hoặc visibility không thuộc thang đã quy ước");
        }
        return new Assessment(
                true,
                reasons,
                sidewalk,
                visibility,
                recommendation,
                SIDEWALK.get(sidewalk),
                VISIBILITY.get(visibility));
    }

    private static List<String> reasons(JsonNode node) {
        JsonNode value = node.get("quality_reasons");
        if (value == null || !value.isArray() || value.size() > 8) {
            throw new IllegalArgumentException("quality_reasons phải là mảng không quá 8 phần tử");
        }
        List<String> reasons = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > 160) {
                throw new IllegalArgumentException("quality_reasons chứa chuỗi không hợp lệ");
            }
            reasons.add(item.asText());
        }
        return List.copyOf(reasons);
    }

    private static boolean present(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull();
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Thiếu " + field);
        }
        return value.asText();
    }

    private static String stripFence(String raw) {
        String text = raw.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstBreak = text.indexOf('\n');
        int closing = text.lastIndexOf("```");
        if (firstBreak < 0 || closing <= firstBreak) {
            throw new IllegalArgumentException("Mô hình không trả JSON hợp lệ");
        }
        return text.substring(firstBreak + 1, closing).trim();
    }
}
