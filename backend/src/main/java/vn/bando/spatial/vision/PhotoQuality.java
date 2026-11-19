package vn.bando.spatial.vision;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Cổng ảnh kém dùng ngưỡng chốt từ phân bố ảnh học, không từ tập kiểm tra.
 * Ảnh học có cạnh ngắn 4284, độ xám trung bình khoảng 76–143 và phương sai trên 3000.
 */
public final class PhotoQuality {

    private PhotoQuality() {
    }

    public record Verdict(boolean poor, List<String> reasons) {
    }

    public static Verdict judge(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        List<String> reasons = new ArrayList<>();
        if (Math.min(width, height) < 64) {
            reasons.add("Cạnh ngắn hơn 64 điểm ảnh");
        }
        double sum = 0;
        double sumSquares = 0;
        int count = width * height;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double gray = BlockGray.gray(image.getRGB(x, y));
                sum += gray;
                sumSquares += gray * gray;
            }
        }
        double mean = sum / count;
        double variance = sumSquares / count - mean * mean;
        if (mean < 20) {
            reasons.add("Ảnh quá tối");
        } else if (mean > 240) {
            reasons.add("Ảnh quá sáng");
        }
        if (variance < 50) {
            reasons.add("Ảnh gần như một màu");
        }
        if (width >= 3 && height >= 3 && meanAbsoluteLaplacian(image) < 4) {
            reasons.add("Ảnh thiếu chi tiết mặt tiền");
        }
        return new Verdict(!reasons.isEmpty(), List.copyOf(reasons));
    }

    static double meanAbsoluteLaplacian(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        double sum = 0;
        int count = 0;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                double center = BlockGray.gray(image.getRGB(x, y));
                double lap = Math.abs(4 * center
                        - BlockGray.gray(image.getRGB(x - 1, y))
                        - BlockGray.gray(image.getRGB(x + 1, y))
                        - BlockGray.gray(image.getRGB(x, y - 1))
                        - BlockGray.gray(image.getRGB(x, y + 1)));
                sum += lap;
                count++;
            }
        }
        return sum / count;
    }
}
