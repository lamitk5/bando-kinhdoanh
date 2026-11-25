package vn.bando.spatial.backtest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import vn.bando.spatial.OsmRefs;
import vn.bando.spatial.score.ScoringService;

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

    public ObjectNode run() {
        JsonNode scale = scoring.referenceNode();
        List<Observed> named = namedShops();
        List<Observed> grid = gridPoints();
        ObjectNode body = mapper.createObjectNode();
        body.put("limitation", "Bảng poi đã nạp không có thẻ disused. File vietnam-latest.osm.pbf mốc 2026-11-15T21:22:28Z chỉ có 3 điểm tiện lợi từng ngừng trong bốn quận, không có tên. Mẫu đang chấm là các điểm trong sourced_places: lời người vẽ khi xóa, Circle K còn trong danh sách hãng cả hai mốc, và Circle K 74 Nguyễn Khang được đặt bằng nội suy giữa số 78 và số 80 đã vẽ trên cùng phố. Đây không phải khảo sát 15 cửa hàng đã đóng tại chỗ.");
        body.put("training_rejection", "Đối chiếu vietnam-210101.osm.pbf mốc 2021-01-01T21:42:03Z với bản 2026-11-15 có hàng trăm node ngành biến mất trong bốn quận. Lời changeset khi xóa phần lớn là Adding POIs của Grab hoặc cập nhật cửa hàng WinMart+. Tập đó không được dùng để huấn luyện và không được gọi là cửa hàng đã đóng.");
        body.put("question", "Nhóm không còn và nhóm Circle K còn trong danh sách hãng có khoảng điểm chồng nhau hay không.");
        body.put("shop_rule", "Mọi cơ sở đối thủ có tên. Điểm đã trừ chính cơ sở khỏi vòng 500 m.");
        body.put("grid_rule", "Mỗi quận 15 điểm ST_GeneratePoints nằm trong ranh, seed 1. Cùng lưới được chấm cho cả ba ngành.");
        body.put("survey_rule", "Nhóm check_date là cơ sở có thẻ ngày người vẽ ghi đã xem. Thẻ được giữ nguyên, không suy ra tuổi cửa hàng.");
        body.put("quartile_rule", "Mốc một phần tư là điểm tại vị trí floor(tỉ lệ * (số điểm - 1)) sau khi sắp xếp, rồi làm tròn một chữ số.");
        body.put("rent_vnd_per_month", RENT);
        body.put("rent_note", "Mức thuê này chỉ để gọi cùng một công thức. Không phải giá thuê quan sát tại từng điểm.");
        body.put("self_count_note", "Điểm công khai tính cả chính cơ sở vào mật độ đối thủ. Trung vị cơ sở ở dưới dùng điểm đã trừ một đối thủ, vì điểm đó nằm tại tâm vòng 500 m.");
        var businesses = body.putArray("businesses");
        for (String business : new String[] {"cafe_drink", "pharmacy", "convenience"}) {
            List<Observed> own = named.stream().filter(place -> business.equals(place.businessType())).toList();
            List<Observed> ownGrid = grid.stream().filter(place -> business.equals(place.businessType())).toList();
            List<Double> shopScores = new ArrayList<>();
            List<Double> gridScores = new ArrayList<>();
            Observed traced = null;
            ObjectNode block = businesses.addObject();
            block.put("business_type", business);
            var surveyed = block.putArray("surveyed");
            for (Observed place : own) {
                JsonNode indicators = read(place.indicators());
                double withSelf = scoring.score(business, indicators, scale, false);
                double withoutSelf = scoring.score(business, indicators, scale, true);
                shopScores.add(withoutSelf);
                if (traced == null || place.osmId() < traced.osmId()) {
                    traced = place;
                    block.set("traced", tracedNode(place, withSelf, withoutSelf));
                }
                if (place.checkDate() != null) {
                    ObjectNode row = surveyed.addObject();
                    row.put("district", place.district());
                    row.put("name", place.name());
                    row.put("check_date", place.checkDate());
                    row.put("osm_url", OsmRefs.url(place.osmType(), place.osmId()));
                    row.put("spatial_score", withSelf);
                    row.put("spatial_score_without_self", withoutSelf);
                }
            }
            for (Observed place : ownGrid) {
                gridScores.add(scoring.score(business, read(place.indicators()), scale, false));
            }
            block.put("named_count", shopScores.size());
            block.put("surveyed_count", surveyed.size());
            block.put("grid_count", gridScores.size());
            block.put("named_median_spatial_score_without_self", median(shopScores));
            block.put("named_lower_quartile", orderStatistic(shopScores, 0.25));
            block.put("named_upper_quartile", orderStatistic(shopScores, 0.75));
            block.put("grid_median_spatial_score", median(gridScores));
            var shopArray = block.putArray("named_scores_without_self");
            shopScores.forEach(shopArray::add);
            var gridArray = block.putArray("grid_scores");
            gridScores.forEach(gridArray::add);
        }
        former(body);
        sourced(body);
        unlocated(body);
        benchmarks(body);
        return body;
    }

    private ObjectNode tracedNode(Observed place, double withSelf, double withoutSelf) {
        ObjectNode node = mapper.createObjectNode();
        node.put("district", place.district());
        node.put("name", place.name());
        node.put("osm_url", OsmRefs.url(place.osmType(), place.osmId()));
        node.put("lat", place.lat());
        node.put("lon", place.lon());
        node.put("spatial_score", withSelf);
        node.put("spatial_score_without_self", withoutSelf);
        return node;
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("JSON chỉ số không đọc được", exception);
        }
    }

    private void former(ObjectNode body) {
        JsonNode catalog;
        try (var stream = BacktestService.class.getResourceAsStream("/backtest/former_convenience.json")) {
            if (stream == null) {
                throw new IllegalStateException("Thiếu danh sách điểm từng mang thẻ tiện lợi");
            }
            catalog = mapper.readTree(stream);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Không đọc được danh sách điểm từng mang thẻ tiện lợi", exception);
        }
        body.put("former_source", "vietnam-latest.osm.pbf mốc 2026-11-15T21:22:28Z");
        var rows = body.putArray("former_places");
        for (JsonNode place : catalog) {
            double lat = place.path("lat").asDouble();
            double lon = place.path("lon").asDouble();
            String district = districtCovering(lon, lat);
            ObjectNode row = rows.addObject();
            row.put("osm_url", OsmRefs.url(place.path("osm_type").asText(), place.path("osm_id").asLong()));
            row.put("lifecycle_key", place.path("lifecycle_key").asText());
            row.put("lifecycle_value", place.path("lifecycle_value").asText());
            row.put("business_type", place.path("business_type").asText());
            row.put("lat", lat);
            row.put("lon", lon);
            row.putNull("name");
            if (district == null) {
                row.put("inside_study", false);
                row.putNull("district");
                row.putNull("spatial_score");
                continue;
            }
            ObjectNode request = mapper.createObjectNode();
            request.put("lat", lat);
            request.put("lon", lon);
            request.put("business_type", place.path("business_type").asText());
            request.put("rent_vnd_per_month", RENT);
            ObjectNode scored = scoring.evaluate(request);
            row.put("inside_study", true);
            row.put("district", district);
            row.put("spatial_score", scored.path("spatial_score").asDouble());
            row.put("included_as_competitor", false);
        }
    }

    private void sourced(ObjectNode body) {
        JsonNode catalog = readResource("/backtest/sourced_places.json", "Thiếu danh sách điểm có nguồn");
        JsonNode scale = scoring.referenceNode();
        var rows = body.putArray("sourced_places");
        var absent = new ArrayList<Double>();
        var listed = new ArrayList<Double>();
        var closed = new ArrayList<Double>();
        for (JsonNode place : catalog) {
            double lat = place.path("lat").asDouble();
            double lon = place.path("lon").asDouble();
            String business = place.path("business_type").asText();
            ObjectNode request = mapper.createObjectNode();
            request.put("lat", lat);
            request.put("lon", lon);
            request.put("business_type", business);
            request.put("rent_vnd_per_month", RENT);
            ObjectNode scored = scoring.evaluate(request);
            boolean removeSelf = place.path("remove_self").asBoolean();
            double compare = removeSelf
                    ? scoring.score(business, scored.path("indicators"), scale, true)
                    : scored.path("spatial_score").asDouble();
            ObjectNode row = rows.addObject();
            row.put("name", place.path("name").asText());
            row.put("business_type", business);
            row.put("district", place.path("district").asText());
            row.put("outcome", place.path("outcome").asText());
            row.put("basis", place.path("basis").asText());
            row.put("source_url", place.path("source_url").asText());
            if (place.path("osm_id").asLong() > 0) {
                row.put("osm_url", OsmRefs.url(place.path("osm_type").asText(), place.path("osm_id").asLong()));
            } else {
                row.putNull("osm_url");
            }
            if (place.path("coordinate_method").asText().isBlank()) {
                row.putNull("coordinate_method");
            } else {
                row.put("coordinate_method", place.path("coordinate_method").asText());
            }
            row.put("lat", scored.path("lat").asDouble());
            row.put("lon", scored.path("lon").asDouble());
            row.put("spatial_score", scored.path("spatial_score").asDouble());
            row.put("spatial_score_for_compare", compare);
            String outcome = place.path("outcome").asText();
            if ("mapper_absent".equals(outcome)) {
                absent.add(compare);
                closed.add(compare);
            } else if ("reported_closed".equals(outcome)) {
                closed.add(compare);
            } else if ("brand_listed".equals(outcome)) {
                listed.add(compare);
            }
        }
        body.put("sourced_note", "mapper_absent là lời người vẽ khi xóa điểm. reported_closed là cửa hàng có bài báo, tọa độ nội suy giữa hai số nhà đã vẽ trên cùng phố. brand_listed là Circle K còn trong danh sách hãng ở cả hai mốc 2024-08-05 và 2026-07-10 và khớp số nhà trên bản đồ.");
        body.put("mapper_absent_count", absent.size());
        body.put("mapper_absent_median_spatial_score", median(absent));
        body.put("reported_closed_count", closed.size() - absent.size());
        body.put("closed_group_count", closed.size());
        body.put("closed_group_median_spatial_score", median(closed));
        body.put("brand_listed_count", listed.size());
        body.put("brand_listed_median_spatial_score", median(listed));
        body.put("sourced_panel_count", rows.size());
        boolean overlap = !closed.isEmpty() && !listed.isEmpty()
                && java.util.Collections.min(closed) <= java.util.Collections.max(listed)
                && java.util.Collections.min(listed) <= java.util.Collections.max(closed);
        body.put("score_ranges_overlap", overlap);
        body.put("panel_finding", overlap
                ? "Khoảng điểm của nhóm không còn và nhóm còn trong danh sách hãng chồng lên nhau. Mẫu này không tách được hai nhóm bằng một ngưỡng điểm."
                : "Khoảng điểm của nhóm không còn và nhóm còn trong danh sách hãng không chồng lên nhau.");
    }

    private void unlocated(ObjectNode body) {
        JsonNode catalog = readResource("/backtest/unlocated_closures.json", "Thiếu danh sách cửa hàng có nguồn nhưng không có tọa độ");
        var rows = body.putArray("unlocated_closures");
        for (JsonNode place : catalog) {
            ObjectNode row = rows.addObject();
            row.put("name", place.path("name").asText());
            row.put("business_type", place.path("business_type").asText());
            row.put("district", place.path("district").asText());
            row.put("address", place.path("address").asText());
            row.put("outcome", place.path("outcome").asText());
            row.put("inside_study", place.path("inside_study").asBoolean(false));
            putOptional(row, "closed_on", place.path("closed_on").asText());
            putOptional(row, "article_date", place.path("article_date").asText());
            row.put("basis", place.path("basis").asText());
            row.put("source_url", place.path("source_url").asText());
            row.put("reason_unscored", place.path("reason_unscored").asText());
            row.putNull("lat");
            row.putNull("lon");
            row.putNull("spatial_score");
        }
        body.put("unlocated_note", "VnExpress ngày 8/8/2024 gọi tên bốn chi nhánh The Coffee House theo phố, không có số nhà và không có ngày đóng, nên không chấm điểm. Chỉ phố Nguyễn Tuân nằm trong bốn quận. Circle K 74 Nguyễn Khang đã được đặt bằng nội suy giữa số 78 và số 80.");
        body.put("unlocated_in_study_count", countInStudy(catalog));
    }

    private void benchmarks(ObjectNode body) {
        JsonNode scale = scoring.referenceNode();
        var circleLists = circleListSnapshots();
        var places = body.putArray("benchmarks");
        var byBrand = new java.util.LinkedHashMap<String, List<Double>>();
        for (Addressed place : addressedBrands()) {
            JsonNode indicators = read(place.indicators());
            double withoutSelf = scoring.score(place.businessType(), indicators, scale, true);
            ObjectNode row = places.addObject();
            row.put("district", place.district());
            row.put("name", place.name());
            row.put("brand", place.brand());
            row.put("business_type", place.businessType());
            row.put("housenumber", place.housenumber());
            row.put("street", place.street());
            putOptional(row, "opening_hours", place.openingHours());
            putOptional(row, "phone", place.phone());
            putOptional(row, "website", place.website());
            putOptional(row, "check_date", place.checkDate());
            putOptional(row, "start_date", place.startDate());
            row.put("osm_url", OsmRefs.url(place.osmType(), place.osmId()));
            row.put("lat", place.lat());
            row.put("lon", place.lon());
            row.put("attractor_density_per_km2", indicators.path("attractor_density_per_km2").asDouble());
            row.put("competitor_count", indicators.path("competitor_count").asInt());
            row.put("arterial_distance_m", indicators.path("arterial_distance_m").asDouble());
            row.put("spatial_score_without_self", withoutSelf);
            if ("circle k".equals(fold(place.brand()))) {
                row.put("circle_k_list", circleLists.getOrDefault(circleKey(place.district(), place.housenumber(), place.street()), "not_in_saved_lists"));
            } else {
                row.putNull("circle_k_list");
            }
            byBrand.computeIfAbsent(place.brand() + "\n" + place.businessType(), key -> new ArrayList<>()).add(withoutSelf);
        }
        var brands = body.putArray("benchmark_brands");
        byBrand.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, List<Double>>comparingByValue(java.util.Comparator.comparingInt(List::size)).reversed())
                .forEach(entry -> {
                    String[] parts = entry.getKey().split("\n", 2);
                    ObjectNode brand = brands.addObject();
                    brand.put("brand", parts[0]);
                    brand.put("business_type", parts[1]);
                    brand.put("count", entry.getValue().size());
                    brand.put("median_spatial_score_without_self", median(entry.getValue()));
                });
        body.put("benchmark_count", places.size());
        body.put("benchmark_note", "Mỗi dòng là một cơ sở đối thủ có brand, số nhà và tên đường trên OpenStreetMap. Giờ mở cửa, điện thoại, website, check_date và start_date chỉ xuất hiện khi thẻ đó có trong dữ liệu; không có thì để null. circle_k_list đối chiếu số nhà và tên đường với trang Circle K lưu ngày 2024-08-05 và 2026-07-10. Không bịa thông số còn thiếu.");
    }

    private static int countInStudy(JsonNode catalog) {
        int count = 0;
        for (JsonNode place : catalog) {
            if (place.path("inside_study").asBoolean(false)) {
                count++;
            }
        }
        return count;
    }

    private static void putOptional(ObjectNode row, String field, String value) {
        if (value == null || value.isBlank()) {
            row.putNull(field);
        } else {
            row.put(field, value);
        }
    }

    private java.util.Map<String, String> circleListSnapshots() {
        JsonNode locator = readResource("/backtest/circle_k_locator.json", "Thiếu danh sách Circle K đã lưu");
        var snapshots = new java.util.LinkedHashMap<String, java.util.LinkedHashSet<String>>();
        for (JsonNode row : locator) {
            String key = circleKey(row.path("district").asText(), row.path("number").asText(), row.path("street_key").asText());
            if (key.startsWith("\n") || key.endsWith("\n") || key.contains("\n\n")) {
                continue;
            }
            snapshots.computeIfAbsent(key, ignored -> new java.util.LinkedHashSet<>()).add(row.path("snapshot").asText());
        }
        var status = new java.util.LinkedHashMap<String, String>();
        snapshots.forEach((key, found) -> {
            boolean older = found.contains("2024-08-05");
            boolean newer = found.contains("2026-07-10");
            status.put(key, older && newer ? "listed_both" : newer ? "listed_2026_only" : older ? "listed_2024_only" : "not_in_saved_lists");
        });
        return status;
    }

    static String circleKey(String district, String housenumber, String street) {
        return district + "\n" + numberKey(housenumber) + "\n" + streetKey(street);
    }

    static String numberKey(String value) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(fold(value));
        if (!matcher.find()) {
            return "";
        }
        return Integer.toString(Integer.parseInt(matcher.group()));
    }

    static String streetKey(String value) {
        String folded = fold(value).replaceAll("\\b(duong|pho|ngo|hem|dai lo)\\b", " ");
        folded = folded.replaceAll("\\d+", " ");
        return folded.replaceAll("[^a-z]+", " ").trim().replaceAll(" +", " ");
    }

    static String fold(String value) {
        String source = value == null ? "" : value;
        String normalized = java.text.Normalizer.normalize(source, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(java.util.Locale.ROOT)
                .replace('đ', 'd');
        return normalized;
    }

    private List<Addressed> addressedBrands() {
        return jdbc.query(
                """
                SELECT d.name AS district, p.business_type, p.name, p.osm_type, p.osm_id,
                       btrim(p.tags ->> 'brand') AS brand,
                       btrim(p.tags ->> 'addr:housenumber') AS housenumber,
                       btrim(p.tags ->> 'addr:street') AS street,
                       nullif(btrim(p.tags ->> 'opening_hours'), '') AS opening_hours,
                       nullif(btrim(coalesce(p.tags ->> 'phone', p.tags ->> 'contact:phone')), '') AS phone,
                       nullif(btrim(coalesce(p.tags ->> 'website', p.tags ->> 'contact:website')), '') AS website,
                       nullif(btrim(p.tags ->> 'check_date'), '') AS check_date,
                       nullif(btrim(p.tags ->> 'start_date'), '') AS start_date,
                       round(ST_Y(p.geom)::numeric, 6)::float8 AS lat,
                       round(ST_X(p.geom)::numeric, 6)::float8 AS lon,
                       location_indicators(
                           round(ST_X(p.geom)::numeric, 6)::float8,
                           round(ST_Y(p.geom)::numeric, 6)::float8,
                           p.business_type
                       )::text AS indicators
                FROM poi AS p
                JOIN district AS d ON d.id = p.district_id
                WHERE p.role = 'competitor'
                  AND nullif(btrim(p.tags ->> 'brand'), '') IS NOT NULL
                  AND nullif(btrim(p.tags ->> 'addr:housenumber'), '') IS NOT NULL
                  AND nullif(btrim(p.tags ->> 'addr:street'), '') IS NOT NULL
                ORDER BY p.tags ->> 'brand', d.name, p.osm_id
                """,
                (rs, row) -> new Addressed(
                        rs.getString("district"),
                        rs.getString("business_type"),
                        rs.getString("name"),
                        rs.getString("osm_type"),
                        rs.getLong("osm_id"),
                        rs.getString("brand"),
                        rs.getString("housenumber"),
                        rs.getString("street"),
                        rs.getString("opening_hours"),
                        rs.getString("phone"),
                        rs.getString("website"),
                        rs.getString("check_date"),
                        rs.getString("start_date"),
                        rs.getDouble("lat"),
                        rs.getDouble("lon"),
                        rs.getString("indicators")));
    }

    private record Addressed(
            String district,
            String businessType,
            String name,
            String osmType,
            long osmId,
            String brand,
            String housenumber,
            String street,
            String openingHours,
            String phone,
            String website,
            String checkDate,
            String startDate,
            double lat,
            double lon,
            String indicators) {
    }

    private JsonNode readResource(String path, String missing) {
        try (var stream = BacktestService.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException(missing);
            }
            return mapper.readTree(stream);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(missing, exception);
        }
    }

    private String districtCovering(double lon, double lat) {
        java.util.List<String> names = jdbc.query(
                """
                SELECT name
                FROM district
                WHERE ST_Covers(geom, ST_SetSRID(ST_MakePoint(?, ?), 4326))
                ORDER BY name
                """,
                (rs, row) -> rs.getString("name"),
                lon,
                lat);
        return names.size() == 1 ? names.get(0) : null;
    }

    private record Observed(
            String district,
            String businessType,
            String name,
            String osmType,
            long osmId,
            double lat,
            double lon,
            String checkDate,
            String indicators) {
    }

    private List<Observed> namedShops() {
        return jdbc.query(
                """
                SELECT d.name AS district, p.business_type, p.name, p.osm_type, p.osm_id,
                       round(ST_Y(p.geom)::numeric, 6)::float8 AS lat,
                       round(ST_X(p.geom)::numeric, 6)::float8 AS lon,
                       nullif(btrim(p.tags ->> 'check_date'), '') AS check_date,
                       location_indicators(
                           round(ST_X(p.geom)::numeric, 6)::float8,
                           round(ST_Y(p.geom)::numeric, 6)::float8,
                           p.business_type
                       )::text AS indicators
                FROM poi AS p
                JOIN district AS d ON d.id = p.district_id
                WHERE p.role = 'competitor'
                  AND p.name IS NOT NULL
                  AND length(btrim(p.name)) > 0
                ORDER BY p.business_type, p.osm_id
                """,
                (rs, row) -> new Observed(
                        rs.getString("district"),
                        rs.getString("business_type"),
                        rs.getString("name"),
                        rs.getString("osm_type"),
                        rs.getLong("osm_id"),
                        rs.getDouble("lat"),
                        rs.getDouble("lon"),
                        rs.getString("check_date"),
                        rs.getString("indicators")));
    }

    private List<Observed> gridPoints() {
        return jdbc.query(
                """
                SELECT d.name AS district, b.business AS business_type,
                       location_indicators(
                           ST_X((point).geom),
                           ST_Y((point).geom),
                           b.business
                       )::text AS indicators
                FROM district AS d
                CROSS JOIN LATERAL ST_Dump(ST_GeneratePoints(d.geom, 15, 1)) AS point
                CROSS JOIN (VALUES ('cafe_drink'), ('pharmacy'), ('convenience')) AS b(business)
                ORDER BY b.business, d.name
                """,
                (rs, row) -> new Observed(
                        rs.getString("district"),
                        rs.getString("business_type"),
                        null,
                        null,
                        0,
                        0,
                        0,
                        null,
                        rs.getString("indicators")));
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

    public static double orderStatistic(List<Double> values, double fraction) {
        List<Double> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        if (sorted.isEmpty()) {
            return Double.NaN;
        }
        int index = (int) Math.floor(fraction * (sorted.size() - 1));
        return Math.round(sorted.get(index) * 10.0) / 10.0;
    }
}
