package vn.bando.spatial.score;

public final class ScoreMath {

    private ScoreMath() {
    }

    public static double higherIsBetter(double value, double highPercentile) {
        requirePositiveScale(highPercentile);
        return 100.0 * Math.min(1.0, value / highPercentile);
    }

    public static double saturation(double density, double highPercentile) {
        return 100.0 - higherIsBetter(density, highPercentile);
    }

    public static double access(double distanceMeters, double medianMeters) {
        requirePositiveScale(medianMeters);
        if (distanceMeters < 0) {
            throw new IllegalArgumentException("Khoảng cách trục đường không được âm");
        }
        return 100.0 * Math.exp(-Math.log(2.0) * distanceMeters / medianMeters);
    }

    public static double weighted(double[] weights, double attractor, double saturation, double access) {
        if (weights.length != 3) {
            throw new IllegalArgumentException("Cần đúng 3 trọng số");
        }
        double sum = weights[0] + weights[1] + weights[2];
        if (Math.abs(sum - 1.0) > 1e-6) {
            throw new IllegalArgumentException("Trọng số phải cộng thành 1");
        }
        return weights[0] * attractor + weights[1] * saturation + weights[2] * access;
    }

    public static double pointsPerMillionVnd(double score, long rentVndPerMonth) {
        if (rentVndPerMonth <= 0) {
            throw new IllegalArgumentException("Tiền thuê tháng phải lớn hơn 0");
        }
        return score * 1_000_000.0 / rentVndPerMonth;
    }

    public static double imageScore(double[] weights, double sidewalk, double visibility) {
        if (weights.length != 2 || Math.abs(weights[0] + weights[1] - 1.0) > 1e-6) {
            throw new IllegalArgumentException("Điểm ảnh cần 2 trọng số cộng thành 1");
        }
        return weights[0] * sidewalk + weights[1] * visibility;
    }

    private static void requirePositiveScale(double scale) {
        if (!(scale > 0) || Double.isNaN(scale)) {
            throw new IllegalArgumentException("Thang chuẩn hóa phải dương");
        }
    }
}
