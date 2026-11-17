package vn.bando.spatial.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VisionAssessmentParserTest {

    @Test
    void parsesAUsableStorefront() {
        VisionAssessmentParser.Assessment assessment = VisionAssessmentParser.parse("""
                {"image_quality":"ok","quality_reasons":[],"sidewalk_capacity":"enough","visibility":"clear","recommendation":"Vỉa hè đi được và mặt tiền thoáng.","meters":12}
                """);
        assertTrue(assessment.assessed());
        assertEquals(70, assessment.sidewalkScore());
        assertEquals(100, assessment.visibilityScore());
    }

    @Test
    void parsesAPoorPhotoWithoutMeasurements() {
        VisionAssessmentParser.Assessment assessment = VisionAssessmentParser.parse("""
                ```json
                {"image_quality":"poor","quality_reasons":["tối"],"sidewalk_capacity":null,"visibility":null,"recommendation":"Ảnh không đủ để nhận xét mặt tiền."}
                ```
                """);
        assertFalse(assessment.assessed());
        assertNull(assessment.sidewalkScore());
        assertNull(assessment.visibilityScore());
        assertEquals("tối", assessment.qualityReasons().get(0));
    }

    @Test
    void rejectsAPoorPhotoThatStillRatesTheSidewalk() {
        assertThrows(IllegalArgumentException.class, () -> VisionAssessmentParser.parse("""
                {"image_quality":"poor","quality_reasons":["mờ"],"sidewalk_capacity":"wide","visibility":null,"recommendation":"Không dùng."}
                """));
    }

    @Test
    void rejectsAnOkPhotoMissingVisibility() {
        assertThrows(IllegalArgumentException.class, () -> VisionAssessmentParser.parse("""
                {"image_quality":"ok","quality_reasons":[],"sidewalk_capacity":"narrow","recommendation":"Thiếu tầm nhìn."}
                """));
    }
}
