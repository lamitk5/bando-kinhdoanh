package vn.bando.spatial.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VisionClientConfig {

    @Bean
    VisionClient visionClient(ObjectMapper mapper) {
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
            return new LocalSidewalkVisionClient(
                    model,
                    mapper,
                    node.path("feature_kind").asText(""),
                    resize.get(0).asInt(),
                    resize.get(1).asInt());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Không đọc được mô hình bề rộng", exception);
        }
    }
}
