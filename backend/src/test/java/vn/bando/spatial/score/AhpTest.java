package vn.bando.spatial.score;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AhpTest {

    @Test
    void cafeWeightsMatchTheCheckedMatrix() {
        Ahp.Result result = Ahp.solve3(WorkingJudgments.spatialMatrix("cafe_drink"));
        assertWeights(result, 0.1634, 0.5396, 0.2970, 0.0079);
    }

    @Test
    void pharmacyWeightsMatchTheCheckedMatrix() {
        Ahp.Result result = Ahp.solve3(WorkingJudgments.spatialMatrix("pharmacy"));
        assertWeights(result, 0.5396, 0.1634, 0.2970, 0.0079);
    }

    @Test
    void convenienceWeightsMatchTheCheckedMatrix() {
        Ahp.Result result = Ahp.solve3(WorkingJudgments.spatialMatrix("convenience"));
        assertWeights(result, 0.5584, 0.1220, 0.3196, 0.0158);
    }

    @Test
    void inconsistentMatrixIsRejected() {
        Ahp.Result result = Ahp.solve3(new double[][] {
                {1, 9, 1.0 / 9},
                {1.0 / 9, 1, 9},
                {9, 1.0 / 9, 1}
        });
        assertTrue(result.consistencyRatio() > 1);
        assertThrows(IllegalArgumentException.class, () -> Ahp.requireConsistent(result));
    }

    @Test
    void scoresUseTheStoredScale() {
        assertEquals(0, ScoreMath.higherIsBetter(0, 90.93), 1e-9);
        assertEquals(100, ScoreMath.higherIsBetter(90.93, 90.93), 1e-9);
        assertEquals(100, ScoreMath.higherIsBetter(200, 90.93), 1e-9);
        assertEquals(100, ScoreMath.saturation(0, 23.05), 1e-9);
        assertEquals(0, ScoreMath.saturation(23.05, 23.05), 1e-9);
        assertEquals(50, ScoreMath.access(192.2, 192.2), 1e-9);
        assertEquals(100, ScoreMath.access(0, 192.2), 1e-6);
        double[] cafe = Ahp.solve3(WorkingJudgments.spatialMatrix("cafe_drink")).weights();
        double total = ScoreMath.weighted(cafe, 40, 60, 80);
        assertEquals(cafe[0] * 40 + cafe[1] * 60 + cafe[2] * 80, total, 1e-9);
        assertEquals(4, ScoreMath.pointsPerMillionVnd(80, 20_000_000), 1e-9);
    }

    @Test
    void imagePairIsConsistentByConstruction() {
        double[] weights = Ahp.pairWeights(WorkingJudgments.sidewalkVersusVisibility("cafe_drink"));
        assertEquals(1.0 / 3.0, weights[0], 1e-9);
        assertEquals(2.0 / 3.0, weights[1], 1e-9);
        assertEquals(70, ScoreMath.imageScore(Ahp.pairWeights(1), 40, 100), 1e-9);
    }

    private static void assertWeights(Ahp.Result result, double first, double second, double third, double ratio) {
        Ahp.requireConsistent(result);
        assertEquals(first, result.weights()[0], 0.001);
        assertEquals(second, result.weights()[1], 0.001);
        assertEquals(third, result.weights()[2], 0.001);
        assertEquals(1, result.weights()[0] + result.weights()[1] + result.weights()[2], 1e-9);
        assertEquals(ratio, result.consistencyRatio(), 0.001);
    }
}
