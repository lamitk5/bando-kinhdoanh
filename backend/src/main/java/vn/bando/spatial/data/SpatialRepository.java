package vn.bando.spatial.data;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import vn.bando.spatial.OsmRefs;
import vn.bando.spatial.api.SpatialModels.CategoryCount;
import vn.bando.spatial.api.SpatialModels.DistrictSummary;
import vn.bando.spatial.api.SpatialModels.HealthResponse;
import vn.bando.spatial.api.SpatialModels.ImportInfo;
import vn.bando.spatial.api.SpatialModels.PoiSample;
import vn.bando.spatial.api.SpatialModels.RoadSummary;
import vn.bando.spatial.api.SpatialModels.SummaryBody;

@Repository
public class SpatialRepository {

    private static final List<String> REQUIRED_GIST = List.of(
            "district_geom_gix",
            "poi_geom_gix",
            "road_geom_gix",
            "road_arterial_geog_gix"
    );

    private final JdbcTemplate jdbc;

    public SpatialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ImportInfo latestImport() {
        return jdbc.queryForObject(
                """
                SELECT id, source_url, sha256, retrieved_at, osm_header_timestamp, attribution,
                       poi_inserted, road_inserted
                FROM data_import
                ORDER BY id DESC
                LIMIT 1
                """,
                (rs, rowNum) -> new ImportInfo(
                        rs.getLong("id"),
                        rs.getString("source_url"),
                        rs.getString("sha256"),
                        rs.getObject("retrieved_at", java.time.OffsetDateTime.class),
                        rs.getObject("osm_header_timestamp", java.time.OffsetDateTime.class),
                        rs.getString("attribution"),
                        rs.getLong("poi_inserted"),
                        rs.getLong("road_inserted")
                )
        );
    }

    public SummaryBody loadBody(ImportInfo dataImport) {
        Map<String, DistrictAccumulator> districts = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT d.osm_id, d.name, d.area_km2, p.category, p.role, count(p.id) AS n
                FROM district AS d
                LEFT JOIN poi AS p ON p.district_id = d.id
                GROUP BY d.osm_id, d.name, d.area_km2, p.category, p.role
                ORDER BY d.name, p.category
                """,
                rs -> {
                    String name = rs.getString("name");
                    long osmId = rs.getLong("osm_id");
                    double areaKm2 = rs.getDouble("area_km2");
                    DistrictAccumulator district = districts.computeIfAbsent(
                            name,
                            ignored -> new DistrictAccumulator(osmId, name, areaKm2)
                    );
                    String category = rs.getString("category");
                    if (category != null) {
                        long count = rs.getLong("n");
                        district.poiCount += count;
                        district.counts.add(new CategoryCount(category, rs.getString("role"), count));
                    }
                }
        );

        List<RoadSummary> roads = jdbc.query(
                """
                SELECT d.name,
                       count(rd.road_id) AS segment_count,
                       count(rd.road_id) FILTER (WHERE r.arterial) AS arterial_segment_count,
                       coalesce(sum(rd.length_m), 0) / 1000.0 AS length_km,
                       coalesce(sum(rd.length_m) FILTER (WHERE r.arterial), 0) / 1000.0 AS arterial_length_km
                FROM district AS d
                LEFT JOIN road_district AS rd ON rd.district_id = d.id
                LEFT JOIN road AS r ON r.id = rd.road_id
                GROUP BY d.name
                ORDER BY d.name
                """,
                (rs, rowNum) -> new RoadSummary(
                        rs.getString("name"),
                        rs.getLong("segment_count"),
                        rs.getLong("arterial_segment_count"),
                        rs.getDouble("length_km"),
                        rs.getDouble("arterial_length_km")
                )
        );

        List<DistrictSummary> districtSummaries = districts.values().stream()
                .map(district -> new DistrictSummary(
                        district.osmId,
                        district.name,
                        district.areaKm2,
                        district.poiCount,
                        List.copyOf(district.counts)
                ))
                .toList();
        return new SummaryBody(dataImport, districtSummaries, roads);
    }

    public List<PoiSample> samples(int limit) {
        return jdbc.query(
                """
                SELECT d.name AS district, p.category, p.role, p.osm_type, p.osm_id, p.name,
                       p.osm_key, p.osm_value, ST_Y(p.geom) AS lat, ST_X(p.geom) AS lon,
                       p.on_boundary, p.possible_duplicate
                FROM poi AS p
                JOIN district AS d ON d.id = p.district_id
                ORDER BY d.name, p.category, p.osm_id
                LIMIT ?
                """,
                (rs, rowNum) -> {
                    String osmType = rs.getString("osm_type");
                    long osmId = rs.getLong("osm_id");
                    return new PoiSample(
                            rs.getString("district"),
                            rs.getString("category"),
                            rs.getString("role"),
                            osmType,
                            osmId,
                            rs.getString("name"),
                            rs.getString("osm_key"),
                            rs.getString("osm_value"),
                            rs.getDouble("lat"),
                            rs.getDouble("lon"),
                            rs.getBoolean("on_boundary"),
                            rs.getBoolean("possible_duplicate"),
                            OsmRefs.url(osmType, osmId)
                    );
                },
                limit
        );
    }

    public String bufferProfile(double lon, double lat, String businessType) {
        try {
            return jdbc.queryForObject(
                    "SELECT buffer_profile(?, ?, ?)::text",
                    String.class,
                    lon,
                    lat,
                    businessType
            );
        } catch (DataAccessException exception) {
            throw mapSqlState(exception);
        }
    }

    public String submitContribution(
            String name,
            String category,
            double lon,
            double lat,
            String note,
            String submitter) {
        try {
            return jdbc.queryForObject(
                    "SELECT submit_poi_contribution(?, ?, ?, ?, ?, ?)::text",
                    String.class,
                    name,
                    category,
                    lon,
                    lat,
                    note,
                    submitter
            );
        } catch (DataAccessException exception) {
            throw mapSqlState(exception);
        }
    }

    public String decideContribution(long id, String decision, String note) {
        try {
            return jdbc.queryForObject(
                    "SELECT decide_poi_contribution(?, ?, ?)::text",
                    String.class,
                    id,
                    decision,
                    note
            );
        } catch (DataAccessException exception) {
            throw mapSqlState(exception);
        }
    }

    public long contributionEpoch() {
        Long epoch = jdbc.queryForObject("SELECT epoch FROM contribution_epoch WHERE id = 1", Long.class);
        if (epoch == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa có mốc đóng góp");
        }
        return epoch;
    }

    public String listContributions(String status) {
        try {
            return jdbc.queryForObject(
                    "SELECT list_poi_contributions(?)::text",
                    String.class,
                    status
            );
        } catch (DataAccessException exception) {
            throw mapSqlState(exception);
        }
    }

    private static RuntimeException mapSqlState(DataAccessException exception) {
        Throwable cause = exception.getMostSpecificCause();
        if (cause instanceof SQLException sql) {
            String state = sql.getSQLState();
            if ("22023".equals(state)) {
                return new ResponseStatusException(HttpStatus.BAD_REQUEST, cause.getMessage(), exception);
            }
            if ("02000".equals(state)) {
                return new ResponseStatusException(HttpStatus.NOT_FOUND, cause.getMessage(), exception);
            }
            if ("P0001".equals(state)) {
                return new ResponseStatusException(HttpStatus.CONFLICT, cause.getMessage(), exception);
            }
        }
        return exception;
    }

    public HealthResponse health(String redisStatus) {
        String postgis = jdbc.queryForObject("SELECT PostGIS_Version()", String.class);
        List<String> indexes = jdbc.query(
                """
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public' AND indexdef ILIKE '%USING gist%'
                ORDER BY indexname
                """,
                (rs, rowNum) -> rs.getString("indexname")
        );
        boolean gistOk = indexes.containsAll(REQUIRED_GIST);
        Integer districtCount = jdbc.queryForObject("SELECT count(*)::int FROM district", Integer.class);
        Long poiCount = jdbc.queryForObject("SELECT count(*) FROM poi", Long.class);
        List<Long> importIds = jdbc.query(
                "SELECT id FROM data_import ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> rs.getLong("id")
        );
        return new HealthResponse(
                "ok",
                postgis,
                redisStatus,
                indexes,
                gistOk,
                districtCount == null ? 0 : districtCount,
                poiCount == null ? 0 : poiCount,
                importIds.isEmpty() ? null : importIds.get(0)
        );
    }

    private static final class DistrictAccumulator {
        private final long osmId;
        private final String name;
        private final double areaKm2;
        private long poiCount;
        private final List<CategoryCount> counts = new ArrayList<>();

        private DistrictAccumulator(long osmId, String name, double areaKm2) {
            this.osmId = osmId;
            this.name = name;
            this.areaKm2 = areaKm2;
        }
    }
}
