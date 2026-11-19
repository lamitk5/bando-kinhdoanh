package vn.bando.spatial.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class YoloCounterTest {

    private static final String WORLD = "9b2c17ab6124a913e9b3a5c170617920d91b0f01111a8479da69f00e2cf27792";
    private static final String SEGMENTER = "1ed65f56d8c76fc7e11988ed5b7b4302e1c78706ea86583a54c1c9e07133c1d5";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aNullPoleCountIsRejected() throws Exception {
        ObjectNode node = measured();
        node.withObject("requested").putNull("utility_pole");
        assertThrows(IllegalArgumentException.class, () -> YoloCounter.validate(node, WORLD, SEGMENTER));
    }

    @Test
    void aSmoothGradientIsNotAStorefront() {
        BufferedImage image = new BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 96; y++) {
            for (int x = 0; x < 96; x++) {
                image.setRGB(x, y, ((x * 2) << 16) | ((y * 2) << 8) | 80);
            }
        }
        PhotoQuality.Verdict verdict = PhotoQuality.judge(image);
        assertTrue(verdict.poor());
        assertTrue(verdict.reasons().contains("Ảnh thiếu chi tiết mặt tiền"));
    }

    @Test
    void streetFixturesProduceMeasuredCounts() throws Exception {
        YoloCounter counter = new YoloCounter(mapper);
        try {
            JsonNode street = counter.count(Files.readAllBytes(fixture("xe-may-pho-ha-noi.jpg")), "image/jpeg");
            assertEquals(WORLD, street.path("weights_sha256").asText());
            assertEquals(SEGMENTER, street.path("segmenter_sha256").asText());
            assertTrue(street.path("requested").path("motorcycle").asInt() > 0);
            assertTrue(street.path("requested").path("person").asInt() > 0);
            assertTrue(street.path("requested").path("tree").asInt() > 0);
            assertTrue(street.path("requested").path("utility_pole").isIntegralNumber());
            assertTrue(street.path("requested").path("sign_blocking_visibility").isIntegralNumber());
            assertFalse(street.path("scene_verified").asBoolean());
            String sentence = CountAdvisory.fromCounts(street.path("requested"));
            assertTrue(sentence.contains(street.path("requested").path("motorcycle").asInt() + " xe máy"));
            assertTrue(sentence.contains("Không kết luận doanh thu"));

            JsonNode pole = counter.count(Files.readAllBytes(fixture("cot-dien.jpg")), "image/jpeg");
            assertTrue(pole.path("requested").path("utility_pole").asInt() > 0);
            assertTrue(pole.path("counts").path("traffic_sign").asInt() > 0);
            BufferedImage photo = ImageIO.read(fixture("xe-may-pho-ha-noi.jpg").toFile());
            assertFalse(PhotoQuality.judge(photo).poor());
        } finally {
            counter.destroy();
        }
    }

    private ObjectNode measured() throws Exception {
        ObjectNode node = mapper.createObjectNode();
        node.put("weights_file", "yolov8s-worldv2.pt");
        node.put("weights_sha256", WORLD);
        node.put("segmenter_sha256", SEGMENTER);
        node.put("confidence_threshold", 0.25);
        node.put("min_area_fraction", 0.0005);
        node.put("device", "cpu");
        node.put("inference_ms", 12.5);
        node.put("scene_verified", false);
        node.put("center_rule", "Giao với hình chữ nhật giữa ảnh");
        node.put("note", "Số đo mô hình.");
        ObjectNode counts = node.putObject("counts");
        for (String key : YoloCounter.COUNTS) {
            counts.put(key, 0);
        }
        node.putArray("detections");
        ObjectNode requested = node.putObject("requested");
        requested.put("person", 0);
        requested.put("car", 0);
        requested.put("motorcycle", 0);
        requested.put("utility_pole", 0);
        requested.put("tree", 0);
        requested.put("sign_blocking_visibility", 0);
        YoloCounter.validate(node, WORLD, SEGMENTER);
        return node;
    }

    private static Path fixture(String name) {
        Path direct = Path.of("etl/vision/fixtures", name);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        return Path.of("..", "etl", "vision", "fixtures", name);
    }
}
