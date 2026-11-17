package vn.bando.spatial.score;

import java.sql.SQLException;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class ScoringRepository {

    private final JdbcTemplate jdbc;

    public ScoringRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String reference() {
        try {
            String json = jdbc.queryForObject("SELECT to_jsonb(scoring_reference)::text FROM scoring_reference WHERE id = 1", String.class);
            if (json == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa có thang chấm điểm");
            }
            return json;
        } catch (DataAccessException exception) {
            if (exception instanceof org.springframework.dao.EmptyResultDataAccessException) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa có thang chấm điểm");
            }
            throw exception;
        }
    }

    public String refresh() {
        return jdbc.queryForObject("SELECT refresh_scoring_reference()::text", String.class);
    }

    public String indicators(double lon, double lat, String businessType) {
        try {
            return jdbc.queryForObject(
                    "SELECT location_indicators(?, ?, ?)::text",
                    String.class,
                    lon,
                    lat,
                    businessType
            );
        } catch (DataAccessException exception) {
            throw mapSqlState(exception);
        }
    }

    private static RuntimeException mapSqlState(DataAccessException exception) {
        Throwable cause = exception.getMostSpecificCause();
        if (cause instanceof SQLException sql && "22023".equals(sql.getSQLState())) {
            return new ResponseStatusException(HttpStatus.BAD_REQUEST, cause.getMessage(), exception);
        }
        return exception;
    }
}
