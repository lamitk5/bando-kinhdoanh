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
    void geometryFeaturesMatchTheTrainingScript() {
        BufferedImage image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 8; x++) {
                int red = (x * 20 + y * 3) % 256;
                int green = (x * 5 + 40) % 256;
                int blue = (y * 30 + 10) % 256;
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        double[] expected = {
                0.218911764706, 0.080105220236, 0.127444596443, 0.220131326949, 0.311308123249, 0.878918650794,
                0.230198039216, 0.080501778274, 0.138730870953, 0.231417601459, 0.322594397759, 0.668286841665,
                0.241484313725, 0.080501778274, 0.150017145463, 0.242703875969, 0.333880672269, 0.531735225729,
                0.252770588235, 0.080105220236, 0.161303419973, 0.253990150479, 0.345166946779, 0.469263802984,
                0.269700000000, 0.080105220236, 0.178232831737, 0.270919562244, 0.362096358543, 0.542209795145,
                0.280986274510, 0.080501778274, 0.189519106247, 0.282205836753, 0.373382633053, 0.586451899163,
                0.292272549020, 0.080501778274, 0.200805380757, 0.293492111263, 0.384668907563, 0.628371550781,
                0.303558823529, 0.080105220236, 0.212091655267, 0.304778385773, 0.395955182073, 0.667968750000,
                0.499999991988, 0.761904761905, 0.119047619048, 0.499999991988, 0.761904761905, 0.119047619048,
                0.499999991988, 0.761904761905, 0.119047619048, 0.499999991988, 0.761904761905, 0.119047619048,
                0.499999991988, 0.761904761905, 0.119047619048, 0.499999991988, 0.761904761905, 0.119047619048,
                0.499999991988, 0.761904761905, 0.119047619048, 0.499999991988, 0.761904761905, 0.119047619048,
                0.499999991988, 0.761904761905, 0.119047619048, 0.499999991988, 0.761904761905, 0.119047619048,
                0.499999991988, 0.761904761905, 0.119047619048, 0.499999991988, 0.761904761905, 0.119047619048,
                0.499999991988, 0.761904761905, 0.119047619048, 0.499999991988, 0.761904761905, 0.119047619048,
                0.499999991988, 0.761904761905, 0.119047619048, 0.499999991988, 0.761904761905, 0.119047619048
        };
        double[] features = SidewalkGeometry.features(image);
        assertEquals(expected.length, features.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], features[i], 1e-9, "feature " + i);
        }
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
        java.nio.file.Path photo = java.nio.file.Path.of("etl/vision/fixtures/highlands-ha-noi.jpg");
        if (!java.nio.file.Files.isRegularFile(photo)) {
            photo = java.nio.file.Path.of("..", "etl", "vision", "fixtures", "highlands-ha-noi.jpg");
        }
        VisionAssessmentParser.Assessment assessment = VisionAssessmentParser.parse(
                client.assess(java.nio.file.Files.readAllBytes(photo), "image/jpeg"));
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
                node.path("feature_kind").asText(""),
                node.path("resize").get(0).asInt(),
                node.path("resize").get(1).asInt());
    }

    private static byte[] png(int width, int height, boolean varied) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int gray = varied ? (x * 4 + y * 9) & 0xff : 0x80;
                int red = varied ? (gray + (x % 17) * 13) & 0xff : gray;
                int blue = varied ? (gray + (y % 13) * 17) & 0xff : gray;
                image.setRGB(x, y, (red << 16) | (gray << 8) | blue);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
