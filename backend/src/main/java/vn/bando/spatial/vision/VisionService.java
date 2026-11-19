package vn.bando.spatial.vision;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import vn.bando.spatial.vision.VisionAssessmentParser.Assessment;

@Service
public class VisionService {

    static final String QUEUE = "vision:queue";

    /** Luôn null khi chạy thật. Bài kiểm tra giữ chốt để kịp đăng ký socket. */
    public static volatile CountDownLatch assessmentGate;

    private final VisionJobRepository jobs;
    private final VisionClient client;
    private final YoloCounter yolo;
    private final StringRedisTemplate redis;
    private final SimpMessagingTemplate messaging;
    private final ObjectMapper mapper;

    public VisionService(
            VisionJobRepository jobs,
            VisionClient client,
            YoloCounter yolo,
            StringRedisTemplate redis,
            SimpMessagingTemplate messaging,
            ObjectMapper mapper) {
        this.jobs = jobs;
        this.client = client;
        this.yolo = yolo;
        this.redis = redis;
        this.messaging = messaging;
        this.mapper = mapper;
    }

    public JsonNode submit(MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file == null ? null : file.getBytes();
        } catch (java.io.IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không đọc được ảnh", exception);
        }
        String contentType;
        try {
            contentType = ImageMagic.contentType(bytes);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
        UUID id = UUID.randomUUID();
        jobs.insert(id, contentType, bytes, sha256(bytes));
        redis.opsForList().leftPush(QUEUE, id.toString());
        return broadcast(id);
    }

    public JsonNode get(UUID id) {
        return read(id);
    }

    public void process(String rawId) {
        UUID id;
        try {
            id = UUID.fromString(rawId);
        } catch (IllegalArgumentException exception) {
            return;
        }
        VisionJobRepository.Claimed claimed = jobs.claim(id).orElse(null);
        if (claimed == null) {
            return;
        }
        broadcast(id);
        try {
            awaitGate();
        } catch (RuntimeException exception) {
            jobs.finish(id, "failed", null, clip(exception.getMessage()));
            broadcast(id);
            return;
        }
        assessed(id, claimed.contentType(), claimed.image());
    }

    private static void awaitGate() {
        CountDownLatch gate = assessmentGate;
        if (gate == null) {
            return;
        }
        try {
            if (!gate.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Hết thời gian chờ trước khi gọi mô hình");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Dừng trước khi gọi mô hình");
        }
    }

    private JsonNode assessed(UUID id, String contentType, byte[] image) {
        try {
            Assessment assessment = VisionAssessmentParser.parse(client.assess(image, contentType));
            ObjectNode result = mapper.createObjectNode();
            boolean poor = assessment.sidewalkCapacity() == null;
            result.put("assessed", assessment.assessed());
            result.put("image_quality", poor ? "poor" : "ok");
            result.set("quality_reasons", mapper.valueToTree(assessment.qualityReasons()));
            if (poor) {
                result.putNull("sidewalk_capacity");
                result.putNull("sidewalk_score");
                result.putNull("sidewalk_width_m");
                result.putNull("visibility");
                result.putNull("visibility_score");
            } else {
                result.put("sidewalk_capacity", assessment.sidewalkCapacity());
                result.put("sidewalk_score", assessment.sidewalkScore());
                if (assessment.widthMeters() == null) {
                    result.putNull("sidewalk_width_m");
                } else {
                    result.put("sidewalk_width_m", assessment.widthMeters());
                }
                if (assessment.visibility() == null) {
                    result.putNull("visibility");
                    result.putNull("visibility_score");
                    result.put("visibility_measured", false);
                } else {
                    result.put("visibility", assessment.visibility());
                    result.put("visibility_score", assessment.visibilityScore());
                    result.put("visibility_measured", true);
                }
            }
            result.put("recommendation", assessment.recommendation());
            result.put("ordinal_note", "Điểm vỉa hè và tầm nhìn là thang thứ tự đã quy ước, không phải số đo mét.");
            if (poor) {
                result.putNull("yolo");
                result.put("yolo_note", "Ảnh không đủ để đếm vật thể.");
                advisory(result, "not_sent", "Ảnh không đủ để đưa sang tầng tư vấn.");
            } else {
                result.set("yolo", yolo.count(image, contentType));
                advisory(result, "waiting_for_api", "API suy luận chưa được gắn. Ảnh không được gửi. Đầu vào sẽ là PostGIS và JSON YOLO.");
            }
            jobs.finish(id, poor ? "poor_quality" : "completed", mapper.writeValueAsString(result), null);
        } catch (RuntimeException exception) {
            jobs.finish(id, "failed", null, clip(exception.getMessage()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            jobs.finish(id, "failed", null, "Không ghi được kết quả ảnh");
        }
        return broadcast(id);
    }

    private void advisory(ObjectNode result, String status, String reason) {
        ObjectNode advisory = result.putObject("advisory");
        advisory.put("status", status);
        advisory.put("image_sent", false);
        advisory.putNull("text");
        advisory.put("reason", reason);
    }

    private JsonNode broadcast(UUID id) {
        JsonNode job = read(id);
        try {
            messaging.convertAndSend("/topic/vision/" + id, mapper.convertValue(job, java.util.Map.class));
        } catch (RuntimeException ignored) {
            // GET vẫn là nguồn trạng thái khi socket chưa có người nghe.
        }
        return job;
    }

    private JsonNode read(UUID id) {
        String json = jobs.findJson(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Không thấy việc ảnh " + id));
        try {
            return mapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Việc ảnh không đọc được", exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Không có SHA-256", exception);
        }
    }

    private static String clip(String message) {
        String text = message == null || message.isBlank() ? "Thẩm định ảnh thất bại" : message;
        return text.length() <= 500 ? text : text.substring(0, 500);
    }
}
