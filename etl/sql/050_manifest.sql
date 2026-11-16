SELECT jsonb_pretty(jsonb_build_object(
    'generated_by', 'etl/import_osm.sh',
    'note', 'Số liệu sinh từ PostGIS sau lần nạp, không gõ tay.',
    'import', (
        SELECT to_jsonb(data_import) - 'id'
        FROM data_import
        ORDER BY id DESC
        LIMIT 1
    ),
    'districts', (
        SELECT jsonb_agg(jsonb_build_object(
            'name', name,
            'osm_name', osm_name,
            'boundary', boundary,
            'osm_id', osm_id,
            'osm_type', osm_type,
            'name_vi', name_vi,
            'name_en', name_en,
            'official_name', official_name,
            'area_km2', area_km2
        ) ORDER BY name)
        FROM district
    ),
    'poi_counts', (
        SELECT jsonb_agg(jsonb_build_object(
            'district', district_name,
            'category', category,
            'role', role,
            'count', n
        ) ORDER BY district_name, category)
        FROM (
            SELECT d.name AS district_name, p.category, p.role, count(*) AS n
            FROM poi AS p
            JOIN district AS d ON d.id = p.district_id
            GROUP BY d.name, p.category, p.role
        ) AS counts
    ),
    'roads', (
        SELECT jsonb_agg(jsonb_build_object(
            'district', district_name,
            'segment_count', segment_count,
            'arterial_segment_count', arterial_segment_count,
            'length_km', length_km,
            'arterial_length_km', arterial_length_km
        ) ORDER BY district_name)
        FROM (
            SELECT
                d.name AS district_name,
                count(*) AS segment_count,
                count(*) FILTER (WHERE r.arterial) AS arterial_segment_count,
                round((sum(rd.length_m) / 1000.0)::numeric, 3) AS length_km,
                round((sum(rd.length_m) FILTER (WHERE r.arterial) / 1000.0)::numeric, 3) AS arterial_length_km
            FROM road_district AS rd
            JOIN road AS r ON r.id = rd.road_id
            JOIN district AS d ON d.id = rd.district_id
            GROUP BY d.name
        ) AS road_counts
    ),
    'gist_indexes', (
        SELECT jsonb_agg(indexname ORDER BY indexname)
        FROM pg_indexes
        WHERE schemaname = 'public' AND indexdef ILIKE '%USING gist%'
    )
));
