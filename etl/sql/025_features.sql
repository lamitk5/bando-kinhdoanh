-- Phân loại POI và đường từ bảng thô, gán vào quận đã kiểm tra.
-- Điểm nằm ngoài 4 quận bị loại. Điểm chạm biên nhưng không nằm trong được giữ và đánh dấu.

CREATE TABLE poi (
    id bigserial PRIMARY KEY,
    import_id bigint NOT NULL REFERENCES data_import (id),
    district_id bigint NOT NULL REFERENCES district (id),
    osm_id bigint NOT NULL,
    osm_type text NOT NULL CHECK (osm_type IN ('N', 'W', 'R')),
    name text,
    category text NOT NULL CHECK (category IN (
        'education', 'office', 'apartments', 'hospital', 'clinic',
        'transit_station', 'transit_stop',
        'cafe_drink', 'pharmacy', 'convenience'
    )),
    role text NOT NULL CHECK (role IN ('attractor', 'competitor')),
    business_type text,
    osm_key text NOT NULL,
    osm_value text NOT NULL,
    tags jsonb NOT NULL,
    source_geom geometry(Geometry, 4326) NOT NULL,
    geom geometry(Point, 4326) NOT NULL,
    on_boundary boolean NOT NULL,
    possible_duplicate boolean NOT NULL DEFAULT false,
    UNIQUE (import_id, osm_type, osm_id)
);

CREATE TABLE road (
    id bigserial PRIMARY KEY,
    import_id bigint NOT NULL REFERENCES data_import (id),
    osm_id bigint NOT NULL,
    osm_type text NOT NULL CHECK (osm_type = 'W'),
    name text,
    highway text NOT NULL,
    arterial boolean NOT NULL,
    tags jsonb NOT NULL,
    geom geometry(LineString, 4326) NOT NULL,
    length_m double precision NOT NULL CHECK (length_m >= 0),
    UNIQUE (import_id, osm_id)
);

CREATE TABLE road_district (
    road_id bigint NOT NULL REFERENCES road (id) ON DELETE CASCADE,
    district_id bigint NOT NULL REFERENCES district (id),
    length_m double precision NOT NULL CHECK (length_m > 0),
    PRIMARY KEY (road_id, district_id)
);

CREATE TEMP TABLE prepared_poi AS
SELECT
    trim(osm_type) AS osm_type,
    osm_id,
    name,
    amenity,
    shop,
    office,
    building,
    railway,
    public_transport,
    highway,
    coalesce(tags, '{}'::jsonb) AS tags,
    geom AS node_geom,
    CASE
        WHEN area_geom IS NULL THEN NULL
        WHEN ST_IsValid(area_geom) AND NOT ST_IsEmpty(area_geom) THEN area_geom
        WHEN NOT ST_IsEmpty(ST_CollectionExtract(ST_MakeValid(area_geom), 3))
            THEN ST_Multi(ST_CollectionExtract(ST_MakeValid(area_geom), 3))
        ELSE NULL
    END AS area_geom
FROM raw_poi;

CREATE TEMP TABLE classified_poi AS
SELECT
    p.osm_type,
    p.osm_id,
    p.name,
    p.tags,
    CASE
        WHEN p.area_geom IS NOT NULL THEN ST_PointOnSurface(p.area_geom)
        ELSE p.node_geom
    END AS geom,
    COALESCE(p.area_geom, p.node_geom) AS source_geom,
    c.category,
    c.role,
    c.business_type,
    c.osm_key,
    c.osm_value
FROM prepared_poi AS p
CROSS JOIN LATERAL classify_feature(
    p.amenity, p.shop, p.office, p.building, p.railway, p.public_transport, p.highway
) AS c
WHERE p.node_geom IS NOT NULL;

DELETE FROM classified_poi
WHERE geom IS NULL OR NOT ST_IsValid(geom) OR ST_IsEmpty(geom);

CREATE INDEX classified_poi_geom_gix ON classified_poi USING GIST (geom);
ANALYZE classified_poi;

DO $$
DECLARE
    duplicated bigint;
    overlapped bigint;
BEGIN
    SELECT count(*) INTO duplicated
    FROM (
        SELECT osm_type, osm_id
        FROM classified_poi
        GROUP BY osm_type, osm_id
        HAVING count(*) > 1
    ) AS extra;
    IF duplicated > 0 THEN
        RAISE EXCEPTION 'Có % phần tử OSM bị phân loại trùng', duplicated;
    END IF;

    SELECT count(*) INTO overlapped
    FROM (
        SELECT c.osm_type, c.osm_id
        FROM classified_poi AS c
        JOIN district AS d ON c.geom && d.geom AND ST_Contains(d.geom, c.geom)
        GROUP BY c.osm_type, c.osm_id
        HAVING count(*) > 1
    ) AS extra;
    IF overlapped > 0 THEN
        RAISE EXCEPTION 'Có % POI nằm trong hơn một quận', overlapped;
    END IF;
END $$;

INSERT INTO poi (
    import_id, district_id, osm_id, osm_type, name, category, role, business_type,
    osm_key, osm_value, tags, source_geom, geom, on_boundary
)
SELECT
    (SELECT id FROM data_import ORDER BY id DESC LIMIT 1),
    picked.district_id,
    picked.osm_id,
    picked.osm_type,
    picked.name,
    picked.category,
    picked.role,
    picked.business_type,
    picked.osm_key,
    picked.osm_value,
    picked.tags,
    picked.source_geom,
    picked.geom,
    NOT picked.any_inside
FROM (
    SELECT
        located.*,
        row_number() OVER (
            PARTITION BY located.osm_type, located.osm_id
            ORDER BY located.inside DESC, located.district_id
        ) AS rn,
        bool_or(located.inside) OVER (
            PARTITION BY located.osm_type, located.osm_id
        ) AS any_inside
    FROM (
        SELECT
            c.*,
            d.id AS district_id,
            ST_Contains(d.geom, c.geom) AS inside
        FROM classified_poi AS c
        JOIN district AS d ON c.geom && d.geom AND ST_Intersects(d.geom, c.geom)
    ) AS located
) AS picked
WHERE picked.rn = 1;

CREATE INDEX poi_geom_gix ON poi USING GIST (geom);
CREATE INDEX poi_geog_gix ON poi USING GIST ((geom::geography));
CREATE INDEX poi_district_category_idx ON poi (district_id, category);
ANALYZE poi;

UPDATE poi AS p
SET possible_duplicate = true
WHERE p.name IS NOT NULL
  AND length(trim(p.name)) > 0
  AND EXISTS (
      SELECT 1
      FROM poi AS other
      WHERE other.id <> p.id
        AND other.category = p.category
        AND other.district_id = p.district_id
        AND lower(trim(other.name)) = lower(trim(p.name))
        AND ST_DWithin(p.geom::geography, other.geom::geography, 30)
  );

INSERT INTO road (
    import_id, osm_id, osm_type, name, highway, arterial, tags, geom, length_m
)
SELECT
    (SELECT id FROM data_import ORDER BY id DESC LIMIT 1),
    r.osm_id,
    'W',
    r.name,
    r.highway,
    r.highway IN (
        'motorway', 'motorway_link', 'trunk', 'trunk_link',
        'primary', 'primary_link', 'secondary', 'secondary_link'
    ),
    r.tags,
    r.geom,
    ST_Length(r.geom::geography)
FROM raw_road AS r
WHERE r.geom IS NOT NULL
  AND ST_IsValid(r.geom)
  AND NOT ST_IsEmpty(r.geom)
  AND r.highway NOT IN (
      'proposed', 'construction', 'abandoned', 'razed', 'no',
      'platform', 'corridor', 'bus_stop', 'rest_area', 'services', 'elevator', 'steps'
  )
  AND EXISTS (
      SELECT 1
      FROM district AS d
      WHERE r.geom && d.geom AND ST_Intersects(d.geom, r.geom)
  );

CREATE INDEX road_geom_gix ON road USING GIST (geom);
ANALYZE road;

INSERT INTO road_district (road_id, district_id, length_m)
SELECT
    piece.road_id,
    piece.district_id,
    piece.length_m
FROM (
    SELECT
        r.id AS road_id,
        d.id AS district_id,
        ST_Length(ST_Intersection(r.geom, d.geom)::geography) AS length_m
    FROM road AS r
    JOIN district AS d ON r.geom && d.geom AND ST_Intersects(r.geom, d.geom)
) AS piece
WHERE piece.length_m > 0;

UPDATE data_import
SET raw_poi_count = (SELECT count(*) FROM raw_poi),
    classified_poi_count = (SELECT count(*) FROM classified_poi),
    poi_outside_count = (
        SELECT count(*)
        FROM classified_poi AS c
        WHERE NOT EXISTS (
            SELECT 1
            FROM district AS d
            WHERE c.geom && d.geom AND ST_Intersects(d.geom, c.geom)
        )
    ),
    poi_inserted = (SELECT count(*) FROM poi),
    poi_on_boundary = (SELECT count(*) FROM poi WHERE on_boundary),
    poi_possible_duplicate = (SELECT count(*) FROM poi WHERE possible_duplicate),
    raw_road_count = (SELECT count(*) FROM raw_road),
    road_inserted = (SELECT count(*) FROM road),
    study_pbf_sha256 = :'study_sha256',
    extract_strategy = :'extract_strategy'
WHERE id = (SELECT max(id) FROM data_import);
