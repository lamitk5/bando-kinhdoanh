package vn.bando.spatial.vision;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class HttpGroqVisionClient implements VisionClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String model;

    HttpGroqVisionClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model == null ? "" : model.trim();
    }

    @Override
    public String assess(byte[] image, String contentType) {
        if (!model.matches("[A-Za-z0-9._/-]{1,80}")) {
            throw new IllegalStateException("Có GROQ_API_KEY nhưng GROQ_MODEL không hợp lệ");
        }
        ObjectNode imageUrl = mapper.createObjectNode();
        imageUrl.putObject("image_url").put(
                "url",
                "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(image));
        imageUrl.put("type", "image_url");
        ObjectNode text = mapper.createObjectNode();
        text.put("type", "text");
        text.put("text", HttpGeminiVisionClient.PROMPT);
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "user");
        message.putArray("content").add(text).add(imageUrl);
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0);
        body.putArray("messages").add(message);
        body.putObject("response_format").put("type", "json_object");
        JsonNode response = post(body);
        String content = response.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new IllegalStateException("Groq không trả nội dung văn bản");
        }
        return content;
    }

    private JsonNode post(ObjectNode body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Groq trả HTTP " + response.statusCode() + ": " + clip(response.body()));
            }
            return mapper.readTree(response.body());
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Không gọi được Groq");
        }
    }

    private String clip(String body) {
        String cleaned = body == null ? "" : body.replace(apiKey, "[redacted]").replaceAll("\\s+", " ").trim();
        return cleaned.length() <= 300 ? cleaned : cleaned.substring(0, 300);
    }
}
