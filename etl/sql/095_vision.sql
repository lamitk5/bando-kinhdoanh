-- Việc thẩm định ảnh. Bảng không bị lần nạp OSM xóa.
-- Kết quả JSON chỉ có khi ảnh đã được mô hình trả về và qua bộ phân tích.

CREATE TABLE IF NOT EXISTS vision_job (
    id uuid PRIMARY KEY,
    status text NOT NULL CHECK (status IN ('queued', 'running', 'completed', 'poor_quality', 'failed')),
    content_type text NOT NULL,
    byte_size integer NOT NULL CHECK (byte_size > 0 AND byte_size <= 4000000),
    sha256 text NOT NULL CHECK (char_length(sha256) = 64),
    image bytea NOT NULL,
    error_message text,
    result jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz,
    CONSTRAINT vision_job_terminal_check CHECK (
        (status IN ('queued', 'running') AND finished_at IS NULL AND result IS NULL)
        OR (status = 'failed' AND finished_at IS NOT NULL AND result IS NULL AND error_message IS NOT NULL)
        OR (status IN ('completed', 'poor_quality') AND finished_at IS NOT NULL AND result IS NOT NULL AND error_message IS NULL)
    )
);

CREATE INDEX IF NOT EXISTS vision_job_status_idx ON vision_job (status, created_at);
