package vn.bando.spatial.api;

import java.time.OffsetDateTime;
import java.util.List;

public final class SpatialModels {

    private SpatialModels() {
    }

    public record ImportInfo(
            long id,
            String sourceUrl,
            String sha256,
            OffsetDateTime retrievedAt,
            OffsetDateTime osmHeaderTimestamp,
            String attribution,
            long poiCount,
            long roadCount
    ) {
    }

    public record CategoryCount(String category, String role, long count) {
    }

    public record DistrictSummary(
            long osmId,
            String name,
            double areaKm2,
            long poiCount,
            List<CategoryCount> counts
    ) {
    }

    public record RoadSummary(
            String district,
            long segmentCount,
            long arterialSegmentCount,
            double lengthKm,
            double arterialLengthKm
    ) {
    }

    public record SummaryBody(
            ImportInfo dataImport,
            List<DistrictSummary> districts,
            List<RoadSummary> roads
    ) {
    }

    public record CacheInfo(boolean hit, String key, long ttlSeconds) {
    }

    public record SummaryResponse(SummaryBody body, CacheInfo cache) {
    }

    public record PoiSample(
            String district,
            String category,
            String role,
            String osmType,
            long osmId,
            String name,
            String osmKey,
            String osmValue,
            double lat,
            double lon,
            boolean onBoundary,
            boolean possibleDuplicate,
            String osmUrl
    ) {
    }

    public record HealthResponse(
            String postgres,
            String postgis,
            String redis,
            List<String> gistIndexes,
            boolean gistOk,
            int districtCount,
            long poiCount,
            Long latestImportId
    ) {
    }
}
