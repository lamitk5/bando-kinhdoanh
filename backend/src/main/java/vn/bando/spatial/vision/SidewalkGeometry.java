package vn.bando.spatial.vision;

import java.awt.image.BufferedImage;

/** Đặc trưng dải và biên. Cùng công thức với etl/vision/train_sidewalk_geometry.py. */
public final class SidewalkGeometry {

    public static final int GRID_W = 128;
    public static final int GRID_H = 96;

    private SidewalkGeometry() {
    }

    public static double[] features(BufferedImage image) {
        double[][] red = blockMeans(image, 16);
        double[][] green = blockMeans(image, 8);
        double[][] blue = blockMeans(image, 0);
        double[][] gray = new double[GRID_H][GRID_W];
        double[][] saturation = new double[GRID_H][GRID_W];
        for (int y = 0; y < GRID_H; y++) {
            for (int x = 0; x < GRID_W; x++) {
                double r = red[y][x];
                double g = green[y][x];
                double b = blue[y][x];
                gray[y][x] = (299.0 * r + 587.0 * g + 114.0 * b) / 1000.0;
                double peak = Math.max(r, Math.max(g, b));
                double low = Math.min(r, Math.min(g, b));
                saturation[y][x] = peak > 0 ? (peak - low) / peak : 0;
            }
        }
        double[] out = new double[96];
        int index = 0;
        for (int band = 0; band < 8; band++) {
            double sum = 0;
            double sumSquares = 0;
            double left = 0;
            double middle = 0;
            double right = 0;
            double color = 0;
            int count = 12 * GRID_W;
            for (int y = band * 12; y < (band + 1) * 12; y++) {
                for (int x = 0; x < GRID_W; x++) {
                    double value = gray[y][x];
                    sum += value;
                    sumSquares += value * value;
                    if (x < 43) {
                        left += value;
                    } else if (x < 86) {
                        middle += value;
                    } else {
                        right += value;
                    }
                    color += saturation[y][x];
                }
            }
            double mean = sum / count;
            double variance = sumSquares / count - mean * mean;
            out[index++] = mean / 255.0;
            out[index++] = Math.sqrt(Math.max(0, variance)) / 255.0;
            out[index++] = left / (12.0 * 43) / 255.0;
            out[index++] = middle / (12.0 * 43) / 255.0;
            out[index++] = right / (12.0 * 42) / 255.0;
            out[index++] = color / count;
        }
        for (int step = 0; step < 16; step++) {
            double[] row = gray[3 + step * 6];
            double total = 1e-6;
            double moment = 0;
            double peak = 0;
            double[] diff = new double[127];
            for (int x = 0; x < 127; x++) {
                diff[x] = Math.abs(row[x + 1] - row[x]);
                total += diff[x];
                moment += diff[x] * x;
                peak = Math.max(peak, diff[x]);
            }
            double center = moment / total / 126.0;
            double leftEdge = 0;
            double span = 0;
            if (peak >= 1.0) {
                int first = -1;
                int last = -1;
                for (int x = 0; x < 127; x++) {
                    if (diff[x] > 0.25 * peak) {
                        if (first < 0) {
                            first = x;
                        }
                        last = x;
                    }
                }
                leftEdge = first / 126.0;
                span = (last - first) / 126.0;
            }
            out[index++] = center;
            out[index++] = span;
            out[index++] = leftEdge;
        }
        return out;
    }

    private static double[][] blockMeans(BufferedImage image, int shift) {
        int height = image.getHeight();
        int width = image.getWidth();
        double[][] integral = new double[height + 1][width + 1];
        for (int y = 0; y < height; y++) {
            double running = 0;
            for (int x = 0; x < width; x++) {
                running += (image.getRGB(x, y) >> shift) & 0xff;
                integral[y + 1][x + 1] = integral[y][x + 1] + running;
            }
        }
        double[][] out = new double[GRID_H][GRID_W];
        for (int y = 0; y < GRID_H; y++) {
            int y0 = y * height / GRID_H;
            int y1 = Math.max(y0 + 1, (y + 1) * height / GRID_H);
            for (int x = 0; x < GRID_W; x++) {
                int x0 = x * width / GRID_W;
                int x1 = Math.max(x0 + 1, (x + 1) * width / GRID_W);
                double sum = integral[y1][x1] - integral[y0][x1] - integral[y1][x0] + integral[y0][x0];
                out[y][x] = sum / ((y1 - y0) * (x1 - x0));
            }
        }
        return out;
    }
}
