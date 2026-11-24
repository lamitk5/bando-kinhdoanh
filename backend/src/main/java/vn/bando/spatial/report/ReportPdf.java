package vn.bando.spatial.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import com.fasterxml.jackson.databind.JsonNode;

public final class ReportPdf {

    private ReportPdf() {
    }

    public static byte[] write(JsonNode compared) {
        try (PDDocument document = new PDDocument();
                var fontStream = ReportPdf.class.getResourceAsStream("/fonts/DejaVuSans.ttf")) {
            if (fontStream == null) {
                throw new IllegalStateException("Thiếu font để ghi tiếng Việt");
            }
            PDType0Font font = PDType0Font.load(document, fontStream, true);
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                float y = 800;
                y = line(stream, font, 16, 48, y, "So sánh mặt bằng");
                y = line(stream, font, 11, 48, y, "Ngành: " + compared.path("business_type").asText()
                        + ". Tiền thuê tháng: " + compared.path("rent_vnd_per_month").asLong() + " đồng.");
                y = line(stream, font, 10, 48, y, "Ba điểm thành phần dùng cùng mức thuê. Chỉ số thuê không phải lợi nhuận.");
                y -= 8;
                for (JsonNode site : compared.path("sites")) {
                    y = line(stream, font, 13, 48, y, site.path("label").asText());
                    y = line(stream, font, 11, 48, y, "Tọa độ " + site.path("lat").asDouble() + ", " + site.path("lon").asDouble());
                    y = line(stream, font, 11, 48, y, "Điểm không gian: " + site.path("spatial_score").asDouble());
                    JsonNode components = site.path("components");
                    y = line(stream, font, 11, 48, y, "Điểm hút khách: " + components.path("attractor_density").path("score").asDouble()
                            + " (số đo " + components.path("attractor_density").path("raw").asDouble() + ")");
                    y = line(stream, font, 11, 48, y, "Bão hòa đối thủ: " + components.path("competitor_saturation").path("score").asDouble()
                            + " (số đo " + components.path("competitor_saturation").path("raw").asDouble() + ")");
                    y = line(stream, font, 11, 48, y, "Tiếp cận trục đường: " + components.path("arterial_access").path("score").asDouble()
                            + " (số đo " + components.path("arterial_access").path("raw").asDouble() + " m)");
                    y = line(stream, font, 11, 48, y, "Điểm trên mỗi triệu đồng thuê: " + site.path("rent_index_points_per_million_vnd").asDouble());
                    if (site.path("partial_ring").asBoolean()) {
                        y = line(stream, font, 10, 48, y, "Vòng 500 m không nằm trọn trong bốn quận.");
                    }
                    y -= 10;
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Không ghi được PDF", exception);
        }
    }

    private static float line(PDPageContentStream stream, PDType0Font font, float size, float x, float y, String text)
            throws IOException {
        stream.beginText();
        stream.setFont(font, size);
        stream.newLineAtOffset(x, y);
        stream.showText(text);
        stream.endText();
        return y - (size + 6);
    }
}
