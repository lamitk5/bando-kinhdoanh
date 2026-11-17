package vn.bando.spatial.api;

import java.time.Duration;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import vn.bando.spatial.BufferRequest;
import vn.bando.spatial.CacheKeys;
import vn.bando.spatial.ContributionRules;
import vn.bando.spatial.OsmRefs;
import vn.bando.spatial.api.SpatialModels.CacheInfo;
import vn.bando.spatial.api.SpatialModels.HealthResponse;
import vn.bando.spatial.api.SpatialModels.ImportInfo;
import vn.bando.spatial.api.SpatialModels.PoiSample;
import vn.bando.spatial.api.SpatialModels.SummaryBody;
import vn.bando.spatial.api.SpatialModels.SummaryResponse;
import vn.bando.spatial.data.SpatialRepository;

@Service
public class SpatialService {

    static final Duration SUMMARY_TTL = Duration.ofHours(6);

    private final SpatialRepository repository;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public SpatialService(SpatialRepository repository, StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.repository = repository;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public SummaryResponse summary() {
        ImportInfo dataImport = requireImport();
        String key = CacheKeys.summary(dataImport.id());
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            return new SummaryResponse(readBody(cached), new CacheInfo(true, key, ttl(key)));
        }
        SummaryBody body = repository.loadBody(dataImport);
        redis.opsForValue().set(key, writeBody(body), SUMMARY_TTL);
        return new SummaryResponse(body, new CacheInfo(false, key, SUMMARY_TTL.toSeconds()));
    }

    public ObjectNode buffer(double lat, double lon, String businessType) {
        BufferRequest.Normalized normalized;
        try {
            normalized = BufferRequest.normalize(lat, lon, businessType);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
        ImportInfo dataImport = requireImport();
        String key = CacheKeys.buffer(
                dataImport.id(),
                normalized.businessType(),
                normalized.lat(),
                normalized.lon(),
                repository.contributionEpoch());
        String cached = redis.opsForValue().get(key);
        boolean hit = cached != null;
        String profileJson = hit ? cached : repository.bufferProfile(normalized.lon(), normalized.lat(), normalized.businessType());
        if (!hit) {
            redis.opsForValue().set(key, profileJson, SUMMARY_TTL);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("import_id", dataImport.id());
        response.set("profile", withArterialUrl(readTree(profileJson)));
        response.set("cache", objectMapper.valueToTree(new CacheInfo(hit, key, hit ? ttl(key) : SUMMARY_TTL.toSeconds())));
        return response;
    }

    public JsonNode submitContribution(JsonNode body) {
        rejectUnknown(body, "name", "category", "lat", "lon", "note", "submitter");
        requireFields(body, "name", "category", "lat", "lon", "submitter");
        try {
            String name = ContributionRules.cleanName(text(body, "name"));
            String category = ContributionRules.requireCategory(text(body, "category"));
            double lat = ContributionRules.latitude(number(body, "lat"));
            double lon = ContributionRules.longitude(number(body, "lon"));
            String note = ContributionRules.cleanOptionalNote(text(body, "note"));
            String submitter = ContributionRules.cleanSubmitter(text(body, "submitter"));
            return readTree(repository.submitContribution(name, category, lon, lat, note, submitter));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    public JsonNode listContributions(String status) {
        if (status != null && !status.isBlank()
                && !Set.of("pending", "approved", "rejected").contains(status)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "status phải là pending, approved, rejected hoặc bỏ trống");
        }
        String filter = status == null || status.isBlank() ? null : status;
        return readTree(repository.listContributions(filter));
    }

    public JsonNode decideContribution(long id, String decision, JsonNode body) {
        rejectUnknown(body == null ? objectMapper.createObjectNode() : body, "decision_note");
        String note = body == null ? null : text(body, "decision_note");
        try {
            if ("rejected".equals(decision)) {
                note = ContributionRules.requireRejectionNote(note);
            } else if ("approved".equals(decision)) {
                note = ContributionRules.cleanOptionalNote(note);
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quyết định phải là approved hoặc rejected");
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
        try {
            JsonNode result = readTree(repository.decideContribution(id, decision, note));
            if ("approved".equals(decision)) {
                evictBufferCache();
            }
            return result;
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
                evictBufferCache();
            }
            throw exception;
        }
    }

    public ListOfSamples samples(int requestedLimit) {
        requireImport();
        int limit = Math.min(50, Math.max(1, requestedLimit));
        return new ListOfSamples(repository.samples(limit));
    }

    public HealthResponse health() {
        String redisStatus;
        try {
            redisStatus = pingRedis();
        } catch (RuntimeException exception) {
            redisStatus = "down";
        }
        return repository.health(redisStatus);
    }

    private ImportInfo requireImport() {
        try {
            return repository.latestImport();
        } catch (EmptyResultDataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa có lần nạp dữ liệu", exception);
        }
    }

    private String pingRedis() {
        String pong = redis.execute(connection -> connection.ping(), true);
        if (pong == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Redis không phản hồi");
        }
        return pong;
    }

    private long ttl(String key) {
        Long seconds = redis.getExpire(key);
        return seconds == null ? -1 : seconds;
    }

    private JsonNode withArterialUrl(JsonNode profile) {
        if (!(profile instanceof ObjectNode objectProfile)) {
            return profile;
        }
        JsonNode arterial = objectProfile.get("nearest_arterial");
        if (arterial instanceof ObjectNode objectArterial && objectArterial.hasNonNull("osm_id")) {
            objectArterial.put("osm_url", OsmRefs.url(objectArterial.path("osm_type").asText("W"), objectArterial.path("osm_id").asLong()));
        }
        return objectProfile;
    }

    private void evictBufferCache() {
        Set<String> keys = redis.keys("spatial:buffer:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private static void rejectUnknown(JsonNode body, String... allowed) {
        if (body == null || !body.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cần một đối tượng JSON");
        }
        Set<String> names = Set.of(allowed);
        body.fieldNames().forEachRemaining(field -> {
            if (!names.contains(field)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trường không được gửi: " + field);
            }
        });
    }

    private static void requireFields(JsonNode body, String... fields) {
        for (String field : fields) {
            if (!body.hasNonNull(field) || (body.get(field).isTextual() && body.get(field).asText().isBlank())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thiếu trường " + field);
            }
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trường " + field + " phải là chuỗi");
        }
        return value.asText();
    }

    private static double number(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isNumber()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trường " + field + " phải là số");
        }
        return value.asDouble();
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Kết quả vùng đệm không đọc được", exception);
        }
    }

    private SummaryBody readBody(String json) {
        try {
            return objectMapper.readValue(json, SummaryBody.class);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cache Redis không đọc được", exception);
        }
    }

    private String writeBody(SummaryBody body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Không ghi được cache Redis", exception);
        }
    }

    public record ListOfSamples(java.util.List<PoiSample> samples) {
    }
}
