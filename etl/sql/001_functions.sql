-- Hàm phân loại và chuẩn hóa tên quận.
-- Một phần tử OSM chỉ nhận một hạng mục, theo thứ tự ưu tiên trong hàm.

CREATE OR REPLACE FUNCTION district_canon(p_name text, p_name_vi text, p_official text)
RETURNS text
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT canon
    FROM (
        VALUES
            ('Cầu Giấy'),
            ('Đống Đa'),
            ('Thanh Xuân'),
            ('Bắc Từ Liêm')
    ) AS expected(canon)
    WHERE p_name IN (canon, 'Quận ' || canon)
       OR p_name_vi IN (canon, 'Quận ' || canon)
       OR p_official IN (canon, 'Quận ' || canon)
    LIMIT 1;
$$;

CREATE OR REPLACE FUNCTION hanoi_name(p_name text, p_name_vi text, p_official text)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT p_name IN ('Hà Nội', 'Thành phố Hà Nội')
        OR p_name_vi IN ('Hà Nội', 'Thành phố Hà Nội')
        OR p_official IN ('Hà Nội', 'Thành phố Hà Nội');
$$;

CREATE OR REPLACE FUNCTION classify_feature(
    amenity text,
    shop text,
    office text,
    building text,
    railway text,
    public_transport text,
    highway text
) RETURNS TABLE (
    category text,
    role text,
    business_type text,
    osm_key text,
    osm_value text
)
LANGUAGE plpgsql
IMMUTABLE
AS $$
BEGIN
    IF amenity = 'pharmacy' THEN
        category := 'pharmacy';
        role := 'competitor';
        business_type := 'pharmacy';
        osm_key := 'amenity';
        osm_value := amenity;
        RETURN NEXT;
        RETURN;
    END IF;

    IF shop = 'chemist' THEN
        category := 'pharmacy';
        role := 'competitor';
        business_type := 'pharmacy';
        osm_key := 'shop';
        osm_value := shop;
        RETURN NEXT;
        RETURN;
    END IF;

    IF shop = 'convenience' THEN
        category := 'convenience';
        role := 'competitor';
        business_type := 'convenience';
        osm_key := 'shop';
        osm_value := shop;
        RETURN NEXT;
        RETURN;
    END IF;

    IF amenity = 'cafe' THEN
        category := 'cafe_drink';
        role := 'competitor';
        business_type := 'cafe_drink';
        osm_key := 'amenity';
        osm_value := amenity;
        RETURN NEXT;
        RETURN;
    END IF;

    IF shop IN ('coffee', 'tea', 'beverages') THEN
        category := 'cafe_drink';
        role := 'competitor';
        business_type := 'cafe_drink';
        osm_key := 'shop';
        osm_value := shop;
        RETURN NEXT;
        RETURN;
    END IF;

    IF amenity = 'hospital' THEN
        category := 'hospital';
        role := 'attractor';
        business_type := NULL;
        osm_key := 'amenity';
        osm_value := amenity;
        RETURN NEXT;
        RETURN;
    END IF;

    IF amenity = 'clinic' THEN
        category := 'clinic';
        role := 'attractor';
        business_type := NULL;
        osm_key := 'amenity';
        osm_value := amenity;
        RETURN NEXT;
        RETURN;
    END IF;

    IF amenity IN ('school', 'university', 'college', 'kindergarten') THEN
        category := 'education';
        role := 'attractor';
        business_type := NULL;
        osm_key := 'amenity';
        osm_value := amenity;
        RETURN NEXT;
        RETURN;
    END IF;

    IF railway IN ('station', 'halt') THEN
        category := 'transit_station';
        role := 'attractor';
        business_type := NULL;
        osm_key := 'railway';
        osm_value := railway;
        RETURN NEXT;
        RETURN;
    END IF;

    IF amenity = 'bus_station' THEN
        category := 'transit_station';
        role := 'attractor';
        business_type := NULL;
        osm_key := 'amenity';
        osm_value := amenity;
        RETURN NEXT;
        RETURN;
    END IF;

    IF public_transport = 'station' THEN
        category := 'transit_station';
        role := 'attractor';
        business_type := NULL;
        osm_key := 'public_transport';
        osm_value := public_transport;
        RETURN NEXT;
        RETURN;
    END IF;

    IF highway = 'bus_stop' THEN
        category := 'transit_stop';
        role := 'attractor';
        business_type := NULL;
        osm_key := 'highway';
        osm_value := highway;
        RETURN NEXT;
        RETURN;
    END IF;

    IF railway = 'tram_stop' THEN
        category := 'transit_stop';
        role := 'attractor';
        business_type := NULL;
        osm_key := 'railway';
        osm_value := railway;
        RETURN NEXT;
        RETURN;
    END IF;

    IF public_transport IN ('stop_position', 'platform') THEN
        category := 'transit_stop';
        role := 'attractor';
        business_type := NULL;
        osm_key := 'public_transport';
        osm_value := public_transport;
        RETURN NEXT;
        RETURN;
    END IF;

    IF building = 'apartments' THEN
        category := 'apartments';
        role := 'attractor';
        business_type := NULL;
        osm_key := 'building';
        osm_value := building;
        RETURN NEXT;
        RETURN;
    END IF;

    IF office IS NOT NULL AND office NOT IN ('vacant', 'no') THEN
        category := 'office';
        role := 'attractor';
        business_type := NULL;
        osm_key := 'office';
        osm_value := office;
        RETURN NEXT;
        RETURN;
    END IF;

    RETURN;
END;
$$;
