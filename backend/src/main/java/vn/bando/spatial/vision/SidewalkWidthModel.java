package vn.bando.spatial.vision;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Mô hình bề rộng vỉa hè học từ số đo mét. Không sinh tầm nhìn và không đổi trọng số Qwen.
 * Chỉ được đưa ra dự đoán khi sai số kiểm tra thấp hơn dự đoán bằng trung bình tập học.
 */
public final class SidewalkWidthModel {

    private final String selected;
    private final boolean beatsBaseline;
    private final double testMaeMeters;
    private final double baselineMaeMeters;
    private final double targetMean;
    private final double[] mean;
    private final double[] std;
    private final double[] ridgeWeights;
    private final double[][] w1;
    private final double[] b1;
    private final double[] w2;
    private final double b2;
    private final int neighbors;
    private final double[][] trainFeatures;
    private final double[] trainTargets;

    private SidewalkWidthModel(
            String selected,
            boolean beatsBaseline,
            double testMaeMeters,
            double baselineMaeMeters,
            double targetMean,
            double[] mean,
            double[] std,
            double[] ridgeWeights,
            double[][] w1,
            double[] b1,
            double[] w2,
            double b2,
            int neighbors,
            double[][] trainFeatures,
            double[] trainTargets) {
        this.selected = selected;
        this.beatsBaseline = beatsBaseline;
        this.testMaeMeters = testMaeMeters;
        this.baselineMaeMeters = baselineMaeMeters;
        this.targetMean = targetMean;
        this.mean = mean;
        this.std = std;
        this.ridgeWeights = ridgeWeights;
        this.w1 = w1;
        this.b1 = b1;
        this.w2 = w2;
        this.b2 = b2;
        this.neighbors = neighbors;
        this.trainFeatures = trainFeatures;
        this.trainTargets = trainTargets;
    }

    public static SidewalkWidthModel read(JsonNode node) {
        String selected = node.path("selected").asText();
        if (!"ridge".equals(selected) && !"mlp".equals(selected) && !"knn".equals(selected)) {
            throw new IllegalArgumentException("Mô hình bề rộng không có họ đã chọn");
        }
        double[] mean = vector(node, "feature_mean");
        double[] std = vector(node, "feature_std");
        if (mean.length != std.length || mean.length == 0) {
            throw new IllegalArgumentException("Chuẩn hóa đặc trưng không khớp");
        }
        double[] ridge = vector(node.path("ridge"), "weights");
        JsonNode mlp = node.path("mlp");
        double[][] w1 = matrix(mlp.path("w1"));
        double[] b1 = vector(mlp, "b1");
        double[] w2 = vector(mlp, "w2");
        if ("ridge".equals(selected) && ridge.length != mean.length) {
            throw new IllegalArgumentException("Trọng số ridge không khớp số đặc trưng");
        }
        if ("mlp".equals(selected) && (w1.length == 0 || w1[0].length != mean.length || b1.length != w1.length || w2.length != w1.length)) {
            throw new IllegalArgumentException("Trọng số mạng không khớp số đặc trưng");
        }
        int neighbors = 0;
        double[][] trainFeatures = new double[0][0];
        double[] trainTargets = new double[0];
        if ("knn".equals(selected)) {
            JsonNode knn = node.path("knn");
            neighbors = knn.path("k").asInt();
            trainFeatures = matrix(knn.path("train_features"));
            trainTargets = vector(knn, "train_targets_m");
            if (neighbors < 1 || trainFeatures.length < neighbors || trainTargets.length != trainFeatures.length
                    || trainFeatures[0].length != mean.length) {
                throw new IllegalArgumentException("Tập láng giềng không khớp số đặc trưng");
            }
        }
        return new SidewalkWidthModel(
                selected,
                node.path("beats_baseline").asBoolean(false),
                node.path("test_mae_m").asDouble(),
                node.path("baseline_test_mae_m").asDouble(),
                node.path("target_mean_m").asDouble(),
                mean,
                std,
                ridge,
                w1,
                b1,
                w2,
                mlp.path("b2").asDouble(),
                neighbors,
                trainFeatures,
                trainTargets);
    }

    public String selected() {
        return selected;
    }

    public boolean beatsBaseline() {
        return beatsBaseline;
    }

    public double testMaeMeters() {
        return testMaeMeters;
    }

    public double baselineMaeMeters() {
        return baselineMaeMeters;
    }

    public double predictMeters(double[] pixels) {
        if (!beatsBaseline) {
            throw new IllegalStateException(
                    "Mô hình không khá hơn trung bình tập học trên tập kiểm tra, nên không đưa ra bề rộng.");
        }
        return rawMeters(pixels);
    }

    public double rawMeters(double[] pixels) {
        if (pixels == null || pixels.length != mean.length) {
            throw new IllegalArgumentException("Ảnh đã thu nhỏ phải có " + mean.length + " điểm");
        }
        double[] scaled = new double[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            scaled[i] = (pixels[i] - mean[i]) / std[i];
        }
        if ("ridge".equals(selected)) {
            return dot(ridgeWeights, scaled) + targetMean;
        }
        if ("knn".equals(selected)) {
            return nearestMean(scaled);
        }
        double[] hidden = new double[w1.length];
        for (int row = 0; row < w1.length; row++) {
            hidden[row] = Math.max(0, dot(w1[row], scaled) + b1[row]);
        }
        return dot(w2, hidden) + b2 + targetMean;
    }

    private double nearestMean(double[] scaled) {
        int[] chosen = new int[neighbors];
        double[] chosenDistance = new double[neighbors];
        java.util.Arrays.fill(chosenDistance, Double.POSITIVE_INFINITY);
        for (int row = 0; row < trainFeatures.length; row++) {
            double distance = 0;
            for (int col = 0; col < scaled.length; col++) {
                double delta = scaled[col] - ((trainFeatures[row][col] - mean[col]) / std[col]);
                distance += delta * delta;
            }
            int place = neighbors - 1;
            if (distance >= chosenDistance[place]) {
                continue;
            }
            while (place > 0 && distance < chosenDistance[place - 1]) {
                chosenDistance[place] = chosenDistance[place - 1];
                chosen[place] = chosen[place - 1];
                place--;
            }
            chosenDistance[place] = distance;
            chosen[place] = row;
        }
        double sum = 0;
        for (int index : chosen) {
            sum += trainTargets[index];
        }
        return sum / neighbors;
    }

    private static double dot(double[] left, double[] right) {
        double sum = 0;
        for (int i = 0; i < left.length; i++) {
            sum += left[i] * right[i];
        }
        return sum;
    }

    private static double[] vector(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException("Thiếu " + field);
        }
        double[] out = new double[value.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = value.get(i).asDouble();
        }
        return out;
    }

    private static double[][] matrix(JsonNode value) {
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException("Thiếu ma trận mạng");
        }
        double[][] out = new double[value.size()][];
        for (int row = 0; row < out.length; row++) {
            JsonNode line = value.get(row);
            out[row] = new double[line.size()];
            for (int col = 0; col < out[row].length; col++) {
                out[row][col] = line.get(col).asDouble();
            }
        }
        return out;
    }
}
