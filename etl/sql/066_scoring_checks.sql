-- Bất biến của thang chấm. Không khóa một phân vị gõ tay.

DO $$
DECLARE
    first jsonb;
    second jsonb;
    kafa jsonb;
BEGIN
    first := refresh_scoring_reference();
    second := refresh_scoring_reference();

    IF (first ->> 'sample_count')::integer < 200
        OR (first ->> 'sample_count')::integer > 800
        OR (first ->> 'grid_size_m')::integer <> 500
        OR (first ->> 'grid_srid')::integer <> 32648 THEN
        RAISE EXCEPTION 'Lưới tham chiếu không đúng: %', first;
    END IF;
    IF (first ->> 'attractor_density_p95')::numeric <= (first ->> 'cafe_competitor_density_p95')::numeric
        OR (first ->> 'arterial_distance_median_m')::numeric <= 20
        OR (first ->> 'arterial_distance_median_m')::numeric >= 2000 THEN
        RAISE EXCEPTION 'Thang mật độ hoặc khoảng cách nằm ngoài miền kiểm: %', first;
    END IF;
    IF (first ->> 'sample_count') IS DISTINCT FROM (second ->> 'sample_count')
        OR (first ->> 'attractor_density_p95') IS DISTINCT FROM (second ->> 'attractor_density_p95')
        OR (first ->> 'arterial_distance_median_m') IS DISTINCT FROM (second ->> 'arterial_distance_median_m') THEN
        RAISE EXCEPTION 'Tính lại thang không ra cùng số: % và %', first, second;
    END IF;

    SELECT location_indicators(ST_X(geom), ST_Y(geom), 'cafe_drink') INTO kafa
    FROM poi
    WHERE osm_type = 'N' AND osm_id = 423736651 AND category = 'cafe_drink';
    IF kafa IS NULL THEN
        RAISE EXCEPTION 'Không thấy node 423736651 để kiểm tra chỉ số';
    END IF;
    IF (kafa ->> 'radius_m')::integer <> 500
        OR (kafa ->> 'competitor_count')::integer < 1
        OR (kafa ->> 'attractor_density_per_km2')::numeric <= 0
        OR (kafa ->> 'arterial_distance_m')::numeric < 5
        OR (kafa ->> 'arterial_distance_m')::numeric > 20
        OR NOT (kafa ->> 'counts_complete')::boolean THEN
        RAISE EXCEPTION 'Chỉ số tại Cà Phê Kafa lệch miền đã biết: %', kafa;
    END IF;

    BEGIN
        PERFORM location_indicators(105, 21, 'pharmacy');
        RAISE EXCEPTION 'Điểm ngoài bốn quận đáng lẽ không được chấm';
    EXCEPTION
        WHEN SQLSTATE '22023' THEN
            NULL;
    END;
END;
$$;
