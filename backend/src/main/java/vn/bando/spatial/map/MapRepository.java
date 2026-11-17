package vn.bando.spatial.map;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MapRepository {

    private final JdbcTemplate jdbc;

    public MapRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record MapPoint(
            String district,
            String name,
            String role,
            String category,
            String businessType,
            String osmType,
            long osmId,
            double lat,
            double lon) {
    }

    public record HeatCell(double lat, double lon, int attractors, int competitors) {
    }

    public record DistrictShape(String name, String geoJson) {
    }

    public List<DistrictShape> districts() {
        return jdbc.query(
                """
                SELECT name, ST_AsGeoJSON(geom) AS geojson
                FROM district
                ORDER BY name
                """,
                (rs, row) -> new DistrictShape(rs.getString("name"), rs.getString("geojson")));
    }

    public List<MapPoint> points(double west, double south, double east, double north, String businessType, int limit) {
        return jdbc.query(
                """
                SELECT d.name AS district, p.name, p.role, p.category, p.business_type,
                       p.osm_type, p.osm_id, ST_Y(p.geom) AS lat, ST_X(p.geom) AS lon
                FROM poi AS p
                JOIN district AS d ON d.id = p.district_id
                WHERE p.geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)
                  AND ST_Intersects(p.geom, ST_MakeEnvelope(?, ?, ?, ?, 4326))
                  AND (p.role = 'attractor' OR p.business_type = ?)
                ORDER BY p.role, p.osm_id
                LIMIT ?
                """,
                (rs, row) -> new MapPoint(
                        rs.getString("district"),
                        rs.getString("name"),
                        rs.getString("role"),
                        rs.getString("category"),
                        rs.getString("business_type"),
                        rs.getString("osm_type"),
                        rs.getLong("osm_id"),
                        rs.getDouble("lat"),
                        rs.getDouble("lon")),
                west, south, east, north,
                west, south, east, north,
                businessType,
                limit);
    }

    public List<HeatCell> heatmap(double west, double south, double east, double north, String businessType) {
        return jdbc.query(
                """
                SELECT ST_Y(cell) AS lat, ST_X(cell) AS lon,
                       count(*) FILTER (WHERE role = 'attractor') AS attractors,
                       count(*) FILTER (WHERE role = 'competitor' AND business_type = ?) AS competitors
                FROM (
                    SELECT ST_SnapToGrid(geom, 0.002) AS cell, role, business_type
                    FROM poi
                    WHERE geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)
                ) AS snapped
                GROUP BY cell
                ORDER BY lat, lon
                """,
                (rs, row) -> new HeatCell(
                        rs.getDouble("lat"),
                        rs.getDouble("lon"),
                        rs.getInt("attractors"),
                        rs.getInt("competitors")),
                businessType,
                west, south, east, north);
    }
}
