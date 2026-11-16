SELECT coalesce(jsonb_agg(row_to_json(s)::jsonb ORDER BY s.category, s.osm_id), '[]'::jsonb)
FROM (
    SELECT category, osm_id, name, osm_key, osm_value, ST_Y(geom) AS lat, ST_X(geom) AS lon
    FROM (
        SELECT
            category,
            osm_id,
            name,
            osm_key,
            osm_value,
            geom,
            row_number() OVER (PARTITION BY category ORDER BY osm_id) AS rn
        FROM poi
        WHERE osm_type = 'N'
    ) AS ranked
    WHERE rn <= 2
) AS s;
