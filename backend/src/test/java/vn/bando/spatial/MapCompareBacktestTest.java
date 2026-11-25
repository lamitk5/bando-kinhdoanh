package vn.bando.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import vn.bando.spatial.backtest.BacktestService;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "vision.worker.enabled=false")
class MapCompareBacktestTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void mapShowsKafaAsACafeCompetitorAndRejectsAHugeFrame() throws Exception {
        JsonNode points = get("/api/map/points?west=105.79&south=21.03&east=105.81&north=21.05&business_type=cafe_drink");
        boolean found = false;
        for (JsonNode feature : points.path("features")) {
            if ("https://www.openstreetmap.org/node/423736651".equals(feature.path("osm_url").asText())) {
                found = true;
                assertEquals("competitor", feature.path("role").asText());
                assertEquals("Cà Phê Kafa", feature.path("name").asText());
            }
        }
        assertTrue(found);
        JsonNode heat = get("/api/map/heatmap?west=105.79&south=21.03&east=105.81&north=21.05&business_type=cafe_drink");
        assertEquals(0.002, heat.path("cell_degrees").asDouble());
        assertTrue(heat.path("cells").size() > 0);
        assertEquals(400, rest.getForEntity("/api/map/points?west=105&south=20&east=106&north=22&business_type=cafe_drink", String.class).getStatusCode().value());
        JsonNode districts = get("/api/map/districts");
        assertEquals(4, districts.path("features").size());
        ResponseEntity<String> home = rest.getForEntity("/", String.class);
        assertEquals(200, home.getStatusCode().value());
        assertTrue(home.getBody().contains("Còn mặt bằng trên bản đồ"));
    }

    @Test
    void compareMatchesEvaluateAndThePdfContainsTheScore() throws Exception {
        String body = """
                {"business_type":"cafe_drink","rent_vnd_per_month":25000000,"sites":[
                  {"label":"Kafa","lat":21.0378611,"lon":105.8010007},
                  {"label":"AnAn","lat":21.044636,"lon":105.802737}
                ]}
                """;
        JsonNode compared = postJson("/api/scoring/compare", body);
        assertEquals(2, compared.path("sites").size());
        JsonNode alone = postJson("/api/scoring/evaluate", """
                {"lat":21.0378611,"lon":105.8010007,"business_type":"cafe_drink","rent_vnd_per_month":25000000}
                """);
        assertEquals(alone.path("spatial_score").asDouble(), compared.path("sites").get(0).path("spatial_score").asDouble());
        byte[] pdf = postBytes("/api/scoring/compare.pdf", body);
        assertEquals('%', (char) pdf[0]);
        assertEquals('P', (char) pdf[1]);
        try (var document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("Kafa"));
            assertTrue(text.contains(String.valueOf(compared.path("sites").get(0).path("spatial_score").asDouble())));
        }
        assertEquals(400, postRaw("/api/scoring/compare", """
                {"business_type":"cafe_drink","rent_vnd_per_month":25000000,"sites":[{"label":"Một","lat":21.03,"lon":105.80}]}
                """).getStatusCode().value());
        ResponseEntity<String> outside = postRaw("/api/scoring/compare", """
                {"business_type":"cafe_drink","rent_vnd_per_month":25000000,"sites":[
                  {"label":"Trong","lat":21.037861,"lon":105.801001},
                  {"label":"Ngoài","lat":21.028,"lon":105.900}
                ]}
                """);
        assertEquals(400, outside.getStatusCode().value());
        assertTrue(outside.getBody().contains("bốn quận"));
        assertTrue(!outside.getBody().contains("PL/pgSQL"));
    }

    @Test
    void backtestUsesTwelveNamedShopsAndDoesNotClaimClosures() throws Exception {
        JsonNode body = get("/api/backtest");
        assertTrue(body.path("limitation").asText().contains("không phải khảo sát 15 cửa hàng đã đóng tại chỗ"));
        assertTrue(body.path("training_rejection").asText().contains("Grab"));
        assertEquals(5, body.path("mapper_absent_count").asInt());
        assertEquals(5, body.path("brand_listed_count").asInt());
        boolean twitterbeans = false;
        boolean circleK = false;
        boolean chuaLang = false;
        for (JsonNode place : body.path("sourced_places")) {
            assertTrue(place.path("source_url").asText().startsWith("https://"));
            assertTrue(place.path("spatial_score_for_compare").isNumber());
            if (place.path("osm_url").asText().endsWith("/3440274560")) {
                twitterbeans = true;
                assertEquals("mapper_absent", place.path("outcome").asText());
                assertEquals("Đống Đa", place.path("district").asText());
                JsonNode alone = postJson("/api/scoring/evaluate", """
                        {"lat":%s,"lon":%s,"business_type":"cafe_drink","rent_vnd_per_month":25000000}
                        """.formatted(place.path("lat").asDouble(), place.path("lon").asDouble()));
                assertEquals(alone.path("spatial_score").asDouble(), place.path("spatial_score").asDouble());
            }
            if (place.path("osm_url").asText().endsWith("/way/1046053646")) {
                chuaLang = true;
                assertEquals("brand_listed", place.path("outcome").asText());
            }
            if (place.path("osm_url").asText().endsWith("/6688293360")) {
                circleK = true;
                assertEquals("brand_listed", place.path("outcome").asText());
                assertEquals("Circle K", place.path("name").asText());
            }
        }
        assertTrue(twitterbeans);
        assertTrue(circleK);
        assertTrue(chuaLang);
        assertTrue(body.path("benchmark_count").asInt() > 100);
        assertEquals(body.path("benchmark_count").asInt(), body.path("benchmarks").size());
        assertTrue(body.path("benchmark_note").asText().contains("Không bịa"));
        boolean addressedCircle = false;
        boolean hoursPassedThrough = false;
        var winmart = new ArrayList<Double>();
        for (JsonNode place : body.path("benchmarks")) {
            assertFalse(place.path("housenumber").asText().isBlank());
            assertFalse(place.path("street").asText().isBlank());
            assertFalse(place.path("brand").asText().isBlank());
            if (!place.path("opening_hours").isNull()) {
                hoursPassedThrough = true;
            }
            if (place.path("osm_url").asText().endsWith("/6688293360")) {
                addressedCircle = true;
                assertEquals("370", place.path("housenumber").asText());
                assertEquals("listed_both", place.path("circle_k_list").asText());
                assertEquals("24/7", place.path("opening_hours").asText());
            }
            if ("WinMart+".equals(place.path("brand").asText())) {
                winmart.add(place.path("spatial_score_without_self").asDouble());
            }
        }
        assertTrue(addressedCircle);
        assertTrue(hoursPassedThrough);
        for (JsonNode brand : body.path("benchmark_brands")) {
            if ("WinMart+".equals(brand.path("brand").asText()) && "convenience".equals(brand.path("business_type").asText())) {
                assertEquals(BacktestService.median(winmart), brand.path("median_spatial_score_without_self").asDouble(), 1e-9);
            }
        }
        assertEquals(body.path("open_premises_count").asInt(), body.path("open_premises").size());
        assertTrue(body.path("open_premises_count").asInt() >= body.path("benchmark_count").asInt());
        assertTrue(body.path("open_premises_note").asText().contains("không phải khảo sát cửa còn mở"));
        assertTrue(body.path("open_premises_note").asText().contains("không bịa tọa độ"));
        int brandedPremises = 0;
        int stillListed = 0;
        boolean premisesCircle = false;
        var typeCounts = new HashMap<String, Integer>();
        var typeScores = new HashMap<String, List<Double>>();
        for (JsonNode place : body.path("open_premises")) {
            assertFalse(place.path("housenumber").asText().isBlank());
            assertFalse(place.path("street").asText().isBlank());
            assertTrue(place.path("spatial_score_without_self").isNumber());
            assertTrue(place.path("osm_url").asText().startsWith("https://www.openstreetmap.org/"));
            typeCounts.merge(place.path("business_type").asText(), 1, Integer::sum);
            typeScores.computeIfAbsent(place.path("business_type").asText(), key -> new ArrayList<>())
                    .add(place.path("spatial_score_without_self").asDouble());
            if (!place.path("brand").isNull() && !place.path("brand").asText().isBlank()) {
                brandedPremises++;
            }
            if (place.path("circle_k_list").isNull()) {
                assertTrue(place.path("still_on_brand_list").isNull());
            } else if (place.path("still_on_brand_list").asBoolean()) {
                stillListed++;
                assertTrue(place.path("circle_k_list").asText().equals("listed_both")
                        || place.path("circle_k_list").asText().equals("listed_2026_only"));
            }
            if (place.path("osm_url").asText().endsWith("/6688293360")) {
                premisesCircle = true;
                assertEquals("listed_both", place.path("circle_k_list").asText());
                assertTrue(place.path("still_on_brand_list").asBoolean());
                assertEquals("370", place.path("housenumber").asText());
                assertEquals("Circle K", place.path("name").asText());
            }
        }
        assertTrue(premisesCircle);
        assertEquals(body.path("benchmark_count").asInt(), brandedPremises);
        assertEquals(stillListed, body.path("open_premises_still_on_brand_list_count").asInt());
        assertTrue(stillListed >= 5);
        for (JsonNode type : body.path("open_premises_by_type")) {
            String business = type.path("business_type").asText();
            int count = typeCounts.getOrDefault(business, 0);
            assertEquals(count, type.path("count").asInt());
            if (count > 0) {
                assertEquals(
                        BacktestService.median(typeScores.get(business)),
                        type.path("median_spatial_score_without_self").asDouble(),
                        1e-9);
            }
        }
        assertTrue(body.path("question").asText().contains("khoảng điểm chồng nhau"));
        assertEquals(1, body.path("unlocated_closures").size());
        assertEquals(1, body.path("unlocated_in_study_count").asInt());
        assertTrue(body.path("unlocated_note").asText().contains("An Khang"));
        JsonNode anKhang = body.path("unlocated_closures").get(0);
        assertEquals("Nhà thuốc An Khang", anKhang.path("name").asText());
        assertEquals("pharmacy", anKhang.path("business_type").asText());
        assertEquals("2025-05-19", anKhang.path("article_date").asText());
        assertTrue(anKhang.path("inside_study").asBoolean());
        assertTrue(anKhang.path("lat").isNull());
        assertTrue(anKhang.path("spatial_score").isNull());
        assertTrue(anKhang.path("closed_on").isNull());
        assertTrue(anKhang.path("osm_url").isNull());
        assertTrue(anKhang.path("source_url").asText().contains("cafebiz.vn"));
        assertTrue(anKhang.path("reason_unscored").asText().contains("số nhà"));
        assertEquals(11, body.path("sourced_panel_count").asInt());
        assertEquals(6, body.path("closed_group_count").asInt());
        assertTrue(body.path("panel_finding").asText().contains("ngưỡng"));
        boolean newsClosure = false;
        for (JsonNode place : body.path("sourced_places")) {
            assertFalse("The Coffee House".equals(place.path("name").asText()));
            if ("reported_closed".equals(place.path("outcome").asText()) && "Circle K".equals(place.path("name").asText())) {
                newsClosure = true;
                assertEquals(21.021910, place.path("lat").asDouble(), 0.0002);
                assertEquals(105.799888, place.path("lon").asDouble(), 0.0002);
                assertTrue(place.path("coordinate_method").asText().contains("11458943616"));
                assertTrue(place.path("osm_url").isNull());
                assertTrue(place.path("spatial_score").isNumber());
            }
        }
        assertTrue(newsClosure);
        assertTrue(body.path("survey_rule").asText().contains("check_date"));
        assertEquals(3, body.path("former_places").size());
        boolean formerConvenience = false;
        for (JsonNode place : body.path("former_places")) {
            assertTrue(place.path("inside_study").asBoolean());
            assertTrue(place.path("spatial_score").isNumber());
            assertTrue(place.path("osm_url").asText().startsWith("https://www.openstreetmap.org/node/"));
            if (place.path("osm_url").asText().endsWith("/12161473580")) {
                formerConvenience = true;
                assertEquals("Cầu Giấy", place.path("district").asText());
                assertEquals("disused:shop", place.path("lifecycle_key").asText());
            }
        }
        assertTrue(formerConvenience);
        int surveyed = 0;
        boolean highlands = false;
        boolean kafa = false;
        for (JsonNode business : body.path("businesses")) {
            assertEquals(60, business.path("grid_count").asInt());
            assertTrue(business.path("named_count").asInt() > 50);
            List<Double> shopScores = new ArrayList<>();
            for (JsonNode score : business.path("named_scores_without_self")) {
                shopScores.add(score.asDouble());
            }
            List<Double> gridScores = new ArrayList<>();
            for (JsonNode score : business.path("grid_scores")) {
                gridScores.add(score.asDouble());
            }
            assertEquals(business.path("named_count").asInt(), shopScores.size());
            assertEquals(BacktestService.median(shopScores), business.path("named_median_spatial_score_without_self").asDouble(), 1e-9);
            assertEquals(BacktestService.orderStatistic(shopScores, 0.25), business.path("named_lower_quartile").asDouble(), 1e-9);
            assertEquals(BacktestService.median(gridScores), business.path("grid_median_spatial_score").asDouble(), 1e-9);
            for (JsonNode place : business.path("surveyed")) {
                surveyed++;
                assertTrue(place.path("check_date").asText().matches("\\d{4}-\\d{2}-\\d{2}"));
                if (place.path("osm_url").asText().endsWith("/10269656432")) {
                    highlands = true;
                    assertEquals("2025-10-28", place.path("check_date").asText());
                    assertEquals("Cầu Giấy", place.path("district").asText());
                }
            }
            if ("cafe_drink".equals(business.path("business_type").asText())) {
                JsonNode traced = business.path("traced");
                kafa = traced.path("osm_url").asText().endsWith("/423736651");
                JsonNode alone = postJson("/api/scoring/evaluate", """
                        {"lat":%s,"lon":%s,"business_type":"cafe_drink","rent_vnd_per_month":25000000}
                        """.formatted(traced.path("lat").asDouble(), traced.path("lon").asDouble()));
                assertEquals(alone.path("spatial_score").asDouble(), traced.path("spatial_score").asDouble());
            }
        }
        assertTrue(surveyed >= 15);
        assertTrue(highlands);
        assertTrue(kafa);
    }

    private JsonNode get(String path) throws Exception {
        ResponseEntity<String> response = rest.getForEntity(path, String.class);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return mapper.readTree(response.getBody());
    }

    private JsonNode postJson(String path, String json) throws Exception {
        ResponseEntity<String> response = postRaw(path, json);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return mapper.readTree(response.getBody());
    }

    private ResponseEntity<String> postRaw(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(json, headers), String.class);
    }

    private byte[] postBytes(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = rest.postForEntity(path, new HttpEntity<>(json, headers), byte[].class);
        assertEquals(200, response.getStatusCode().value());
        return response.getBody();
    }
}
