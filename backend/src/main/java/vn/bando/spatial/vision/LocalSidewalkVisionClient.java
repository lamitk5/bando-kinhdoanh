package vn.bando.spatial.vision;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Locale;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Ước lượng bề rộng từ mô hình đã học. Ảnh kém không được gán bề rộng.
 * Tầm nhìn không có nhãn nên để null.
 */
public final class LocalSidewalkVisionClient implements VisionClient {

    private final SidewalkWidthModel model;
    private final ObjectMapper mapper;
    private final String featureKind;
    private final int gridWidth;
    private final int gridHeight;

    public LocalSidewalkVisionClient(
            SidewalkWidthModel model,
            ObjectMapper mapper,
            String featureKind,
            int gridWidth,
            int gridHeight) {
        this.model = model;
        this.mapper = mapper;
        this.featureKind = featureKind;
        this.gridWidth = gridWidth;
        this.gridHeight = gridHeight;
    }

    @Override
    public String assess(byte[] image, String contentType) {
        BufferedImage decoded = decode(image);
        PhotoQuality.Verdict quality = PhotoQuality.judge(decoded);
        ObjectNode node = mapper.createObjectNode();
        if (quality.poor()) {
            node.put("image_quality", "poor");
            node.putNull("sidewalk_capacity");
            node.putNull("visibility");
            node.set("quality_reasons", mapper.valueToTree(quality.reasons()));
            node.put("recommendation", "Ảnh không đủ để ước lượng bề rộng. Không gán mức vỉa hè hay tầm nhìn.");
            return node.toString();
        }
        double[] pixels = "band-edge-v3".equals(featureKind)
                ? SidewalkGeometry.features(decoded)
                : BlockGray.features(decoded, gridWidth, gridHeight);
        double meters = round2(model.predictMeters(pixels));
        String band = WidthBand.of(meters);
        node.put("image_quality", "ok");
        node.set("quality_reasons", mapper.createArrayNode());
        node.put("sidewalk_capacity", band);
        node.put("sidewalk_width_m", meters);
        node.putNull("visibility");
        node.put("visibility_measured", false);
        node.put("recommendation", recommendation(meters, band));
        return node.toString();
    }

    private static BufferedImage decode(byte[] image) {
        return ImageDecoder.decode(image);
    }

    private String recommendation(double meters, String band) {
        return "Ước lượng bề rộng %s m trên vùng vỉa hè đã cắt (±%s m là MAE tập kiểm tra từ 514 mẫu vỉa hè Seoul, chưa phải sai số đo tại Hà Nội). Không đo tầm nhìn. Mức %s là quy ước mét, không phải nhãn người chấm."
                .formatted(comma2(meters), comma2(model.testMaeMeters()), band);
    }

    private static String comma2(double value) {
        return String.format(Locale.US, "%.2f", value).replace('.', ',');
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
