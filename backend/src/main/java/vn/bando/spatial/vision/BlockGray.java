package vn.bando.spatial.vision;

import java.awt.image.BufferedImage;

/** Trung bình từng ô của ảnh xám. Cùng công thức với script huấn luyện. */
public final class BlockGray {

    private BlockGray() {
    }

    public static double[] features(BufferedImage image, int gridWidth, int gridHeight) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width < 1 || height < 1 || gridWidth < 1 || gridHeight < 1) {
            throw new IllegalArgumentException("Ảnh hoặc lưới đặc trưng không hợp lệ");
        }
        double[] out = new double[gridWidth * gridHeight];
        int index = 0;
        for (int y = 0; y < gridHeight; y++) {
            int y0 = y * height / gridHeight;
            int y1 = Math.max(y0 + 1, (y + 1) * height / gridHeight);
            for (int x = 0; x < gridWidth; x++) {
                int x0 = x * width / gridWidth;
                int x1 = Math.max(x0 + 1, (x + 1) * width / gridWidth);
                double sum = 0;
                int count = 0;
                for (int yy = y0; yy < y1; yy++) {
                    for (int xx = x0; xx < x1; xx++) {
                        sum += gray(image.getRGB(xx, yy));
                        count++;
                    }
                }
                out[index++] = (sum / count) / 255.0;
            }
        }
        return out;
    }

    static double gray(int rgb) {
        int red = (rgb >> 16) & 0xff;
        int green = (rgb >> 8) & 0xff;
        int blue = rgb & 0xff;
        return (299.0 * red + 587.0 * green + 114.0 * blue) / 1000.0;
    }
}
