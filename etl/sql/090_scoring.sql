-- Thang chuẩn hóa chấm điểm, tính từ lưới 500 m trong bốn quận.
-- Không ghi một trần mật độ bằng tay.

CREATE TABLE IF NOT EXISTS scoring_reference (
    id integer PRIMARY KEY CHECK (id = 1),
    computed_at timestamptz NOT NULL,
    sample_count integer NOT NULL CHECK (sample_count >= 30),
    grid_size_m integer NOT NULL CHECK (grid_size_m = 500),
    grid_srid integer NOT NULL CHECK (grid_srid = 32648),
    attractor_density_p95 double precision NOT NULL CHECK (attractor_density_p95 > 0),
    cafe_competitor_density_p95 double precision NOT NULL CHECK (cafe_competitor_density_p95 > 0),
    pharmacy_competitor_density_p95 double precision NOT NULL CHECK (pharmacy_competitor_density_p95 > 0),
    convenience_competitor_density_p95 double precision NOT NULL CHECK (convenience_competitor_density_p95 > 0),
    arterial_distance_median_m double precision NOT NULL CHECK (arterial_distance_median_m > 0),
    method text NOT NULL
);

CREATE OR REPLACE FUNCTION refresh_scoring_reference()
RETURNS jsonb
LANGUAGE plpgsql
AS $$
DECLARE
    stored scoring_reference%ROWTYPE;
BEGIN
    WITH study AS (
        SELECT ST_UnaryUnion(ST_Collect(geom)) AS geom FROM district
    ),
    utm AS (
        SELECT ST_Transform(geom, 32648) AS geom FROM study
    ),
    cells AS (
        SELECT (ST_SquareGrid(500, geom)).geom AS cell FROM utm
    ),
    samples AS (
        SELECT ST_Transform(
            ST_PointOnSurface(ST_Intersection(cells.cell, utm.geom)),
            4326
        )::geography AS geog
        FROM cells
        CROSS JOIN utm
        WHERE ST_Intersects(cells.cell, utm.geom)
          AND ST_Area(ST_Intersection(cells.cell, utm.geom)) >= 62500
    ),
    observed AS (
        SELECT
            attractors / (area_m2 / 1000000.0) AS attractor_density,
            cafes / (area_m2 / 1000000.0) AS cafe_density,
            pharmacies / (area_m2 / 1000000.0) AS pharmacy_density,
            conveniences / (area_m2 / 1000000.0) AS convenience_density,
            arterial_m
        FROM (
            SELECT
                ST_Area(ST_Intersection(
                    ST_Buffer(samples.geog, 500),
                    (SELECT ST_UnaryUnion(ST_Collect(geom))::geography FROM district)
                )) AS area_m2,
                (SELECT count(*) FROM counted_poi AS poi
                 WHERE poi.role = 'attractor' AND ST_DWithin(poi.geom::geography, samples.geog, 500)) AS attractors,
                (SELECT count(*) FROM counted_poi AS poi
                 WHERE poi.category = 'cafe_drink' AND ST_DWithin(poi.geom::geography, samples.geog, 500)) AS cafes,
                (SELECT count(*) FROM counted_poi AS poi
                 WHERE poi.category = 'pharmacy' AND ST_DWithin(poi.geom::geography, samples.geog, 500)) AS pharmacies,
                (SELECT count(*) FROM counted_poi AS poi
                 WHERE poi.category = 'convenience' AND ST_DWithin(poi.geom::geography, samples.geog, 500)) AS conveniences,
                (SELECT ST_Distance(road.geom::geography, samples.geog)
                 FROM road WHERE arterial
                 ORDER BY road.geom::geography <-> samples.geog
                 LIMIT 1) AS arterial_m
            FROM samples
        ) AS measured
        WHERE area_m2 > 0 AND arterial_m IS NOT NULL
    ),
    summary AS (
        SELECT
            count(*)::integer AS sample_count,
            percentile_cont(0.95) WITHIN GROUP (ORDER BY attractor_density) AS attractor_density_p95,
            percentile_cont(0.95) WITHIN GROUP (ORDER BY cafe_density) AS cafe_competitor_density_p95,
            percentile_cont(0.95) WITHIN GROUP (ORDER BY pharmacy_density) AS pharmacy_competitor_density_p95,
            percentile_cont(0.95) WITHIN GROUP (ORDER BY convenience_density) AS convenience_competitor_density_p95,
            percentile_cont(0.50) WITHIN GROUP (ORDER BY arterial_m) AS arterial_distance_median_m
        FROM observed
    )
    INSERT INTO scoring_reference (
        id, computed_at, sample_count, grid_size_m, grid_srid,
        attractor_density_p95, cafe_competitor_density_p95,
        pharmacy_competitor_density_p95, convenience_competitor_density_p95,
        arterial_distance_median_m, method
    )
    SELECT
        1, clock_timestamp(), sample_count, 500, 32648,
        attractor_density_p95, cafe_competitor_density_p95,
        pharmacy_competitor_density_p95, convenience_competitor_density_p95,
        arterial_distance_median_m,
        'Lưới ô vuông 500 m trong EPSG:32648, bỏ ô giao quận dưới 62500 m². Mật độ vòng 500 m trên phần diện tích nằm trong bốn quận. P95 cho mật độ, trung vị cho khoảng cách trục đường.'
    FROM summary
    ON CONFLICT (id) DO UPDATE SET
        computed_at = EXCLUDED.computed_at,
        sample_count = EXCLUDED.sample_count,
        grid_size_m = EXCLUDED.grid_size_m,
        grid_srid = EXCLUDED.grid_srid,
        attractor_density_p95 = EXCLUDED.attractor_density_p95,
        cafe_competitor_density_p95 = EXCLUDED.cafe_competitor_density_p95,
        pharmacy_competitor_density_p95 = EXCLUDED.pharmacy_competitor_density_p95,
        convenience_competitor_density_p95 = EXCLUDED.convenience_competitor_density_p95,
        arterial_distance_median_m = EXCLUDED.arterial_distance_median_m,
        method = EXCLUDED.method;

    SELECT * INTO stored FROM scoring_reference WHERE id = 1;
    RETURN jsonb_build_object(
        'sample_count', stored.sample_count,
        'grid_size_m', stored.grid_size_m,
        'grid_srid', stored.grid_srid,
        'attractor_density_p95', round(stored.attractor_density_p95::numeric, 2),
        'cafe_competitor_density_p95', round(stored.cafe_competitor_density_p95::numeric, 2),
        'pharmacy_competitor_density_p95', round(stored.pharmacy_competitor_density_p95::numeric, 2),
        'convenience_competitor_density_p95', round(stored.convenience_competitor_density_p95::numeric, 2),
        'arterial_distance_median_m', round(stored.arterial_distance_median_m::numeric, 1),
        'computed_at', stored.computed_at,
        'method', stored.method,
        'unit_density', 'poi_per_km2',
        'unit_distance', 'meter'
    );
END;
$$;

CREATE OR REPLACE FUNCTION location_indicators(
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
    full_area double precision;
    area_m2 double precision;
    attractors integer;
    competitors integer;
    arterial_m numeric;
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
    IF NOT ST_Covers(study, pt) THEN
        RAISE EXCEPTION 'Điểm chấm phải nằm trong bốn quận' USING ERRCODE = '22023';
    END IF;

    full_area := ST_Area(ST_Buffer(pt, 500));
    area_m2 := ST_Area(ST_Intersection(ST_Buffer(pt, 500), study));
    IF area_m2 <= 0 OR full_area <= 0 THEN
        RAISE EXCEPTION 'Không tính được diện tích vòng 500 m' USING ERRCODE = '22023';
    END IF;

    SELECT count(*) INTO attractors
    FROM counted_poi
    WHERE role = 'attractor' AND ST_DWithin(geom::geography, pt, 500);
    SELECT count(*) INTO competitors
    FROM counted_poi
    WHERE category = p_business AND ST_DWithin(geom::geography, pt, 500);
    SELECT round(ST_Distance(road.geom::geography, pt)::numeric, 1) INTO arterial_m
    FROM road
    WHERE arterial
    ORDER BY road.geom::geography <-> pt
    LIMIT 1;

    RETURN jsonb_build_object(
        'radius_m', 500,
        'searched_area_m2', round(area_m2::numeric, 1),
        'fraction_inside_study_area', round((area_m2 / full_area)::numeric, 4),
        'counts_complete', area_m2 / full_area >= 0.999,
        'attractor_count', attractors,
        'competitor_count', competitors,
        'attractor_density_per_km2', round((attractors / (area_m2 / 1000000.0))::numeric, 2),
        'competitor_density_per_km2', round((competitors / (area_m2 / 1000000.0))::numeric, 2),
        'arterial_distance_m', arterial_m,
        'density_rule', 'Mật độ = số điểm trên counted_poi chia cho diện tích phần vòng 500 m nằm trong bốn quận, đơn vị điểm trên km².'
    );
END;
$$;
