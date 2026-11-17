package vn.bando.spatial.backtest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import vn.bando.spatial.OsmRefs;
import vn.bando.spatial.score.Ahp;
import vn.bando.spatial.score.ScoreMath;
import vn.bando.spatial.score.ScoringService;
import vn.bando.spatial.score.WorkingJudgments;

@Service
public class BacktestService {

    static final long RENT = 25_000_000L;

    private final JdbcTemplate jdbc;
    private final ScoringService scoring;
    private final ObjectMapper mapper;

    public BacktestService(JdbcTemplate jdbc, ScoringService scoring, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.scoring = scoring;
        this.mapper = mapper;
    }

    public record Place(String district, String businessType, String name, String osmType, Long osmId, double lat, double lon) {
    }

    public ObjectNode run() {
        List<Place> shops = shops();
        List<Place> grid = grid();
        ObjectNode body = mapper.createObjectNode();
        body.put("limitation", "Bản đồ đã nạp có 0 thẻ disused và 0 thẻ abandoned, nên không tách được cửa hàng đã đóng cửa hay sang nhượng.");
        body.put("question", "Cơ sở đang có trên OpenStreetMap có điểm không gian khác các điểm lưới trong cùng bốn quận hay không. Đây không phải đối chiếu cửa hàng còn sống với cửa hàng đã đóng.");
        body.put("shop_rule", "Mỗi quận và mỗi ngành lấy một cơ sở có tên, chọn osm_id nhỏ nhất.");
        body.put("grid_rule", "Mỗi quận lấy 3 điểm ST_GeneratePoints nằm trong ranh, seed 1.");
        body.put("rent_vnd_per_month", RENT);
        body.put("rent_note", "Mức thuê này chỉ để gọi cùng một công thức. Không phải giá thuê quan sát tại từng điểm.");
        body.put("self_count_note", "Điểm công khai tính cả chính cơ sở vào mật độ đối thủ. Trung vị cơ sở ở dưới dùng điểm đã trừ một đối thủ, vì điểm đó nằm tại tâm vòng 500 m.");
        var businesses = body.putArray("businesses");
        for (String business : new String[] {"cafe_drink", "pharmacy", "convenience"}) {
            ObjectNode block = businesses.addObject();
            block.put("business_type", business);
            List<Place> ownShops = shops.stream().filter(place -> business.equals(place.businessType())).toList();
            List<Double> shopScores = fill(block.putArray("shops"), ownShops, business, true);
            List<Double> gridScores = fill(block.putArray("grid"), grid, business, false);
            block.put("shop_count", shopScores.size());
            block.put("grid_count", gridScores.size());
            block.put("shop_median_spatial_score_without_self", median(shopScores));
            block.put("grid_median_spatial_score", median(gridScores));
        }
        return body;
    }

    private List<Double> fill(
            com.fasterxml.jackson.databind.node.ArrayNode array,
            List<Place> places,
            String business,
            boolean removeSelf) {
        List<Double> scores = new ArrayList<>();
        double[] weights = Ahp.solve3(WorkingJudgments.spatialMatrix(business)).weights();
        for (Place place : places) {
            ObjectNode request = mapper.createObjectNode();
            request.put("lat", place.lat());
            request.put("lon", place.lon());
            request.put("business_type", business);
            request.put("rent_vnd_per_month", RENT);
            ObjectNode scored = scoring.evaluate(request);
            double spatial = scored.path("spatial_score").asDouble();
            ObjectNode row = array.addObject();
            row.put("district", place.district());
            if (place.name() == null) {
                row.putNull("name");
            } else {
                row.put("name", place.name());
            }
            row.put("lat", scored.path("lat").asDouble());
            row.put("lon", scored.path("lon").asDouble());
            row.put("spatial_score", spatial);
            if (removeSelf) {
                double withoutSelf = withoutSelf(scored, weights);
                row.put("spatial_score_without_self", withoutSelf);
                scores.add(withoutSelf);
            } else {
                scores.add(spatial);
            }
            if (place.osmId() != null) {
                row.put("osm_url", OsmRefs.url(place.osmType(), place.osmId()));
            } else {
                row.putNull("osm_url");
            }
        }
        return scores;
    }

    private static double withoutSelf(ObjectNode scored, double[] weights) {
        var indicators = scored.path("indicators");
        var components = scored.path("components");
        int competitors = indicators.path("competitor_count").asInt();
        double area = indicators.path("searched_area_m2").asDouble();
        double density = Math.max(0, competitors - 1) / (area / 1_000_000.0);
        double attractor = ScoreMath.higherIsBetter(
                indicators.path("attractor_density_per_km2").asDouble(),
                components.path("attractor_density").path("scale").asDouble());
        double saturation = ScoreMath.saturation(density, components.path("competitor_saturation").path("scale").asDouble());
        double access = ScoreMath.access(
                indicators.path("arterial_distance_m").asDouble(),
                components.path("arterial_access").path("scale").asDouble());
        return Math.round(ScoreMath.weighted(weights, attractor, saturation, access) * 10.0) / 10.0;
    }

    private List<Place> shops() {
        return jdbc.query(
                """
                SELECT DISTINCT ON (d.name, p.business_type)
                       d.name AS district, p.business_type, p.name, p.osm_type, p.osm_id,
                       ST_Y(p.geom) AS lat, ST_X(p.geom) AS lon
                FROM poi AS p
                JOIN district AS d ON d.id = p.district_id
                WHERE p.role = 'competitor'
                  AND p.name IS NOT NULL
                  AND length(btrim(p.name)) > 0
                ORDER BY d.name, p.business_type, p.osm_id
                """,
                (rs, row) -> new Place(
                        rs.getString("district"),
                        rs.getString("business_type"),
                        rs.getString("name"),
                        rs.getString("osm_type"),
                        rs.getLong("osm_id"),
                        rs.getDouble("lat"),
                        rs.getDouble("lon")));
    }

    private List<Place> grid() {
        return jdbc.query(
                """
                SELECT d.name AS district, ST_Y((point).geom) AS lat, ST_X((point).geom) AS lon
                FROM district AS d
                CROSS JOIN LATERAL ST_Dump(ST_GeneratePoints(d.geom, 3, 1)) AS point
                ORDER BY d.name, lat, lon
                """,
                (rs, row) -> new Place(
                        rs.getString("district"),
                        null,
                        null,
                        null,
                        null,
                        rs.getDouble("lat"),
                        rs.getDouble("lon")));
    }

    public static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int size = sorted.size();
        if (size == 0) {
            return Double.NaN;
        }
        double middle = size % 2 == 1
                ? sorted.get(size / 2)
                : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
        return Math.round(middle * 10.0) / 10.0;
    }
}
