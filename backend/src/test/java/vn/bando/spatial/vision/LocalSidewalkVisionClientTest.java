package vn.bando.spatial.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class LocalSidewalkVisionClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void configUsesTheLocalModelWhenNoKeyIsSet() throws Exception {
        VisionClient client = new VisionClientConfig().visionClient(mapper);
        assertTrue(client instanceof LocalSidewalkVisionClient, client.getClass().getName());
    }

    @Test
    void blockAverageMatchesTheTrainingFormula() {
        BufferedImage image = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x0a141e);
        image.setRGB(1, 0, 0x28323c);
        image.setRGB(0, 1, 0x46505a);
        image.setRGB(1, 1, 0x646e78);
        image.setRGB(2, 0, 0xff0000);
        image.setRGB(3, 0, 0x00ff00);
        image.setRGB(2, 1, 0x0000ff);
        image.setRGB(3, 1, 0xffffff);
        double[] features = BlockGray.features(image, 2, 1);
        assertEquals(0.24764705882352941, features[0], 1e-12);
        assertEquals(0.5, features[1], 1e-12);
    }

    @Test
    void aTinyFlatPhotoIsPoorAndCarriesNoWidth() throws Exception {
        LocalSidewalkVisionClient client = client();
        VisionAssessmentParser.Assessment assessment = VisionAssessmentParser.parse(client.assess(png(1, 1, false), "image/png"));
        assertFalse(assessment.assessed());
        assertEquals(null, assessment.sidewalkCapacity());
        assertTrue(assessment.qualityReasons().contains("Cạnh ngắn hơn 64 điểm ảnh"));
    }

    @Test
    void aVariedPhotoReturnsMetersWithoutVisibility() throws Exception {
        LocalSidewalkVisionClient client = client();
        VisionAssessmentParser.Assessment assessment = VisionAssessmentParser.parse(client.assess(png(64, 64, true), "image/png"));
        assertFalse(assessment.assessed());
        assertEquals(WidthBand.of(assessment.widthMeters()), assessment.sidewalkCapacity());
        assertEquals(null, assessment.visibility());
        assertTrue(assessment.recommendation().contains("Seoul"));
        assertTrue(assessment.recommendation().contains("Không đo tầm nhìn"));
    }

    private LocalSidewalkVisionClient client() throws Exception {
        var node = mapper.readTree(SidewalkWidthModel.class.getResourceAsStream("/vision/sidewalk_width_model.json"));
        return new LocalSidewalkVisionClient(
                SidewalkWidthModel.read(node),
                mapper,
                node.path("resize").get(0).asInt(),
                node.path("resize").get(1).asInt());
    }

    private static byte[] png(int width, int height, boolean varied) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int color = varied ? (x * 4 + y * 9) & 0xff : 0x808080;
                image.setRGB(x, y, (color << 16) | (color << 8) | color);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
