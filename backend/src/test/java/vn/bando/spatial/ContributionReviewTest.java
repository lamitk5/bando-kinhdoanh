package vn.bando.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "bando.reviewer.name=ban-kiem-tra",
            "bando.reviewer.token=kiem-tra-noi-bo",
            "vision.worker.enabled=false"
        })
class ContributionReviewTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeRowsCreatedByThisTest() {
        jdbc.update("DELETE FROM poi_contribution WHERE name LIKE 'KIEM TRA DUYET%'");
    }

    @Test
    void approvalRequiresADifferentConfiguredReviewer() throws Exception {
        JsonNode pending = post("/api/spatial/contributions", """
                {"name":"KIEM TRA DUYET","category":"cafe_drink","lat":21.037861,"lon":105.801001,"submitter":"nguoi-gui"}
                """, null);
        long id = pending.path("id").asLong();
        assertEquals("pending", pending.path("status").asText());
        assertTrue(pending.path("reviewer").isNull());

        ResponseEntity<String> missing = send("/api/spatial/contributions/" + id + "/approve", "{}", null);
        assertEquals(401, missing.getStatusCode().value());
        assertEquals("pending", statusOf(id));

        ResponseEntity<String> wrong = send("/api/spatial/contributions/" + id + "/approve", "{}", "sai");
        assertEquals(401, wrong.getStatusCode().value());
        assertEquals("pending", statusOf(id));

        JsonNode self = post("/api/spatial/contributions", """
                {"name":"KIEM TRA DUYET TRUNG","category":"cafe_drink","lat":21.038,"lon":105.8012,"submitter":"ban-kiem-tra"}
                """, null);
        ResponseEntity<String> samePerson = send(
                "/api/spatial/contributions/" + self.path("id").asLong() + "/approve", "{}", "kiem-tra-noi-bo");
        assertEquals(400, samePerson.getStatusCode().value());
        assertTrue(samePerson.getBody().contains("trùng người gửi"));
        assertFalse(samePerson.getBody().contains("PL/pgSQL"));
        assertEquals("pending", statusOf(self.path("id").asLong()));

        JsonNode approved = post("/api/spatial/contributions/" + id + "/approve", "{}", "kiem-tra-noi-bo");
        assertEquals("approved", approved.path("status").asText());
        assertEquals("ban-kiem-tra", approved.path("reviewer").asText());
        assertTrue(approved.path("counted_in_buffer").asBoolean());

        JsonNode waiting = post("/api/spatial/contributions", """
                {"name":"KIEM TRA DUYET TU CHOI","category":"pharmacy","lat":21.0382,"lon":105.8014,"submitter":"nguoi-gui"}
                """, null);
        ResponseEntity<String> noReason = send(
                "/api/spatial/contributions/" + waiting.path("id").asLong() + "/reject", "{}", "kiem-tra-noi-bo");
        assertEquals(400, noReason.getStatusCode().value());
        JsonNode rejected = post(
                "/api/spatial/contributions/" + waiting.path("id").asLong() + "/reject",
                "{\"decision_note\":\"không dùng cho kiểm tra\"}",
                "kiem-tra-noi-bo");
        assertEquals("rejected", rejected.path("status").asText());
        assertEquals("ban-kiem-tra", rejected.path("reviewer").asText());
        assertFalse(rejected.path("counted_in_buffer").asBoolean());
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM poi_contribution WHERE id = ?", String.class, id);
    }

    private JsonNode post(String path, String json, String token) throws Exception {
        ResponseEntity<String> response = send(path, json, token);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return mapper.readTree(response.getBody());
    }

    private ResponseEntity<String> send(String path, String json, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set("X-Reviewer-Token", token);
        }
        return rest.postForEntity(path, new HttpEntity<>(json, headers), String.class);
    }
}
