package vn.bando.spatial.vision;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VisionClientConfig {

    @Bean
    VisionClient visionClient() {
        String gemini = env("GEMINI_API_KEY");
        if (!gemini.isBlank()) {
            return new HttpGeminiVisionClient(gemini, env("GEMINI_MODEL"));
        }
        String groq = env("GROQ_API_KEY");
        if (!groq.isBlank()) {
            return new HttpGroqVisionClient(groq, env("GROQ_MODEL"));
        }
        return new UnconfiguredVisionClient();
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }
}
