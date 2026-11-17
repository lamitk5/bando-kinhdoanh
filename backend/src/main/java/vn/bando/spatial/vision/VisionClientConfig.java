package vn.bando.spatial.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VisionClientConfig {

    @Bean
    VisionClient visionClient(ObjectMapper mapper) {
        String gemini = env("GEMINI_API_KEY");
        if (!gemini.isBlank()) {
            return new HttpGeminiVisionClient(gemini, env("GEMINI_MODEL"));
        }
        String groq = env("GROQ_API_KEY");
        if (!groq.isBlank()) {
            return new HttpGroqVisionClient(groq, env("GROQ_MODEL"));
        }
        try (var input = VisionClientConfig.class.getResourceAsStream("/vision/sidewalk_width_model.json")) {
            if (input == null) {
                return new UnconfiguredVisionClient();
            }
            JsonNode node = mapper.readTree(input);
            SidewalkWidthModel model = SidewalkWidthModel.read(node);
            if (!model.beatsBaseline()) {
                return new UnconfiguredVisionClient();
            }
            JsonNode resize = node.path("resize");
            return new LocalSidewalkVisionClient(model, mapper, resize.get(0).asInt(), resize.get(1).asInt());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Không đọc được mô hình bề rộng", exception);
        }
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }
}
