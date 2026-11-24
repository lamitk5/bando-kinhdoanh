package vn.bando.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import vn.bando.spatial.ContributionRules;

class OsmRefsTest {

    @Test
    void buildsOpenStreetMapUrls() {
        assertEquals("https://www.openstreetmap.org/node/5", OsmRefs.url("N", 5));
        assertEquals("https://www.openstreetmap.org/way/10", OsmRefs.url("W", 10));
        assertEquals("https://www.openstreetmap.org/relation/100", OsmRefs.url("R", 100));
    }

    @Test
    void rejectsUnknownType() {
        assertThrows(IllegalArgumentException.class, () -> OsmRefs.url("X", 1));
    }

    @Test
    void cacheKeyIncludesImportId() {
        assertEquals("spatial:summary:v1:3", CacheKeys.summary(3));
    }

    @Test
    void bufferCacheKeyUsesSixDecimals() {
        assertEquals(
                "spatial:buffer:v2:1:cafe_drink:21.037861:105.801001:0",
                CacheKeys.buffer(1, "cafe_drink", 21.037861, 105.801001, 0));
    }

    @Test
    void bufferRequestRoundsCoordinatesAndRejectsOtherTrades() {
        BufferRequest.Normalized normalized = BufferRequest.normalize(21.03786119, 105.80100051, "pharmacy");
        assertEquals(21.037861, normalized.lat());
        assertEquals(105.801001, normalized.lon());
        assertThrows(IllegalArgumentException.class, () -> BufferRequest.normalize(21.03, 105.8, "restaurant"));
        assertThrows(IllegalArgumentException.class, () -> BufferRequest.normalize(0, 0, "cafe_drink"));
    }

    @Test
    void contributionRulesTrimAndRejectUnknownCategory() {
        assertEquals("Cà phê", ContributionRules.cleanName("  Cà phê  "));
        assertEquals("cafe_drink", ContributionRules.requireCategory("cafe_drink"));
        assertEquals(21.037861, ContributionRules.latitude(21.0378614));
        assertThrows(IllegalArgumentException.class, () -> ContributionRules.requireCategory("restaurant"));
        assertThrows(IllegalArgumentException.class, () -> ContributionRules.requireRejectionNote("  "));
        assertThrows(IllegalArgumentException.class, () -> ContributionRules.longitude(0));
    }
}
