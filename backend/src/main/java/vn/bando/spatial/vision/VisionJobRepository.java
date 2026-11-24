package vn.bando.spatial.vision;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class VisionJobRepository {

    private final JdbcTemplate jdbc;

    public VisionJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Claimed(UUID id, String contentType, byte[] image) {
    }

    public void insert(UUID id, String contentType, byte[] image, String sha256) {
        jdbc.update(
                """
                INSERT INTO vision_job (id, status, content_type, byte_size, sha256, image)
                VALUES (?, 'queued', ?, ?, ?, ?)
                """,
                id,
                contentType,
                image.length,
                sha256,
                image);
    }

    public Optional<String> findJson(UUID id) {
        List<String> rows = jdbc.query(
                """
                SELECT jsonb_build_object(
                    'id', id,
                    'status', status,
                    'content_type', content_type,
                    'byte_size', byte_size,
                    'sha256', sha256,
                    'error_message', error_message,
                    'result', result,
                    'created_at', created_at,
                    'started_at', started_at,
                    'finished_at', finished_at
                )::text
                FROM vision_job
                WHERE id = ?
                """,
                (rs, rowNum) -> rs.getString(1),
                id);
        return rows.stream().findFirst();
    }

    public Optional<Claimed> claim(UUID id) {
        int updated = jdbc.update(
                """
                UPDATE vision_job
                SET status = 'running', started_at = clock_timestamp()
                WHERE id = ? AND status = 'queued'
                """,
                id);
        if (updated != 1) {
            return Optional.empty();
        }
        List<Claimed> rows = jdbc.query(
                "SELECT id, content_type, image FROM vision_job WHERE id = ? AND status = 'running'",
                (rs, rowNum) -> new Claimed(
                        rs.getObject("id", UUID.class),
                        rs.getString("content_type"),
                        rs.getBytes("image")),
                id);
        return rows.stream().findFirst();
    }

    public void finish(UUID id, String status, String resultJson, String error) {
        int updated = resultJson == null
                ? jdbc.update(
                        """
                        UPDATE vision_job
                        SET status = ?, finished_at = clock_timestamp(), result = NULL, error_message = ?
                        WHERE id = ? AND status = 'running'
                        """,
                        status,
                        error,
                        id)
                : jdbc.update(
                        """
                        UPDATE vision_job
                        SET status = ?, finished_at = clock_timestamp(), result = ?::jsonb, error_message = NULL
                        WHERE id = ? AND status = 'running'
                        """,
                        status,
                        resultJson,
                        id);
        if (updated != 1) {
            throw new IllegalStateException("Không cập nhật được việc ảnh " + id);
        }
    }

    public List<String> requeueLeftovers() {
        jdbc.update(
                """
                UPDATE vision_job
                SET status = 'queued', started_at = NULL
                WHERE status = 'running'
                """);
        return jdbc.query(
                "SELECT id::text FROM vision_job WHERE status = 'queued' ORDER BY created_at",
                (rs, rowNum) -> rs.getString(1));
    }
}
