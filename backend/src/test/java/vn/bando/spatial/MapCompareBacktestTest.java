package vn.bando.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
        assertEquals(200, rest.getForEntity("/", String.class).getStatusCode().value());
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
    }

    @Test
    void backtestUsesTwelveNamedShopsAndDoesNotClaimClosures() throws Exception {
        JsonNode body = get("/api/backtest");
        assertTrue(body.path("limitation").asText().contains("disused"));
        assertTrue(body.path("question").asText().contains("không phải đối chiếu cửa hàng còn sống"));
        int shops = 0;
        boolean kafa = false;
        for (JsonNode business : body.path("businesses")) {
            assertEquals(4, business.path("shop_count").asInt());
            assertEquals(12, business.path("grid_count").asInt());
            List<Double> shopScores = new ArrayList<>();
            for (JsonNode shop : business.path("shops")) {
                shops++;
                shopScores.add(shop.path("spatial_score_without_self").asDouble());
                assertTrue(shop.path("osm_url").asText().startsWith("https://www.openstreetmap.org/"));
                if (shop.path("osm_url").asText().endsWith("/423736651")) {
                    kafa = true;
                }
            }
            List<Double> gridScores = new ArrayList<>();
            for (JsonNode point : business.path("grid")) {
                gridScores.add(point.path("spatial_score").asDouble());
            }
            assertEquals(BacktestService.median(shopScores), business.path("shop_median_spatial_score_without_self").asDouble(), 1e-9);
            assertEquals(BacktestService.median(gridScores), business.path("grid_median_spatial_score").asDouble(), 1e-9);
        }
        assertEquals(12, shops);
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
