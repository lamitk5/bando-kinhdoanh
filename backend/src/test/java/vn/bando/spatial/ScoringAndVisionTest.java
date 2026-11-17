package vn.bando.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Type;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import vn.bando.spatial.vision.VisionService;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScoringAndVisionTest {

    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private final List<String> createdJobIds = new ArrayList<>();

    @AfterEach
    void removeFixture() {
        if (VisionService.assessmentGate != null) {
            VisionService.assessmentGate.countDown();
        }
        VisionService.assessmentGate = null;
        for (String id : createdJobIds) {
            jdbc.update("DELETE FROM vision_job WHERE id = ?::uuid", id);
        }
        createdJobIds.clear();
    }

    @Test
    void scoresKafaFromTheStoredScale() throws Exception {
        JsonNode reference = rest.getForObject("/api/scoring/reference", JsonNode.class);
        assertTrue(reference.path("sample_count").asInt() >= 200);
        assertTrue(reference.path("attractor_density_p95").asDouble()
                > reference.path("cafe_competitor_density_p95").asDouble());
        assertTrue(reference.path("matrix_label").asText().contains("chưa phải phiếu chuyên gia"));
        assertEquals(3, reference.path("matrices").size());
        assertTrue(reference.path("matrices").get(0).path("consistency_ratio").asDouble() < 0.1);

        JsonNode cafe = evaluate("""
                {"lat":21.0378611,"lon":105.8010007,"business_type":"cafe_drink","rent_vnd_per_month":25000000}
                """);
        assertFalse(cafe.path("vision_applied").asBoolean());
        assertTrue(cafe.path("vision_job_id").isNull());
        assertTrue(cafe.path("composite_score").isNull());
        assertFalse(cafe.path("partial_ring").asBoolean());
        assertTrue(cafe.path("indicators").path("competitor_count").asInt() >= 1);
        assertRecombines(cafe);
        double distance = cafe.path("components").path("arterial_access").path("raw").asDouble();
        assertTrue(distance > 5 && distance < 20, "arterial distance " + distance);
        assertTrue(cafe.path("rent_index_meaning").asText().contains("Không phải lợi nhuận"));
        assertFalse(cafe.has("roi"));

        JsonNode pharmacy = evaluate("""
                {"lat":21.0378611,"lon":105.8010007,"business_type":"pharmacy","rent_vnd_per_month":25000000}
                """);
        assertTrue(pharmacy.path("weights").get(0).asDouble() > pharmacy.path("weights").get(1).asDouble());
        assertTrue(cafe.path("weights").get(1).asDouble() > cafe.path("weights").get(0).asDouble());

        assertEquals(400, post("{\"lat\":21,\"lon\":105,\"business_type\":\"pharmacy\",\"rent_vnd_per_month\":1}").getStatusCode().value());
        assertEquals(400, post("{\"lat\":21.0378611,\"lon\":105.8010007,\"business_type\":\"restaurant\",\"rent_vnd_per_month\":1}").getStatusCode().value());
        assertEquals(400, post("{\"lat\":21.0378611,\"lon\":105.8010007,\"business_type\":\"cafe_drink\",\"rent_vnd_per_month\":25000000,\"blend_weight\":0.5}").getStatusCode().value());
        assertEquals(404, post("{\"lat\":21.0378611,\"lon\":105.8010007,\"business_type\":\"cafe_drink\",\"rent_vnd_per_month\":25000000,\"vision_job_id\":\"00000000-0000-0000-0000-000000000000\"}").getStatusCode().value());
    }

    @Test
    void tinyPngFailsWithoutAKeyAndTheSocketSeesIt() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        VisionService.assessmentGate = gate;
        JsonNode created = upload(ONE_PIXEL_PNG);
        createdJobIds.add(created.path("id").asText());
        String createdJobId = created.path("id").asText();
        assertEquals("queued", created.path("status").asText());
        assertFalse(created.has("image"));

        BlockingQueue<Object> frames = new LinkedBlockingQueue<>();
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        StompSession session = client.connectAsync("ws://127.0.0.1:" + port + "/ws", new StompSessionHandlerAdapter() {
            @Override
            public void handleException(StompSession session, org.springframework.messaging.simp.stomp.StompCommand command, StompHeaders headers, byte[] payload, Throwable exception) {
                frames.add(exception.toString());
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                frames.add(exception.toString());
            }
        }).get(5, TimeUnit.SECONDS);
        session.subscribe("/topic/vision/" + createdJobId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                frames.add(payload);
            }
        });
        Thread.sleep(300);
        gate.countDown();

        Object frame = frames.poll(8, TimeUnit.SECONDS);
        JsonNode job = rest.getForObject("/api/vision/jobs/" + createdJobId, JsonNode.class);
        assertTrue(frame instanceof Map<?, ?> map && "failed".equals(map.get("status")), "frame " + frame + " job " + job);
        session.disconnect();

        assertEquals("failed", job.path("status").asText());
        assertTrue(job.path("result").isNull());
        assertTrue(job.path("error_message").asText().contains("Chưa có khóa API thị giác"));
        assertFalse(job.has("image"));
    }

    @Test
    void blendsOnlyACallerWeightAndRefusesAPoorPhoto() throws Exception {
        String ready = insertJob("completed", """
                {"assessed":true,"image_quality":"ok","quality_reasons":[],"sidewalk_capacity":"enough","sidewalk_score":70,"visibility":"clear","visibility_score":100,"recommendation":"Dòng kiểm tra thang thứ tự, không phải ảnh hiện trường.","ordinal_note":"Điểm vỉa hè và tầm nhìn là thang thứ tự đã quy ước, không phải số đo mét."}
                """);
        JsonNode blended = evaluate("""
                {"lat":21.0378611,"lon":105.8010007,"business_type":"cafe_drink","rent_vnd_per_month":25000000,"vision_job_id":"%s","blend_weight":0.5}
                """.formatted(ready));
        assertEquals(90.0, blended.path("image_score").asDouble(), 0.05);
        assertEquals(0.5, blended.path("blend_weight").asDouble(), 0);
        double expected = Math.round((0.5 * blended.path("spatial_score").asDouble() + 45.0) * 10.0) / 10.0;
        assertEquals(expected, blended.path("composite_score").asDouble(), 0.05);

        String poor = insertJob("poor_quality", """
                {"assessed":false,"image_quality":"poor","quality_reasons":["mờ"],"sidewalk_capacity":null,"sidewalk_score":null,"visibility":null,"visibility_score":null,"recommendation":"Ảnh không đủ.","ordinal_note":"Điểm vỉa hè và tầm nhìn là thang thứ tự đã quy ước, không phải số đo mét."}
                """);
        assertEquals(409, post("""
                {"lat":21.0378611,"lon":105.8010007,"business_type":"cafe_drink","rent_vnd_per_month":25000000,"vision_job_id":"%s","blend_weight":1}
                """.formatted(poor)).getStatusCode().value());
    }

    private String insertJob(String status, String result) throws Exception {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO vision_job (id, status, content_type, byte_size, sha256, image, result, finished_at)
                VALUES (?::uuid, ?, 'image/png', ?, ?, ?, ?::jsonb, clock_timestamp())
                """,
                id,
                status,
                ONE_PIXEL_PNG.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ONE_PIXEL_PNG)),
                ONE_PIXEL_PNG,
                result);
        createdJobIds.add(id);
        return id;
    }

    private JsonNode evaluate(String json) throws Exception {
        ResponseEntity<String> response = post(json);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return mapper.readTree(response.getBody());
    }

    private ResponseEntity<String> post(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/api/scoring/evaluate", new HttpEntity<>(json, headers), String.class);
    }

    private JsonNode upload(byte[] png) throws Exception {
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.IMAGE_PNG);
        ByteArrayResource resource = new ByteArrayResource(png) {
            @Override
            public String getFilename() {
                return "dot.png";
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new HttpEntity<>(resource, fileHeaders));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> response = rest.postForEntity(
                "/api/vision/jobs",
                new HttpEntity<>(body, headers),
                String.class);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return mapper.readTree(response.getBody());
    }

    private static void assertRecombines(JsonNode score) {
        JsonNode weights = score.path("weights");
        JsonNode components = score.path("components");
        double recomputed = weights.get(0).asDouble() * components.path("attractor_density").path("score").asDouble()
                + weights.get(1).asDouble() * components.path("competitor_saturation").path("score").asDouble()
                + weights.get(2).asDouble() * components.path("arterial_access").path("score").asDouble();
        assertEquals(score.path("spatial_score").asDouble(), Math.round(recomputed * 10.0) / 10.0, 0.05);
        double distance = components.path("arterial_access").path("raw").asDouble();
        double median = components.path("arterial_access").path("scale").asDouble();
        double access = 100.0 * Math.exp(-Math.log(2.0) * distance / median);
        assertEquals(components.path("arterial_access").path("score").asDouble(), Math.round(access * 10.0) / 10.0, 0.05);
        long rent = score.path("rent_vnd_per_month").asLong();
        assertEquals(
                score.path("spatial_score").asDouble() * 1_000_000.0 / rent,
                score.path("rent_index_points_per_million_vnd").asDouble(),
                0.001);
    }
}
