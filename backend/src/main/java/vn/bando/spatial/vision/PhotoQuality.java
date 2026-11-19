package vn.bando.spatial.vision;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Cổng chất lượng và tính tự nhiên của ảnh trước khi đưa vào mô hình đếm và ước lượng vỉa hè.
 * Chặn ảnh quá nhỏ, quá tối/sáng, một màu, chuyển sắc mượt, dải xám rời rạc, nhiễu ngẫu nhiên và hoa văn tổng hợp đồng nhất.
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
        double[][] grayGrid = new double[height][width];
        double sum = 0;
        double sumSquares = 0;
        int count = width * height;
        int[] bins = new int[32];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double gray = BlockGray.gray(image.getRGB(x, y));
                grayGrid[y][x] = gray;
                sum += gray;
                sumSquares += gray * gray;
                int bin = Math.min(31, Math.max(0, ((int) gray) / 8));
                bins[bin]++;
            }
        }
        double mean = sum / count;
        double variance = Math.max(0.0, sumSquares / count - mean * mean);
        double std = Math.sqrt(variance);
        if (mean < 20) {
            reasons.add("Ảnh quá tối");
        } else if (mean > 240) {
            reasons.add("Ảnh quá sáng");
        }
        if (variance < 50) {
            reasons.add("Ảnh gần như một màu");
        }
        if (width >= 3 && height >= 3 && meanAbsoluteLaplacian(grayGrid, width, height) < 4) {
            reasons.add("Ảnh thiếu chi tiết mặt tiền");
        }
        if (count >= 64 && occupiedBins(bins, count) < 8) {
            reasons.add("Ảnh có dải mức xám rời rạc nhân tạo, không phải ảnh chụp mặt tiền");
        }
        if (width >= 16 && height >= 16 && std >= 1.0 && neighborDiffOverStd(grayGrid, width, height, std) > 0.65) {
            reasons.add("Ảnh nhiễu điểm ảnh ngẫu nhiên, không phải ảnh chụp mặt tiền");
        }
        if (width >= 32 && height >= 32 && std >= 1.0 && blockStdCoefficientOfVariation(grayGrid, width, height) < 0.08) {
            reasons.add("Ảnh hoa văn hoặc kết cấu tổng hợp đồng nhất, không phải ảnh chụp mặt tiền");
        }
        if (width >= 32 && height >= 32 && std >= 1.0 && flatPixelFraction(grayGrid, width, height) > 0.50) {
            reasons.add("Ảnh đồ họa phẳng hoặc hình vẽ nhân tạo, không phải ảnh chụp mặt tiền");
        }
        return new Verdict(!reasons.isEmpty(), List.copyOf(reasons));
    }

    static double flatPixelFraction(double[][] gray, int width, int height) {
        long flat = 0;
        long total = (long) (width - 1) * (height - 1);
        if (total <= 0) {
            return 0.0;
        }
        for (int y = 0; y < height - 1; y++) {
            for (int x = 0; x < width - 1; x++) {
                int c = (int) gray[y][x];
                if (Math.abs(c - (int) gray[y][x + 1]) == 0 && Math.abs(c - (int) gray[y + 1][x]) == 0) {
                    flat++;
                }
            }
        }
        return (double) flat / total;
    }

    static int occupiedBins(int[] bins, int totalPixels) {
        double threshold = 0.005 * totalPixels;
        int occupied = 0;
        for (int binCount : bins) {
            if (binCount >= threshold) {
                occupied++;
            }
        }
        return occupied;
    }

    static double neighborDiffOverStd(double[][] gray, int width, int height, double std) {
        double dxSum = 0;
        long dxCount = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width - 1; x++) {
                dxSum += Math.abs(gray[y][x + 1] - gray[y][x]);
                dxCount++;
            }
        }
        double dySum = 0;
        long dyCount = 0;
        for (int y = 0; y < height - 1; y++) {
            for (int x = 0; x < width; x++) {
                dySum += Math.abs(gray[y + 1][x] - gray[y][x]);
                dyCount++;
            }
        }
        double meanDiff = ((dxSum / Math.max(1, dxCount)) + (dySum / Math.max(1, dyCount))) / 2.0;
        return meanDiff / std;
    }

    static double blockStdCoefficientOfVariation(double[][] gray, int width, int height) {
        int bw = Math.max(4, width / 8);
        int bh = Math.max(4, height / 8);
        List<Double> blockStds = new ArrayList<>();
        for (int by = 0; by + bh <= height; by += bh) {
            for (int bx = 0; bx + bw <= width; bx += bw) {
                double s = 0;
                double s2 = 0;
                int n = bw * bh;
                for (int y = by; y < by + bh; y++) {
                    for (int x = bx; x < bx + bw; x++) {
                        double v = gray[y][x];
                        s += v;
                        s2 += v * v;
                    }
                }
                double m = s / n;
                blockStds.add(Math.sqrt(Math.max(0.0, s2 / n - m * m)));
            }
        }
        if (blockStds.size() < 4) {
            return 1.0;
        }
        double sum = 0;
        for (double v : blockStds) {
            sum += v;
        }
        double mean = sum / blockStds.size();
        if (mean < 1e-6) {
            return 0.0;
        }
        double var = 0;
        for (double v : blockStds) {
            double d = v - mean;
            var += d * d;
        }
        return Math.sqrt(var / blockStds.size()) / mean;
    }

    static double meanAbsoluteLaplacian(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        double[][] gray = new double[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                gray[y][x] = BlockGray.gray(image.getRGB(x, y));
            }
        }
        return meanAbsoluteLaplacian(gray, width, height);
    }

    private static double meanAbsoluteLaplacian(double[][] gray, int width, int height) {
        double sum = 0;
        int count = 0;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                double lap = Math.abs(4 * gray[y][x]
                        - gray[y][x - 1]
                        - gray[y][x + 1]
                        - gray[y - 1][x]
                        - gray[y + 1][x]);
                sum += lap;
                count++;
            }
        }
        return sum / count;
    }
}
