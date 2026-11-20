package vn.bando.spatial.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class YoloCounterTest {

    private static final String COCO = "5d4a90cdc7a21786cc59cd19778e9eafff836df9e2da32524737c7ee6efe4fe5";
    private static final String WORLD = "9b2c17ab6124a913e9b3a5c170617920d91b0f01111a8479da69f00e2cf27792";
    private static final String SEGMENTER = "1ed65f56d8c76fc7e11988ed5b7b4302e1c78706ea86583a54c1c9e07133c1d5";
    private static final String DEPTH = "453e3d7ffaa5d89985ea6593e87af0091026e098d028ae7ab7f929780c3a2f85";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aNullPoleCountOrMismatchedCarMergeIsRejected() throws Exception {
        ObjectNode node = measured();
        node.withObject("requested").putNull("utility_pole");
        assertThrows(IllegalArgumentException.class,
                () -> YoloCounter.validate(node, COCO, WORLD, SEGMENTER, DEPTH));

        ObjectNode carMismatch = measured();
        carMismatch.withObject("counts").put("bus", 1);
        carMismatch.putArray("detections")
                .addObject()
                .put("class", "bus")
                .put("confidence", 0.6)
                .put("source", "yolov8m")
                .put("blocks_facade", false)
                .putArray("box_px").add(1).add(2).add(30).add(40);
        assertThrows(IllegalArgumentException.class,
                () -> YoloCounter.validate(carMismatch, COCO, WORLD, SEGMENTER, DEPTH));
        carMismatch.withObject("requested").put("car", 1);
        YoloCounter.validate(carMismatch, COCO, WORLD, SEGMENTER, DEPTH);
    }

    @Test
    void syntheticGradientCheckerboardNoiseAndTexturedGridAreRejectedBeforeCounting() {
        BufferedImage gradient = new BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 96; y++) {
            for (int x = 0; x < 96; x++) {
                gradient.setRGB(x, y, ((x * 2) << 16) | ((y * 2) << 8) | 80);
            }
        }
        PhotoQuality.Verdict gradientVerdict = PhotoQuality.judge(gradient);
        assertTrue(gradientVerdict.poor());
        assertTrue(gradientVerdict.reasons().contains("Ảnh thiếu chi tiết mặt tiền"));

        BufferedImage checkerboard = new BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 96; y++) {
            for (int x = 0; x < 96; x++) {
                int color = ((x / 8 + y / 8) % 2 == 0) ? 0xffffff : 0x000000;
                checkerboard.setRGB(x, y, color);
            }
        }
        PhotoQuality.Verdict checkerVerdict = PhotoQuality.judge(checkerboard);
        assertTrue(checkerVerdict.poor());
        assertTrue(checkerVerdict.reasons().contains("Ảnh có dải mức xám rời rạc nhân tạo, không phải ảnh chụp mặt tiền"));

        Random rng = new Random(42L);
        BufferedImage noise = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                noise.setRGB(x, y, rng.nextInt(0x1000000));
            }
        }
        PhotoQuality.Verdict noiseVerdict = PhotoQuality.judge(noise);
        assertTrue(noiseVerdict.poor());
        assertTrue(noiseVerdict.reasons().contains("Ảnh nhiễu điểm ảnh ngẫu nhiên, không phải ảnh chụp mặt tiền"));

        BufferedImage texturedGrid = new BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 120; y++) {
            for (int x = 0; x < 160; x++) {
                double base = 60.0 + 110.0 * (y / 120.0) + 25.0 * (x / 160.0);
                double cell = (((x / 6) % 2 == 0) ^ ((y / 6) % 2 == 0)) ? 25.0 : -25.0;
                int v = Math.min(255, Math.max(0, (int) Math.round(base + cell + rng.nextGaussian() * 5.0)));
                texturedGrid.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        PhotoQuality.Verdict gridVerdict = PhotoQuality.judge(texturedGrid);
        assertTrue(gridVerdict.poor());
        assertTrue(gridVerdict.reasons().contains("Ảnh hoa văn hoặc kết cấu tổng hợp đồng nhất, không phải ảnh chụp mặt tiền"));

        BufferedImage cartoon = new BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 120; y++) {
            for (int x = 0; x < 160; x++) {
                int color = y < 40 ? ((120 + y) << 16 | (160 + y / 2) << 8 | 230)
                        : (y < 90 ? 0xd2c3aa : 0x55555a);
                cartoon.setRGB(x, y, color);
            }
        }
        PhotoQuality.Verdict cartoonVerdict = PhotoQuality.judge(cartoon);
        assertTrue(cartoonVerdict.poor());
        assertTrue(cartoonVerdict.reasons().contains("Ảnh đồ họa phẳng hoặc hình vẽ nhân tạo, không phải ảnh chụp mặt tiền"));
    }

    @Test
    void sineWaveAndFlatCartoonAreRejectedWhileHanoiFixturesStayPhotographs() throws Exception {
        BufferedImage sine = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 240; y++) {
            for (int x = 0; x < 320; x++) {
                int v = (int) Math.round(128 + 110 * Math.sin(2 * Math.PI * x / 16.0));
                v = Math.max(0, Math.min(255, v));
                sine.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        PhotoQuality.Verdict sineVerdict = PhotoQuality.judge(sine);
        assertTrue(sineVerdict.poor(), sineVerdict.reasons().toString());

        BufferedImage flat = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 240; y++) {
            for (int x = 0; x < 320; x++) {
                int color = y < 80 ? 0x5aa0dc : (y < 170 ? 0xb4aa96 : 0x46464b);
                if (y > 100 && y < 150 && x > 40 && x < 260) {
                    color = 0xd23232;
                }
                flat.setRGB(x, y, color);
            }
        }
        PhotoQuality.Verdict flatVerdict = PhotoQuality.judge(flat);
        assertTrue(flatVerdict.poor(), flatVerdict.reasons().toString());
        assertTrue(flatVerdict.reasons().stream().anyMatch(reason -> reason.contains("quá ít màu")));

        for (String name : java.util.List.of(
                "xe-may-pho-ha-noi.jpg",
                "pho-ha-noi.jpg",
                "highlands-ha-noi.jpg",
                "dong-tac-dong-da.jpg",
                "cau-giay-ha-noi.jpg",
                "cot-dien.jpg",
                "bien-bao-che-mat-tien.jpg")) {
            BufferedImage photo = ImageIO.read(fixture(name).toFile());
            PhotoQuality.Verdict verdict = PhotoQuality.judge(photo);
            assertFalse(verdict.poor(), name + " " + verdict.reasons());
        }
    }

    @Test
    void pngAndWebpExifOrientationSixMatchTheJpegRotation() throws Exception {
        BufferedImage photo = ImageIO.read(fixture("highlands-ha-noi.jpg").toFile());
        byte[] png = pngWithOrientation6(photo);
        assertEquals(6, ImageDecoder.orientation(png));
        BufferedImage pngDecoded = ImageDecoder.decode(png);
        assertEquals(photo.getHeight(), pngDecoded.getWidth());
        assertEquals(photo.getWidth(), pngDecoded.getHeight());

        ByteArrayOutputStream webpOut = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(photo, "webp", webpOut));
        byte[] webp = webpWithExifOrientation6(webpOut.toByteArray());
        assertEquals(6, ImageDecoder.orientation(webp));
        BufferedImage webpDecoded = ImageDecoder.decode(webp);
        assertEquals(photo.getHeight(), webpDecoded.getWidth());
        assertEquals(photo.getWidth(), webpDecoded.getHeight());
    }

    @Test
    void streetStorefrontViaductAndPoleFixturesProduceMeasuredCountsAndSceneVerification() throws Exception {
        YoloCounter counter = new YoloCounter(mapper);
        try {
            JsonNode street = counter.count(Files.readAllBytes(fixture("xe-may-pho-ha-noi.jpg")), "image/jpeg");
            assertEquals(COCO, street.path("coco_sha256").asText());
            assertEquals(WORLD, street.path("weights_sha256").asText());
            assertEquals(SEGMENTER, street.path("segmenter_sha256").asText());
            assertEquals(DEPTH, street.path("depth_sha256").asText());
            assertTrue(street.path("requested").path("motorcycle").asInt() >= 20);
            assertTrue(street.path("requested").path("person").asInt() >= 10);
            assertTrue(street.path("counts").path("rider").asInt() >= 1);
            assertTrue(street.path("requested").path("tree").asInt() >= 1);
            assertEquals(0, street.path("requested").path("sign_blocking_visibility").asInt());
            assertFalse(street.path("scene_verified").asBoolean());

            JsonNode pole = counter.count(Files.readAllBytes(fixture("cot-dien.jpg")), "image/jpeg");
            assertEquals(1, pole.path("requested").path("utility_pole").asInt());
            assertTrue(pole.path("requested").path("tree").asInt() >= 2);
            assertTrue(pole.path("counts").path("traffic_sign").asInt() >= 2);
            assertEquals(0, pole.path("requested").path("sign_blocking_visibility").asInt());
            assertFalse(pole.path("scene_verified").asBoolean());

            JsonNode blockingSign = counter.count(Files.readAllBytes(fixture("bien-bao-che-mat-tien.jpg")), "image/jpeg");
            assertTrue(blockingSign.path("requested").path("sign_blocking_visibility").asInt() >= 1);

            JsonNode cauGiayViaduct = counter.count(Files.readAllBytes(fixture("cau-giay-ha-noi.jpg")), "image/jpeg");
            assertFalse(cauGiayViaduct.path("scene_verified").asBoolean());
            assertTrue(cauGiayViaduct.path("scene").path("ceiling_depth_drop").asDouble() >= 0.25);
            assertTrue(cauGiayViaduct.path("counts").path("rider").asInt() >= 3);

            JsonNode highlands = counter.count(Files.readAllBytes(fixture("highlands-ha-noi.jpg")), "image/jpeg");
            assertTrue(highlands.path("scene_verified").asBoolean());
            assertTrue(highlands.path("scene").path("sidewalk_ratio").asDouble() >= 0.03);
            assertEquals(3, highlands.path("requested").path("motorcycle").asInt());
            assertTrue(highlands.path("requested").path("car").asInt() >= 1);
            assertTrue(highlands.path("requested").path("tree").asInt() >= 1);
            String advisory = CountAdvisory.fromVision(highlands, 4.21);
            assertTrue(advisory.contains("4,21 m"));
            assertTrue(advisory.contains("±0,62 m"));
            assertTrue(advisory.contains("Không kết luận doanh thu"));

            BufferedImage photo = ImageIO.read(fixture("highlands-ha-noi.jpg").toFile());
            assertFalse(PhotoQuality.judge(photo).poor());

            java.io.ByteArrayOutputStream webpOut = new java.io.ByteArrayOutputStream();
            assertTrue(ImageIO.write(photo, "webp", webpOut));
            BufferedImage decodedWebp = ImageDecoder.decode(webpOut.toByteArray());
            assertEquals(photo.getWidth(), decodedWebp.getWidth());
            assertEquals(photo.getHeight(), decodedWebp.getHeight());

            byte[] exifRotated = withExifOrientation6(Files.readAllBytes(fixture("highlands-ha-noi.jpg")));
            assertEquals(6, ImageDecoder.jpegExifOrientation(exifRotated));
            BufferedImage rotatedDecoded = ImageDecoder.decode(exifRotated);
            assertEquals(photo.getHeight(), rotatedDecoded.getWidth());
            assertEquals(photo.getWidth(), rotatedDecoded.getHeight());
        } finally {
            counter.destroy();
        }
    }

    private ObjectNode measured() throws Exception {
        ObjectNode node = mapper.createObjectNode();
        node.put("weights_file", "yolov8m.pt");
        node.put("coco_sha256", COCO);
        node.put("weights_sha256", WORLD);
        node.put("segmenter_sha256", SEGMENTER);
        node.put("depth_sha256", DEPTH);
        node.put("confidence_threshold", 0.25);
        node.put("motorcycle_confidence_threshold", 0.15);
        node.put("four_wheel_confidence_threshold", 0.18);
        node.put("min_area_fraction", 0.0005);
        node.put("device", "cpu");
        node.put("inference_ms", 12.5);
        node.put("scene_verified", false);
        node.put("occlusion_rule", "Kiểm tra nền công trình và độ sâu đứng trước mặt tiền.");
        node.put("note", "Số đo mô hình.");
        ObjectNode scene = node.putObject("scene");
        scene.put("verified", false);
        scene.put("building_ratio", 0.02);
        scene.put("sidewalk_ratio", 0.01);
        scene.put("lower_pavement_ratio", 0.12);
        scene.put("lower_occlusion_ratio", 0.05);
        scene.put("summary", "Chưa đủ mặt tiền công trình.");
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
        YoloCounter.validate(node, COCO, WORLD, SEGMENTER, DEPTH);
        return node;
    }

    private static byte[] withExifOrientation6(byte[] jpeg) {
        byte[] app1 = new byte[] {
                (byte) 0xff, (byte) 0xe1, 0x00, 0x22,
                'E', 'x', 'i', 'f', 0x00, 0x00,
                'M', 'M', 0x00, 0x2a, 0x00, 0x00, 0x00, 0x08,
                0x00, 0x01,
                0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x06, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
        };
        byte[] out = new byte[2 + app1.length + (jpeg.length - 2)];
        out[0] = jpeg[0];
        out[1] = jpeg[1];
        System.arraycopy(app1, 0, out, 2, app1.length);
        System.arraycopy(jpeg, 2, out, 2 + app1.length, jpeg.length - 2);
        return out;
    }

    private static byte[] tiffOrientation6() {
        return new byte[] {
                'M', 'M', 0x00, 0x2a, 0x00, 0x00, 0x00, 0x08,
                0x00, 0x01,
                0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x06, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
        };
    }

    private static byte[] pngWithOrientation6(BufferedImage photo) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(photo, "png", raw));
        byte[] png = raw.toByteArray();
        byte[] tiff = tiffOrientation6();
        byte[] chunk = new byte[12 + tiff.length];
        chunk[0] = (byte) ((tiff.length >> 24) & 0xff);
        chunk[1] = (byte) ((tiff.length >> 16) & 0xff);
        chunk[2] = (byte) ((tiff.length >> 8) & 0xff);
        chunk[3] = (byte) (tiff.length & 0xff);
        chunk[4] = 'e';
        chunk[5] = 'X';
        chunk[6] = 'I';
        chunk[7] = 'f';
        System.arraycopy(tiff, 0, chunk, 8, tiff.length);
        CRC32 crc = new CRC32();
        crc.update(chunk, 4, 4 + tiff.length);
        long value = crc.getValue();
        chunk[8 + tiff.length] = (byte) ((value >> 24) & 0xff);
        chunk[9 + tiff.length] = (byte) ((value >> 16) & 0xff);
        chunk[10 + tiff.length] = (byte) ((value >> 8) & 0xff);
        chunk[11 + tiff.length] = (byte) (value & 0xff);
        int iend = png.length - 12;
        byte[] out = new byte[png.length + chunk.length];
        System.arraycopy(png, 0, out, 0, iend);
        System.arraycopy(chunk, 0, out, iend, chunk.length);
        System.arraycopy(png, iend, out, iend + chunk.length, 12);
        return out;
    }

    private static byte[] webpWithExifOrientation6(byte[] webp) {
        byte[] tiff = tiffOrientation6();
        byte[] payload = new byte[6 + tiff.length];
        payload[0] = 'E';
        payload[1] = 'x';
        payload[2] = 'i';
        payload[3] = 'f';
        payload[4] = 0;
        payload[5] = 0;
        System.arraycopy(tiff, 0, payload, 6, tiff.length);
        int padded = payload.length + (payload.length & 1);
        byte[] chunk = new byte[8 + padded];
        chunk[0] = 'E';
        chunk[1] = 'X';
        chunk[2] = 'I';
        chunk[3] = 'F';
        chunk[4] = (byte) (payload.length & 0xff);
        chunk[5] = (byte) ((payload.length >> 8) & 0xff);
        chunk[6] = (byte) ((payload.length >> 16) & 0xff);
        chunk[7] = (byte) ((payload.length >> 24) & 0xff);
        System.arraycopy(payload, 0, chunk, 8, payload.length);
        byte[] out = new byte[webp.length + chunk.length];
        System.arraycopy(webp, 0, out, 0, webp.length);
        System.arraycopy(chunk, 0, out, webp.length, chunk.length);
        int riffSize = out.length - 8;
        out[4] = (byte) (riffSize & 0xff);
        out[5] = (byte) ((riffSize >> 8) & 0xff);
        out[6] = (byte) ((riffSize >> 16) & 0xff);
        out[7] = (byte) ((riffSize >> 24) & 0xff);
        return out;
    }

    private static Path fixture(String name) {
        Path direct = Path.of("etl/vision/fixtures", name);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        return Path.of("..", "etl", "vision", "fixtures", name);
    }
}
