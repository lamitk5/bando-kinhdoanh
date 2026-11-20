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
        if (width >= 48 && height >= 48 && quantizedColorCount(image) < 220) {
            reasons.add("Ảnh có quá ít màu sau khi thu nhỏ, giống đồ họa hoặc sóng tổng hợp, không phải ảnh chụp mặt tiền");
        }
        if (width >= 48 && height >= 48 && periodicFlatField(grayGrid, width, height)) {
            reasons.add("Ảnh là dải sóng tuần hoàn một chiều, không phải ảnh chụp mặt tiền");
        }
        return new Verdict(!reasons.isEmpty(), List.copyOf(reasons));
    }

    static int quantizedColorCount(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int side = Math.max(width, height);
        int dw = width;
        int dh = height;
        if (side > 160) {
            double scale = 160.0 / side;
            dw = Math.max(1, (int) Math.round(width * scale));
            dh = Math.max(1, (int) Math.round(height * scale));
        }
        java.util.HashSet<Integer> colors = new java.util.HashSet<>();
        for (int y = 0; y < dh; y++) {
            int y0 = y * height / dh;
            int y1 = Math.max(y0 + 1, (y + 1) * height / dh);
            for (int x = 0; x < dw; x++) {
                int x0 = x * width / dw;
                int x1 = Math.max(x0 + 1, (x + 1) * width / dw);
                long red = 0;
                long green = 0;
                long blue = 0;
                int samples = 0;
                for (int yy = y0; yy < y1; yy++) {
                    for (int xx = x0; xx < x1; xx++) {
                        int rgb = image.getRGB(xx, yy);
                        red += (rgb >> 16) & 0xff;
                        green += (rgb >> 8) & 0xff;
                        blue += rgb & 0xff;
                        samples++;
                    }
                }
                int rq = (int) (red / samples) / 16;
                int gq = (int) (green / samples) / 16;
                int bq = (int) (blue / samples) / 16;
                colors.add((rq << 8) | (gq << 4) | bq);
            }
        }
        return colors.size();
    }

    static boolean periodicFlatField(double[][] gray, int width, int height) {
        double horizontal = axisAutocorr(gray, width, height, true);
        double vertical = axisAutocorr(gray, width, height, false);
        double horizontalDiff = meanAbsNeighbor(gray, width, height, true);
        double verticalDiff = meanAbsNeighbor(gray, width, height, false);
        return (horizontal > 0.72 && verticalDiff < 0.12 * Math.max(1.0, horizontalDiff))
                || (vertical > 0.72 && horizontalDiff < 0.12 * Math.max(1.0, verticalDiff));
    }

    private static double axisAutocorr(double[][] gray, int width, int height, boolean horizontal) {
        int n = Math.min(128, horizontal ? width : height);
        if (n < 16) {
            return 0;
        }
        double[] samples = new double[n];
        double mean = 0;
        int y = height / 2;
        int x = width / 2;
        for (int i = 0; i < n; i++) {
            double value = horizontal
                    ? gray[y][i * (width - 1) / (n - 1)]
                    : gray[i * (height - 1) / (n - 1)][x];
            samples[i] = value;
            mean += value;
        }
        mean /= n;
        double energy = 0;
        for (int i = 0; i < n; i++) {
            samples[i] -= mean;
            energy += samples[i] * samples[i];
        }
        if (energy < n * 25.0) {
            return 0;
        }
        double best = 0;
        for (int lag : new int[] {8, 12, 16, 24, 32}) {
            if (lag >= n / 2) {
                continue;
            }
            double corr = 0;
            for (int i = 0; i < n - lag; i++) {
                corr += samples[i] * samples[i + lag];
            }
            best = Math.max(best, corr / energy);
        }
        return best;
    }

    private static double meanAbsNeighbor(double[][] gray, int width, int height, boolean horizontal) {
        int stepX = Math.max(1, width / 160);
        int stepY = Math.max(1, height / 160);
        double sum = 0;
        int count = 0;
        if (horizontal) {
            for (int y = 0; y < height; y += stepY) {
                for (int x = 0; x + stepX < width; x += stepX) {
                    sum += Math.abs(gray[y][x + stepX] - gray[y][x]);
                    count++;
                }
            }
        } else {
            for (int y = 0; y + stepY < height; y += stepY) {
                for (int x = 0; x < width; x += stepX) {
                    sum += Math.abs(gray[y + stepY][x] - gray[y][x]);
                    count++;
                }
            }
        }
        return count == 0 ? 0 : sum / count;
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
