-- Đóng góp điểm tiện ích ngoài hiện trường.
-- Dòng pending và rejected không vào vùng đệm. Chỉ approved còn nằm trong bốn quận mới được đếm.
-- Bảng này không bị lần nạp OSM xóa.

CREATE TABLE IF NOT EXISTS poi_contribution (
    id bigserial PRIMARY KEY,
    status text NOT NULL DEFAULT 'pending',
    name text NOT NULL,
    category text NOT NULL,
    role text NOT NULL,
    business_type text,
    note text,
    submitter text NOT NULL,
    geom geometry(Point, 4326) NOT NULL,
    district_osm_id bigint NOT NULL,
    on_boundary boolean NOT NULL,
    possible_duplicate boolean NOT NULL,
    submitted_at timestamptz NOT NULL DEFAULT now(),
    decided_at timestamptz,
    decision_note text,
    CONSTRAINT poi_contribution_role_check CHECK (
        (
            role = 'competitor'
            AND business_type = category
            AND category IN ('cafe_drink', 'pharmacy', 'convenience')
        ) OR (
            role = 'attractor'
            AND business_type IS NULL
            AND category IN (
                'education', 'office', 'apartments', 'hospital', 'clinic',
                'transit_station', 'transit_stop'
            )
        )
    ),
    CONSTRAINT poi_contribution_status_check CHECK (
        (status = 'pending' AND decided_at IS NULL AND decision_note IS NULL)
        OR (status = 'approved' AND decided_at IS NOT NULL)
        OR (
            status = 'rejected'
            AND decided_at IS NOT NULL
            AND decision_note IS NOT NULL
            AND char_length(trim(decision_note)) > 0
        )
    ),
    CONSTRAINT poi_contribution_name_check CHECK (char_length(trim(name)) BETWEEN 1 AND 160),
    CONSTRAINT poi_contribution_submitter_check CHECK (char_length(trim(submitter)) BETWEEN 1 AND 80),
    CONSTRAINT poi_contribution_note_check CHECK (note IS NULL OR char_length(note) <= 500),
    CONSTRAINT poi_contribution_decision_note_check CHECK (
        decision_note IS NULL OR char_length(decision_note) <= 500
    )
);

CREATE INDEX IF NOT EXISTS poi_contribution_geom_gix
    ON poi_contribution USING GIST (geom);
CREATE INDEX IF NOT EXISTS poi_contribution_geog_gix
    ON poi_contribution USING GIST ((geom::geography));

CREATE TABLE IF NOT EXISTS contribution_epoch (
    id integer PRIMARY KEY CHECK (id = 1),
    epoch bigint NOT NULL
);

INSERT INTO contribution_epoch (id, epoch)
VALUES (1, 0)
ON CONFLICT (id) DO NOTHING;

CREATE OR REPLACE VIEW counted_poi AS
SELECT id, category, role, geom, 'osm'::text AS origin
FROM poi
UNION ALL
SELECT id, category, role, geom, 'contribution'::text AS origin
FROM poi_contribution
WHERE status = 'approved'
  AND EXISTS (
      SELECT 1
      FROM district
      WHERE ST_Covers(district.geom, poi_contribution.geom)
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

CREATE OR REPLACE FUNCTION submit_poi_contribution(
    p_name text,
    p_category text,
    p_lon double precision,
    p_lat double precision,
    p_note text,
    p_submitter text
) RETURNS jsonb
LANGUAGE plpgsql
AS $$
DECLARE
    clean_name text := nullif(trim(p_name), '');
    clean_submitter text := nullif(trim(p_submitter), '');
    clean_note text := nullif(trim(p_note), '');
    pt geometry;
    chosen_id bigint;
    chosen_osm bigint;
    n_inside integer;
    n_touch integer;
    assigned_role text;
    assigned_business text;
    duplicate_name boolean;
    new_id bigint;
BEGIN
    IF clean_name IS NULL OR char_length(clean_name) > 160 THEN
        RAISE EXCEPTION 'Tên điểm cần từ 1 đến 160 ký tự' USING ERRCODE = '22023';
    END IF;
    IF clean_submitter IS NULL OR char_length(clean_submitter) > 80 THEN
        RAISE EXCEPTION 'Người gửi cần từ 1 đến 80 ký tự' USING ERRCODE = '22023';
    END IF;
    IF clean_note IS NOT NULL AND char_length(clean_note) > 500 THEN
        RAISE EXCEPTION 'Ghi chú tối đa 500 ký tự' USING ERRCODE = '22023';
    END IF;
    IF p_category IS NULL OR p_category NOT IN (
        'education', 'office', 'apartments', 'hospital', 'clinic',
        'transit_station', 'transit_stop',
        'cafe_drink', 'pharmacy', 'convenience'
    ) THEN
        RAISE EXCEPTION 'category không thuộc danh mục đang dùng' USING ERRCODE = '22023';
    END IF;
    IF p_lon IS NULL OR p_lat IS NULL
        OR p_lon < 102 OR p_lon > 110
        OR p_lat < 8 OR p_lat > 24 THEN
        RAISE EXCEPTION 'Tọa độ nằm ngoài phạm vi Việt Nam (kinh độ 102–110, vĩ độ 8–24)'
            USING ERRCODE = '22023';
    END IF;

    IF p_category IN ('cafe_drink', 'pharmacy', 'convenience') THEN
        assigned_role := 'competitor';
        assigned_business := p_category;
    ELSE
        assigned_role := 'attractor';
        assigned_business := NULL;
    END IF;

    pt := ST_SetSRID(ST_MakePoint(p_lon, p_lat), 4326);

    SELECT
        count(*) FILTER (WHERE ST_Contains(geom, pt)),
        count(*)
    INTO n_inside, n_touch
    FROM district
    WHERE geom && pt AND ST_Intersects(geom, pt);

    IF n_inside = 1 THEN
        SELECT id, osm_id
        INTO chosen_id, chosen_osm
        FROM district
        WHERE geom && pt AND ST_Contains(geom, pt);
    ELSIF n_inside = 0 AND n_touch = 1 THEN
        SELECT id, osm_id
        INTO chosen_id, chosen_osm
        FROM district
        WHERE geom && pt AND ST_Intersects(geom, pt);
    ELSIF coalesce(n_touch, 0) = 0 THEN
        RAISE EXCEPTION 'Điểm nằm ngoài bốn quận nghiên cứu' USING ERRCODE = '22023';
    ELSE
        RAISE EXCEPTION 'Điểm chạm hơn một quận, không gán được một quận' USING ERRCODE = '22023';
    END IF;

    SELECT EXISTS (
        SELECT 1
        FROM poi
        WHERE poi.district_id = chosen_id
          AND poi.category = p_category
          AND poi.name IS NOT NULL
          AND lower(trim(poi.name)) = lower(clean_name)
          AND ST_DWithin(poi.geom::geography, pt::geography, 30)
        UNION ALL
        SELECT 1
        FROM poi_contribution AS other
        WHERE other.status <> 'rejected'
          AND other.district_osm_id = chosen_osm
          AND other.category = p_category
          AND lower(trim(other.name)) = lower(clean_name)
          AND ST_DWithin(other.geom::geography, pt::geography, 30)
    ) INTO duplicate_name;

    INSERT INTO poi_contribution (
        name, category, role, business_type, note, submitter, geom,
        district_osm_id, on_boundary, possible_duplicate
    )
    VALUES (
        clean_name, p_category, assigned_role, assigned_business, clean_note, clean_submitter, pt,
        chosen_osm, n_inside = 0, duplicate_name
    )
    RETURNING id INTO new_id;

    RETURN contribution_json(new_id);
END;
$$;

CREATE OR REPLACE FUNCTION decide_poi_contribution(
    p_id bigint,
    p_decision text,
    p_note text
) RETURNS jsonb
LANGUAGE plpgsql
AS $$
DECLARE
    clean_note text := nullif(trim(p_note), '');
    current_status text;
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

    SELECT status INTO current_status
    FROM poi_contribution
    WHERE id = p_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Không có đóng góp %', p_id USING ERRCODE = '02000';
    END IF;
    IF current_status <> 'pending' THEN
        RAISE EXCEPTION 'Đóng góp % đã ở trạng thái %', p_id, current_status USING ERRCODE = 'P0001';
    END IF;

    UPDATE poi_contribution
    SET status = p_decision,
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

CREATE OR REPLACE FUNCTION list_poi_contributions(p_status text)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    wanted text := nullif(trim(p_status), '');
    matched_count integer;
    payload jsonb;
BEGIN
    IF wanted IS NOT NULL AND wanted NOT IN ('pending', 'approved', 'rejected') THEN
        RAISE EXCEPTION 'status phải là pending, approved, rejected hoặc bỏ trống' USING ERRCODE = '22023';
    END IF;

    SELECT count(*) INTO matched_count
    FROM poi_contribution
    WHERE wanted IS NULL OR status = wanted;

    SELECT coalesce(jsonb_agg(contribution_json(id) ORDER BY id DESC), '[]'::jsonb)
    INTO payload
    FROM (
        SELECT id
        FROM poi_contribution
        WHERE wanted IS NULL OR status = wanted
        ORDER BY id DESC
        LIMIT 100
    ) AS page;

    RETURN jsonb_build_object(
        'matched', matched_count,
        'returned', jsonb_array_length(payload),
        'truncated', matched_count > 100,
        'contributions', payload
    );
END;
$$;
