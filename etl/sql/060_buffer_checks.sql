-- Kiểm tra vùng đệm trên dữ liệu đã nạp. Không đối chiếu một số lượng gõ sẵn,
-- ngoài các bất biến: bán kính đúng, số đếm không giảm khi bán kính tăng,
-- mét chứ không phải độ, và điểm xa vùng nghiên cứu không được coi là đếm đủ.

DO $$
DECLARE
    anchor_lon double precision;
    anchor_lat double precision;
    anchor_name text;
    profile jsonb;
    competitors int[];
    attractors int[];
    radius int;
    reported_m numeric;
    measured_m double precision;
    degree_distance double precision;
    outside jsonb;
BEGIN
    SELECT ST_X(geom), ST_Y(geom), name
    INTO anchor_lon, anchor_lat, anchor_name
    FROM poi
    WHERE osm_type = 'N'
      AND osm_id = 423736651
      AND category = 'cafe_drink';

    IF anchor_lon IS NULL THEN
        RAISE EXCEPTION 'Không thấy node OSM 423736651 loại cafe_drink trong bảng poi';
    END IF;

    profile := buffer_profile(anchor_lon, anchor_lat, 'cafe_drink');

    IF profile -> 'radii_m' <> '[200,500,1000]'::jsonb THEN
        RAISE EXCEPTION 'Sai bộ bán kính: %', profile -> 'radii_m';
    END IF;

    IF profile ->> 'distance_unit' IS DISTINCT FROM 'meter'
        OR (profile ->> 'srid')::int <> 4326
        OR profile ->> 'nearest_arterial_rule' IS NULL THEN
        RAISE EXCEPTION 'Sai đơn vị hoặc SRID: %', profile;
    END IF;

    IF jsonb_array_length(profile -> 'rings') <> 3 THEN
        RAISE EXCEPTION 'Cần đúng 3 vòng, có %', profile -> 'rings';
    END IF;

    competitors := ARRAY[]::int[];
    attractors := ARRAY[]::int[];
    FOR radius IN SELECT (ring ->> 'radius_m')::int
                  FROM jsonb_array_elements(profile -> 'rings') AS ring
                  ORDER BY (ring ->> 'radius_m')::int
    LOOP
        competitors := competitors || (
            SELECT (ring ->> 'competitor_count')::int
            FROM jsonb_array_elements(profile -> 'rings') AS ring
            WHERE (ring ->> 'radius_m')::int = radius
        );
        attractors := attractors || (
            SELECT (ring ->> 'attractor_count')::int
            FROM jsonb_array_elements(profile -> 'rings') AS ring
            WHERE (ring ->> 'radius_m')::int = radius
        );
    END LOOP;

    IF competitors[1] < 1 THEN
        RAISE EXCEPTION 'Node % (%) phải có ít nhất 1 cafe_drink trong 200 m, đếm được %',
            anchor_name, 423736651, competitors[1];
    END IF;

    IF competitors[1] > competitors[2] OR competitors[2] > competitors[3]
        OR attractors[1] > attractors[2] OR attractors[2] > attractors[3] THEN
        RAISE EXCEPTION 'Số đếm giảm khi bán kính tăng. Đối thủ %, điểm hút %', competitors, attractors;
    END IF;

    reported_m := (profile -> 'nearest_arterial' ->> 'distance_m')::numeric;
    SELECT ST_Distance(road.geom::geography, ST_SetSRID(ST_MakePoint(anchor_lon, anchor_lat), 4326)::geography),
           ST_Distance(road.geom, ST_SetSRID(ST_MakePoint(anchor_lon, anchor_lat), 4326))
    INTO measured_m, degree_distance
    FROM road
    WHERE arterial
    ORDER BY road.geom::geography <-> ST_SetSRID(ST_MakePoint(anchor_lon, anchor_lat), 4326)::geography
    LIMIT 1;

    IF reported_m IS NULL OR abs(reported_m - measured_m) > 0.2 THEN
        RAISE EXCEPTION 'Khoảng cách trục đường % m không khớp phép đo geography % m', reported_m, measured_m;
    END IF;

    IF measured_m > 1 AND abs(reported_m - degree_distance) < 0.001 THEN
        RAISE EXCEPTION 'Khoảng cách trả về % trùng khoảng cách theo độ %, không phải mét',
            reported_m, degree_distance;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND indexname = 'road_arterial_geog_gix'
          AND indexdef ILIKE '%USING gist%'
    ) THEN
        RAISE EXCEPTION 'Thiếu chỉ mục GIST geography cho trục đường';
    END IF;

    outside := buffer_profile(105.0, 21.0, 'pharmacy');
    IF (outside -> 'rings' -> 2 ->> 'counts_complete')::boolean THEN
        RAISE EXCEPTION 'Điểm 105E 21N không được đánh dấu đếm đủ ở 1000 m';
    END IF;
    IF (outside -> 'rings' -> 2 ->> 'attractor_count')::int <> 0
        OR (outside -> 'rings' -> 2 ->> 'competitor_count')::int <> 0 THEN
        RAISE EXCEPTION 'Điểm cách vùng nghiên cứu hàng chục km vẫn đếm được POI: %', outside -> 'rings' -> 2;
    END IF;

    BEGIN
        PERFORM buffer_profile(105.8, 21.03, 'restaurant');
        RAISE EXCEPTION 'business_type restaurant đáng lẽ bị từ chối';
    EXCEPTION
        WHEN SQLSTATE '22023' THEN
            NULL;
    END;
END;
$$;
