-- Điều kiện dừng. Không kiểm tra một con số kỳ vọng gõ tay.

DO $$
DECLARE
    missing text;
    bad bigint;
BEGIN
    SELECT string_agg(expected.name, ', ') INTO missing
    FROM (
        VALUES ('Cầu Giấy'), ('Đống Đa'), ('Thanh Xuân'), ('Bắc Từ Liêm')
    ) AS expected(name)
    WHERE NOT EXISTS (SELECT 1 FROM district AS d WHERE d.name = expected.name);

    IF missing IS NOT NULL OR (SELECT count(*) FROM district) <> 4 THEN
        RAISE EXCEPTION 'Bộ quận không đúng, thiếu: %', coalesce(missing, '(thừa quận)');
    END IF;

    SELECT count(*) INTO bad
    FROM poi
    WHERE ST_SRID(geom) <> 4326
       OR ST_SRID(source_geom) <> 4326
       OR NOT ST_IsValid(geom)
       OR tags ->> osm_key IS DISTINCT FROM osm_value
       OR (role = 'competitor' AND business_type IS NULL)
       OR (role = 'attractor' AND business_type IS NOT NULL)
       OR name LIKE 'FIXTURE%';
    IF bad > 0 THEN
        RAISE EXCEPTION '% POI sai SRID, sai thẻ, sai vai trò, hoặc là fixture', bad;
    END IF;

    SELECT count(*) INTO bad
    FROM poi AS p
    JOIN district AS d ON d.id = p.district_id
    WHERE NOT ST_Intersects(d.geom, p.geom)
       OR (NOT p.on_boundary AND NOT ST_Contains(d.geom, p.geom));
    IF bad > 0 THEN
        RAISE EXCEPTION '% POI không nằm trong quận đã gán', bad;
    END IF;

    SELECT count(*) INTO bad FROM road WHERE ST_SRID(geom) <> 4326 OR NOT ST_IsValid(geom);
    IF bad > 0 THEN
        RAISE EXCEPTION '% đường sai SRID hoặc hình học', bad;
    END IF;

    IF (SELECT count(*) FROM road WHERE arterial) = 0 THEN
        RAISE EXCEPTION 'Không có trục đường chính';
    END IF;

    SELECT count(*) INTO bad
    FROM (
        VALUES
            ('education'), ('office'), ('apartments'), ('hospital'), ('clinic'),
            ('transit_station'), ('transit_stop'),
            ('cafe_drink'), ('pharmacy'), ('convenience')
    ) AS required(category)
    WHERE NOT EXISTS (
        SELECT 1 FROM poi WHERE poi.category = required.category
    );
    IF bad > 0 THEN
        RAISE EXCEPTION 'Thiếu hạng mục POI sau khi nạp. Đếm: %',
            (SELECT string_agg(format('%s=%s', category, n), ', ')
             FROM (SELECT category, count(*) AS n FROM poi GROUP BY category) AS counts);
    END IF;

    SELECT string_agg(required.index_name, ', ') INTO missing
    FROM (
        VALUES ('district_geom_gix'), ('poi_geom_gix'), ('road_geom_gix')
    ) AS required(index_name)
    WHERE NOT EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND indexname = required.index_name
          AND indexdef ILIKE '%USING gist%'
    );
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'Thiếu chỉ mục GIST: %', missing;
    END IF;

    IF coalesce((SELECT length(osm2pgsql_version) FROM data_import ORDER BY id DESC LIMIT 1), 0) = 0 THEN
        RAISE EXCEPTION 'Lần nạp không ghi phiên bản osm2pgsql';
    END IF;
END $$;
