-- Người duyệt là một danh tính đã cấu hình, không lấy từ thân yêu cầu.
-- Hàm 3 tham số bị bỏ để không còn đường duyệt không ghi người duyệt.

ALTER TABLE poi_contribution
    ADD COLUMN IF NOT EXISTS reviewer text;

ALTER TABLE poi_contribution
    DROP CONSTRAINT IF EXISTS poi_contribution_reviewer_check;

ALTER TABLE poi_contribution
    ADD CONSTRAINT poi_contribution_reviewer_check CHECK (
        (
            status = 'pending'
            AND reviewer IS NULL
        ) OR (
            status IN ('approved', 'rejected')
            AND reviewer IS NOT NULL
            AND char_length(trim(reviewer)) BETWEEN 1 AND 80
            AND lower(trim(reviewer)) <> lower(trim(submitter))
        )
    );

CREATE OR REPLACE FUNCTION contribution_json(p_id bigint)
RETURNS jsonb
LANGUAGE sql
STABLE
AS $$
    SELECT jsonb_build_object(
        'id', c.id,
        'status', c.status,
        'name', c.name,
        'category', c.category,
        'role', c.role,
        'business_type', c.business_type,
        'note', c.note,
        'submitter', c.submitter,
        'reviewer', c.reviewer,
        'lat', ST_Y(c.geom),
        'lon', ST_X(c.geom),
        'district_osm_id', c.district_osm_id,
        'district_name', d.name,
        'on_boundary', c.on_boundary,
        'possible_duplicate', c.possible_duplicate,
        'inside_current_study', EXISTS (
            SELECT 1 FROM district AS covered WHERE ST_Covers(covered.geom, c.geom)
        ),
        'counted_in_buffer', c.status = 'approved' AND EXISTS (
            SELECT 1 FROM district AS covered WHERE ST_Covers(covered.geom, c.geom)
        ),
        'submitted_at', c.submitted_at,
        'decided_at', c.decided_at,
        'decision_note', c.decision_note,
        'source', 'field'
    )
    FROM poi_contribution AS c
    LEFT JOIN LATERAL (
        SELECT name, geom
        FROM district
        WHERE osm_id = c.district_osm_id
        ORDER BY id DESC
        LIMIT 1
    ) AS d ON true
    WHERE c.id = p_id;
$$;

DROP FUNCTION IF EXISTS decide_poi_contribution(bigint, text, text);

CREATE FUNCTION decide_poi_contribution(
    p_id bigint,
    p_decision text,
    p_note text,
    p_reviewer text
) RETURNS jsonb
LANGUAGE plpgsql
AS $$
DECLARE
    clean_note text := nullif(trim(p_note), '');
    clean_reviewer text := nullif(trim(p_reviewer), '');
    current_status text;
    current_submitter text;
BEGIN
    IF p_decision IS NULL OR p_decision NOT IN ('approved', 'rejected') THEN
        RAISE EXCEPTION 'Quyết định phải là approved hoặc rejected' USING ERRCODE = '22023';
    END IF;
    IF clean_note IS NOT NULL AND char_length(clean_note) > 500 THEN
        RAISE EXCEPTION 'Ghi chú duyệt tối đa 500 ký tự' USING ERRCODE = '22023';
    END IF;
    IF p_decision = 'rejected' AND clean_note IS NULL THEN
        RAISE EXCEPTION 'Từ chối cần lý do' USING ERRCODE = '22023';
    END IF;
    IF clean_reviewer IS NULL OR char_length(clean_reviewer) > 80 THEN
        RAISE EXCEPTION 'Người duyệt cần từ 1 đến 80 ký tự' USING ERRCODE = '22023';
    END IF;

    SELECT status, submitter INTO current_status, current_submitter
    FROM poi_contribution
    WHERE id = p_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Không có đóng góp %', p_id USING ERRCODE = '02000';
    END IF;
    IF current_status <> 'pending' THEN
        RAISE EXCEPTION 'Đóng góp % đã ở trạng thái %', p_id, current_status USING ERRCODE = 'P0001';
    END IF;
    IF lower(clean_reviewer) = lower(trim(current_submitter)) THEN
        RAISE EXCEPTION 'Người duyệt trùng người gửi' USING ERRCODE = '22023';
    END IF;

    UPDATE poi_contribution
    SET status = p_decision,
        reviewer = clean_reviewer,
        decided_at = clock_timestamp(),
        decision_note = clean_note
    WHERE id = p_id
      AND status = 'pending';

    IF p_decision = 'approved' THEN
        UPDATE contribution_epoch SET epoch = epoch + 1 WHERE id = 1;
    END IF;

    RETURN contribution_json(p_id);
END;
$$;
