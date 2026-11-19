package vn.bando.spatial.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class YoloCounterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aFilledPoleCountIsRejected() throws Exception {
        ObjectNode node = measured();
        node.withObject("requested").put("utility_pole", 0);
        assertThrows(IllegalArgumentException.class, () -> YoloCounter.validate(node, node.path("weights_sha256").asText()));
    }

    @Test
    void gradientPhotoIsCountedByTheSavedWeights() throws Exception {
        YoloCounter counter = new YoloCounter(mapper);
        try {
            JsonNode count = counter.count(gradientPng(), "image/png");
            assertEquals("yolov8n.pt", count.path("weights_file").asText());
            assertEquals("f59b3d833e2ff32e194b5bb8e08d211dc7c5bdf144b90d2c8412c47ccfc83b36", count.path("weights_sha256").asText());
            assertEquals("cpu", count.path("device").asText());
            assertTrue(count.path("inference_ms").asDouble() > 0);
            assertFalse(count.path("scene_verified").asBoolean());
            assertTrue(count.path("requested").get("utility_pole").isNull());
            assertTrue(count.path("requested").get("tree").isNull());
            assertTrue(count.path("requested").get("sign_blocking_visibility").isNull());
            assertEquals(count.path("counts").path("motorcycle").asInt(), count.path("requested").path("motorcycle").asInt());
            assertTrue(count.path("note").asText().contains("Không phải khảo sát"));
        } finally {
            counter.destroy();
        }
    }

    private ObjectNode measured() throws Exception {
        ObjectNode node = mapper.createObjectNode();
        node.put("weights_file", "yolov8n.pt");
        node.put("weights_sha256", "f59b3d833e2ff32e194b5bb8e08d211dc7c5bdf144b90d2c8412c47ccfc83b36");
        node.put("dataset", "COCO");
        node.put("confidence_threshold", 0.25);
        node.put("device", "cpu");
        node.put("inference_ms", 12.5);
        node.put("scene_verified", false);
        node.put("note", "Số đếm là đầu ra YOLOv8n trên ảnh này. Không phải khảo sát hiện trường.");
        ObjectNode counts = node.putObject("counts");
        for (String key : YoloCounter.COUNTS) {
            counts.put(key, 0);
        }
        node.putArray("detections");
        ObjectNode requested = node.putObject("requested");
        requested.put("person", 0);
        requested.put("car", 0);
        requested.put("motorcycle", 0);
        requested.putNull("utility_pole");
        requested.putNull("tree");
        requested.putNull("sign_blocking_visibility");
        var missing = node.putArray("not_measured");
        missing.addObject().put("field", "utility_pole").put("reason", "Không có lớp.");
        missing.addObject().put("field", "tree").put("reason", "Không có lớp.");
        missing.addObject().put("field", "sign_blocking_visibility").put("reason", "Không đo được che khuất.");
        YoloCounter.validate(node, node.path("weights_sha256").asText());
        return node;
    }

    private static byte[] gradientPng() throws Exception {
        BufferedImage image = new BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 96; y++) {
            for (int x = 0; x < 96; x++) {
                image.setRGB(x, y, ((x * 2) << 16) | ((y * 2) << 8) | 80);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
