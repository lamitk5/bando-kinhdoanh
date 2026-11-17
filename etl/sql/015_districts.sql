-- Tạo lần nạp và 4 quận từ raw_admin. Chưa đọc POI.
-- Không xóa poi_contribution: đóng góp hiện trường phải sống qua lần nạp lại.

DROP TABLE IF EXISTS road_district CASCADE;
DROP TABLE IF EXISTS poi CASCADE;
DROP TABLE IF EXISTS road CASCADE;
DROP TABLE IF EXISTS district CASCADE;
DROP TABLE IF EXISTS data_import CASCADE;

CREATE TABLE data_import (
    id bigserial PRIMARY KEY,
    source_url text NOT NULL,
    resolved_url text NOT NULL,
    sha256 text NOT NULL CHECK (char_length(sha256) = 64),
    byte_size bigint NOT NULL CHECK (byte_size > 100000000),
    md5_geofabrik text NOT NULL,
    md5_ok boolean NOT NULL CHECK (md5_ok),
    retrieved_at timestamptz NOT NULL,
    http_last_modified timestamptz,
    osm_header_timestamp timestamptz NOT NULL,
    study_pbf_sha256 text,
    extract_strategy text,
    osmium_version text NOT NULL,
    osm2pgsql_version text NOT NULL CHECK (length(osm2pgsql_version) > 0),
    postgis_version text NOT NULL,
    attribution text NOT NULL,
    raw_poi_count bigint,
    classified_poi_count bigint,
    poi_outside_count bigint,
    poi_inserted bigint,
    poi_on_boundary bigint,
    poi_possible_duplicate bigint,
    raw_road_count bigint,
    road_inserted bigint,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE district (
    id bigserial PRIMARY KEY,
    import_id bigint NOT NULL REFERENCES data_import (id),
    osm_id bigint NOT NULL,
    osm_type text NOT NULL CHECK (osm_type = 'R'),
    name text NOT NULL,
    osm_name text NOT NULL,
    boundary text NOT NULL,
    name_vi text,
    name_en text,
    official_name text,
    admin_level int NOT NULL CHECK (admin_level = 6),
    area_km2 double precision NOT NULL,
    geom geometry(MultiPolygon, 4326) NOT NULL,
    UNIQUE (import_id, name)
);

INSERT INTO data_import (
    source_url,
    resolved_url,
    sha256,
    byte_size,
    md5_geofabrik,
    md5_ok,
    retrieved_at,
    http_last_modified,
    osm_header_timestamp,
    osmium_version,
    osm2pgsql_version,
    postgis_version,
    attribution
) VALUES (
    :'source_url',
    :'resolved_url',
    :'sha256',
    :'byte_size',
    :'md5_geofabrik',
    :'md5_ok',
    :'retrieved_at',
    NULLIF(:'http_last_modified', '')::timestamptz,
    :'osm_header_timestamp',
    :'osmium_version',
    :'osm2pgsql_version',
    :'postgis_version',
    :'attribution'
);

INSERT INTO district (
    import_id, osm_id, osm_type, name, osm_name, boundary, name_vi, name_en, official_name, admin_level, area_km2, geom
)
SELECT
    currval('data_import_id_seq'),
    osm_id,
    'R',
    canon,
    osm_name,
    boundary,
    name_vi,
    tags ->> 'name:en',
    tags ->> 'official_name',
    was_admin_level,
    ST_Area(geom::geography) / 1000000.0,
    geom
FROM (
    SELECT DISTINCT ON (canon)
        osm_id,
        osm_name,
        boundary,
        name_vi,
        tags,
        was_admin_level,
        canon,
        ST_Multi(ST_CollectionExtract(ST_MakeValid(geom), 3)) AS geom
    FROM (
        SELECT
            osm_id,
            name AS osm_name,
            boundary,
            tags ->> 'name:vi' AS name_vi,
            tags,
            was_admin_level,
            geom,
            district_canon(name, tags ->> 'name:vi', tags ->> 'official_name') AS canon
        FROM raw_historic_district
        WHERE boundary = 'historic'
          AND was_admin_level = 6
    ) AS named
    WHERE canon IS NOT NULL
      AND geom IS NOT NULL
    ORDER BY canon, ST_Area(geom::geography) DESC
) AS picked
WHERE GeometryType(geom) IN ('POLYGON', 'MULTIPOLYGON')
  AND NOT ST_IsEmpty(geom);

CREATE INDEX district_geom_gix ON district USING GIST (geom);
ANALYZE district;
