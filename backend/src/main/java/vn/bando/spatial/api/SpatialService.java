package vn.bando.spatial.api;

import java.time.Duration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import vn.bando.spatial.CacheKeys;
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
