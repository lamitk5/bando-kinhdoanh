package vn.bando.spatial.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class SidewalkWidthModelTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void ridgeAddsTheTrainingMean() throws Exception {
        SidewalkWidthModel model = SidewalkWidthModel.read(mapper.readTree("""
                {
                  "selected": "ridge",
                  "beats_baseline": true,
                  "test_mae_m": 0.4,
                  "baseline_test_mae_m": 0.8,
                  "target_mean_m": 1,
                  "feature_mean": [0, 0],
                  "feature_std": [1, 1],
                  "ridge": {"weights": [2, 0]},
                  "mlp": {"w1": [[0, 0]], "b1": [0], "w2": [0], "b2": 0}
                }
                """));
        assertEquals(7, model.predictMeters(new double[] {3, 4}));
    }

    @Test
    void mlpUsesARectifiedHiddenLayer() throws Exception {
        SidewalkWidthModel model = SidewalkWidthModel.read(mapper.readTree("""
                {
                  "selected": "mlp",
                  "beats_baseline": true,
                  "test_mae_m": 0.2,
                  "baseline_test_mae_m": 0.5,
                  "target_mean_m": 0,
                  "feature_mean": [0, 0],
                  "feature_std": [1, 1],
                  "ridge": {"weights": [0, 0]},
                  "mlp": {"w1": [[1, 0], [0, -1]], "b1": [0, 0], "w2": [1, 1], "b2": 0.5}
                }
                """));
        assertEquals(2.5, model.predictMeters(new double[] {2, 3}));
    }

    @Test
    void aModelThatLosesToTheMeanDoesNotAnswer() throws Exception {
        SidewalkWidthModel model = SidewalkWidthModel.read(mapper.readTree("""
                {
                  "selected": "ridge",
                  "beats_baseline": false,
                  "test_mae_m": 1.2,
                  "baseline_test_mae_m": 0.9,
                  "target_mean_m": 3,
                  "feature_mean": [0],
                  "feature_std": [1],
                  "ridge": {"weights": [1]},
                  "mlp": {"w1": [[0]], "b1": [0], "w2": [0], "b2": 0}
                }
                """));
        assertEquals(4, model.rawMeters(new double[] {1}));
        assertThrows(IllegalStateException.class, () -> model.predictMeters(new double[] {1}));
    }

    @Test
    void publishedSeoulModelBeatsTheMeanAndMatchesItsCoefficients() throws Exception {
        JsonNode node = mapper.readTree(SidewalkWidthModel.class.getResourceAsStream("/vision/sidewalk_width_model.json"));
        SidewalkWidthModel model = SidewalkWidthModel.read(node);
        assertEquals("mlp", model.selected());
        assertEquals(349, node.path("train_count").asInt());
        assertEquals(53, node.path("val_count").asInt());
        assertEquals(112, node.path("test_count").asInt());
        assertEquals(48, node.path("resize").get(0).asInt());
        assertEquals(36, node.path("resize").get(1).asInt());
        assertEquals(0.8962524457859952, model.testMaeMeters(), 1e-12);
        assertEquals(1.2699672533769955, model.baselineMaeMeters(), 1e-12);
        assertTrue(model.beatsBaseline());
        assertTrue(model.testMaeMeters() < model.baselineMaeMeters());
        assertFalse(node.path("qwen_weights_updated").asBoolean());
        assertFalse(node.path("visibility_trained").asBoolean());
        assertEquals("10.5281/zenodo.22908353", node.path("doi").asText());
        double[] gray = new double[1728];
        java.util.Arrays.fill(gray, 0.5);
        assertEquals(2.0732804281586765, model.predictMeters(gray), 1e-6);
    }
}
