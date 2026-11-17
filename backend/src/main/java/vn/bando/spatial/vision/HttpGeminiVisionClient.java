package vn.bando.spatial.vision;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class HttpGeminiVisionClient implements VisionClient {

    static final String PROMPT = """
            Bạn nhìn một ảnh mặt tiền cửa hàng. Chỉ trả về một JSON, không markdown, với đúng các khóa \
            image_quality (ok hoặc poor), quality_reasons (mảng chuỗi tiếng Việt), \
            sidewalk_capacity (none, narrow, enough, wide, hoặc null khi poor), \
            visibility (blocked, partial, clear, hoặc null khi poor), \
            recommendation (một câu tiếng Việt không quá 500 ký tự). \
            Ảnh mờ, tối, không phải mặt tiền, hoặc không đủ nhìn vỉa hè thì image_quality là poor \
            và sidewalk_capacity cùng visibility là null. Không ước lượng mét, tiền hay điểm số.""";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String model;

    HttpGeminiVisionClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model == null ? "" : model.trim();
    }

    @Override
    public String assess(byte[] image, String contentType) {
        if (!model.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IllegalStateException("Có GEMINI_API_KEY nhưng GEMINI_MODEL không hợp lệ");
        }
        ObjectNode body = mapper.createObjectNode();
        ArrayNode parts = body.putArray("contents").addObject().putArray("parts");
        parts.addObject().put("text", PROMPT);
        parts.addObject().putObject("inline_data")
                .put("mime_type", contentType)
                .put("data", Base64.getEncoder().encodeToString(image));
        body.putObject("generationConfig")
                .put("temperature", 0)
                .put("responseMimeType", "application/json");
        return text(post(
                "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent",
                body,
                "x-goog-api-key",
                apiKey));
    }

    private JsonNode post(String url, ObjectNode body, String keyHeader, String key) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header(keyHeader, key)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Gemini trả HTTP " + response.statusCode() + ": " + clip(response.body()));
            }
            return mapper.readTree(response.body());
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Không gọi được Gemini");
        }
    }

    private String text(JsonNode response) {
        String text = response.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
        if (text.isBlank()) {
            throw new IllegalStateException("Gemini không trả nội dung văn bản");
        }
        return text;
    }

    private String clip(String body) {
        String cleaned = body == null ? "" : body.replace(apiKey, "[redacted]").replaceAll("\\s+", " ").trim();
        return cleaned.length() <= 300 ? cleaned : cleaned.substring(0, 300);
    }
}
