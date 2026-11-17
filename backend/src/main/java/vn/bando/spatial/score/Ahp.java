package vn.bando.spatial.score;

public final class Ahp {

    public static final double RANDOM_INDEX_N3 = 0.58;

    private Ahp() {
    }

    public record Result(double[] weights, double lambdaMax, double consistencyIndex, double consistencyRatio) {
    }

    public static Result solve3(double[][] matrix) {
        if (matrix.length != 3 || matrix[0].length != 3 || matrix[1].length != 3 || matrix[2].length != 3) {
            throw new IllegalArgumentException("Ma trận AHP giai đoạn 1 phải là 3x3");
        }
        for (int row = 0; row < 3; row++) {
            if (Math.abs(matrix[row][row] - 1.0) > 1e-9) {
                throw new IllegalArgumentException("Đường chéo ma trận phải bằng 1");
            }
            for (int column = 0; column < 3; column++) {
                if (matrix[row][column] <= 0) {
                    throw new IllegalArgumentException("Phần tử ma trận phải dương");
                }
                if (Math.abs(matrix[row][column] * matrix[column][row] - 1.0) > 1e-6) {
                    throw new IllegalArgumentException("Ma trận phải nghịch đảo");
                }
            }
        }
        double[] weights = {1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0};
        for (int iteration = 0; iteration < 60; iteration++) {
            double[] next = multiply(matrix, weights);
            double sum = next[0] + next[1] + next[2];
            weights = new double[] {next[0] / sum, next[1] / sum, next[2] / sum};
        }
        double[] applied = multiply(matrix, weights);
        double lambda = (applied[0] / weights[0] + applied[1] / weights[1] + applied[2] / weights[2]) / 3.0;
        double consistencyIndex = (lambda - 3.0) / 2.0;
        return new Result(weights, lambda, consistencyIndex, consistencyIndex / RANDOM_INDEX_N3);
    }

    public static double[] pairWeights(double firstVersusSecond) {
        if (firstVersusSecond <= 0) {
            throw new IllegalArgumentException("So sánh cặp phải dương");
        }
        double first = Math.sqrt(firstVersusSecond);
        double second = Math.sqrt(1.0 / firstVersusSecond);
        double sum = first + second;
        return new double[] {first / sum, second / sum};
    }

    public static void requireConsistent(Result result) {
        if (!(result.consistencyRatio() < 0.1)) {
            throw new IllegalArgumentException("CR của ma trận làm việc phải nhỏ hơn 0,1");
        }
    }

    private static double[] multiply(double[][] matrix, double[] vector) {
        return new double[] {
                matrix[0][0] * vector[0] + matrix[0][1] * vector[1] + matrix[0][2] * vector[2],
                matrix[1][0] * vector[0] + matrix[1][1] * vector[1] + matrix[1][2] * vector[2],
                matrix[2][0] * vector[0] + matrix[2][1] * vector[1] + matrix[2][2] * vector[2]
        };
    }
}
