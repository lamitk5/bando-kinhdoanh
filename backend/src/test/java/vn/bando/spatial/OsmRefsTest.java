package vn.bando.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

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
}
