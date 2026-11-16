-- Dừng nếu chưa đủ đúng 4 quận, hoặc quận không nằm trong địa phận Hà Nội.

DO $$
DECLARE
    district_count int;
    hanoi_id bigint;
    outside_name text;
    coverage double precision;
BEGIN
    SELECT count(*) INTO district_count FROM district;
    IF district_count <> 4 THEN
        RAISE EXCEPTION
            'Cần đúng 4 quận historic, đang có %. Bảng raw_historic_district: %',
            district_count,
            (
                SELECT string_agg(
                    format('%s osm=%s boundary=%s km2=%s', name, osm_id, boundary, round((ST_Area(geom::geography) / 1000000.0)::numeric, 3)),
                    '; '
                )
                FROM raw_historic_district
            );
    END IF;

    IF EXISTS (
        SELECT 1
        FROM district
        WHERE name NOT IN ('Cầu Giấy', 'Đống Đa', 'Thanh Xuân', 'Bắc Từ Liêm')
           OR area_km2 < 4
           OR area_km2 > 80
           OR ST_SRID(geom) <> 4326
           OR NOT ST_IsValid(geom)
    ) THEN
        RAISE EXCEPTION 'Quận sai tên, sai SRID, hình học hỏng, hoặc diện tích ngoài khoảng 4–80 km2: %',
            (SELECT string_agg(format('%s=%s km2', name, round(area_km2::numeric, 3)), ', ') FROM district);
    END IF;

    SELECT osm_id INTO hanoi_id
    FROM raw_admin
    WHERE admin_level = 4
      AND hanoi_name(name, name_vi, tags ->> 'official_name')
      AND geom IS NOT NULL
    ORDER BY ST_Area(ST_MakeValid(geom)::geography) DESC
    LIMIT 1;

    IF hanoi_id IS NULL THEN
        RAISE EXCEPTION 'Không thấy polygon Hà Nội admin_level=4. Các tên level 4: %',
            (
                SELECT string_agg(format('%s (vi=%s)', name, name_vi), '; ')
                FROM (
                    SELECT name, name_vi
                    FROM raw_admin
                    WHERE admin_level = 4
                    ORDER BY ST_Area(geom::geography) DESC
                    LIMIT 15
                ) AS names
            );
    END IF;

    SELECT d.name,
           ST_Area(ST_Intersection(d.geom, ST_MakeValid(h.geom))::geography) / NULLIF(ST_Area(d.geom::geography), 0)
    INTO outside_name, coverage
    FROM district AS d
    JOIN raw_admin AS h ON h.osm_id = hanoi_id AND h.admin_level = 4
    ORDER BY ST_Area(ST_Intersection(d.geom, ST_MakeValid(h.geom))::geography) / NULLIF(ST_Area(d.geom::geography), 0) ASC
    LIMIT 1;

    IF coverage IS NULL OR coverage < 0.95 THEN
        RAISE EXCEPTION 'Quận % chỉ phủ % trong polygon Hà Nội', outside_name, round(coalesce(coverage, 0)::numeric, 4);
    END IF;

    RAISE NOTICE 'Bốn quận: %',
        (SELECT string_agg(format('%s osm=%s km2=%s', name, osm_id, round(area_km2::numeric, 3)), ', ') FROM district);
END $$;
