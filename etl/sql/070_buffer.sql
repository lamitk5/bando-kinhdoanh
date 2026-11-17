-- Vùng đệm 200/500/1000 m. Đơn vị là mét trên geography, không phải độ.
-- Hàm đọc counted_poi (POI OpenStreetMap và đóng góp đã duyệt), road, district.

CREATE INDEX IF NOT EXISTS road_arterial_geog_gix
    ON road USING GIST ((geom::geography))
    WHERE arterial;

CREATE OR REPLACE FUNCTION buffer_profile(
    p_lon double precision,
    p_lat double precision,
    p_business text
) RETURNS jsonb
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    pt geography;
    study geography;
    inside_area boolean;
    rings jsonb;
    arterial jsonb;
BEGIN
    IF p_business IS NULL OR p_business NOT IN ('cafe_drink', 'pharmacy', 'convenience') THEN
        RAISE EXCEPTION 'business_type phải là cafe_drink, pharmacy hoặc convenience'
            USING ERRCODE = '22023';
    END IF;

    IF p_lon IS NULL OR p_lat IS NULL
        OR p_lon < 102 OR p_lon > 110
        OR p_lat < 8 OR p_lat > 24 THEN
        RAISE EXCEPTION 'Tọa độ nằm ngoài phạm vi Việt Nam (kinh độ 102–110, vĩ độ 8–24)'
            USING ERRCODE = '22023';
    END IF;

    pt := ST_SetSRID(ST_MakePoint(p_lon, p_lat), 4326)::geography;

    SELECT ST_UnaryUnion(ST_Collect(geom))::geography INTO study FROM district;
    IF study IS NULL THEN
        RAISE EXCEPTION 'Chưa có vùng nghiên cứu';
    END IF;

    inside_area := ST_Covers(study, pt);

    WITH radii AS (
        SELECT radius_m
        FROM (VALUES (200), (500), (1000)) AS listed(radius_m)
    ),
    counts AS (
        SELECT
            radii.radius_m,
            count(poi.id) FILTER (WHERE poi.role = 'attractor') AS attractor_count,
            count(poi.id) FILTER (WHERE poi.category = p_business) AS competitor_count,
            count(poi.id) FILTER (WHERE poi.origin = 'contribution' AND poi.role = 'attractor') AS contribution_attractor_count,
            count(poi.id) FILTER (WHERE poi.origin = 'contribution' AND poi.category = p_business) AS contribution_competitor_count
        FROM radii
        LEFT JOIN counted_poi AS poi ON ST_DWithin(poi.geom::geography, pt, radii.radius_m)
        GROUP BY radii.radius_m
    ),
    breakdown AS (
        SELECT radii.radius_m, poi.category, count(*) AS n
        FROM radii
        JOIN counted_poi AS poi
            ON poi.role = 'attractor'
           AND ST_DWithin(poi.geom::geography, pt, radii.radius_m)
        GROUP BY radii.radius_m, poi.category
    ),
    coverage AS (
        SELECT
            radii.radius_m,
            ST_Area(ST_Intersection(ST_Buffer(pt, radii.radius_m), study))
                / ST_Area(ST_Buffer(pt, radii.radius_m)) AS fraction
        FROM radii
    )
    SELECT jsonb_agg(
        jsonb_build_object(
            'radius_m', counts.radius_m,
            'fraction_inside_study_area', round(coverage.fraction::numeric, 4),
            'counts_complete', coverage.fraction >= 0.999,
            'attractor_count', counts.attractor_count,
            'competitor_count', counts.competitor_count,
            'contribution_attractor_count', counts.contribution_attractor_count,
            'contribution_competitor_count', counts.contribution_competitor_count,
            'attractors_by_category', coalesce(by_category.cats, '{}'::jsonb)
        )
        ORDER BY counts.radius_m
    )
    INTO rings
    FROM counts
    JOIN coverage ON coverage.radius_m = counts.radius_m
    LEFT JOIN (
        SELECT radius_m, jsonb_object_agg(category, n) AS cats
        FROM breakdown
        GROUP BY radius_m
    ) AS by_category ON by_category.radius_m = counts.radius_m;

    SELECT jsonb_build_object(
        'distance_m', round(ST_Distance(road.geom::geography, pt)::numeric, 1),
        'name', road.name,
        'highway', road.highway,
        'osm_id', road.osm_id,
        'osm_type', 'W'
    )
    INTO arterial
    FROM road
    WHERE road.arterial
    ORDER BY road.geom::geography <-> pt
    LIMIT 1;

    RETURN jsonb_build_object(
        'lon', p_lon,
        'lat', p_lat,
        'business_type', p_business,
        'inside_study_area', inside_area,
        'radii_m', jsonb_build_array(200, 500, 1000),
        'distance_unit', 'meter',
        'srid', 4326,
        'count_rule', 'Đếm POI OpenStreetMap và đóng góp đã duyệt còn nằm trong bốn quận, kể cả điểm đúng tại tọa độ hỏi. Đóng góp đang chờ hoặc bị từ chối không được tính. Không phải điểm số.',
        'counts_complete_rule', 'counts_complete khi ít nhất 0.999 diện tích vòng tròn nằm trong bốn quận đã nạp. Dưới ngưỡng này số đếm thiếu điểm ngoài vùng.',
        'nearest_arterial_rule', 'Khoảng cách tới tuyến arterial đã nạp trong bốn quận, tính bằng mét. Đường nằm trọn ngoài vùng không có trong bảng, nên với điểm sát mép hoặc ngoài vùng khoảng cách này có thể lớn hơn khoảng cách thật. Tuyến cắt biên được giữ nguyên, nên điểm gần nhất có thể nằm ngoài bốn quận và khoảng cách có thể ngắn hơn khoảng cách tới đoạn nằm trong vùng.',
        'rings', rings,
        'nearest_arterial', arterial
    );
END;
$$;
