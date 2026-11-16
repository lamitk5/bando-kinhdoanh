-- Kiểm tra luật phân loại trên dữ liệu giả lập trong phiên SQL, không ghi vào bảng POI.

CREATE TEMP TABLE classify_case (
    id int PRIMARY KEY,
    amenity text,
    shop text,
    office text,
    building text,
    railway text,
    public_transport text,
    highway text,
    expected text
);

INSERT INTO classify_case (
    id, amenity, shop, office, building, railway, public_transport, highway, expected
) VALUES
    (1, 'cafe', NULL, NULL, NULL, NULL, NULL, NULL, 'cafe_drink'),
    (2, NULL, 'coffee', NULL, NULL, NULL, NULL, NULL, 'cafe_drink'),
    (3, NULL, 'tea', NULL, NULL, NULL, NULL, NULL, 'cafe_drink'),
    (4, NULL, 'beverages', NULL, NULL, NULL, NULL, NULL, 'cafe_drink'),
    (5, 'restaurant', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    (6, 'bar', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    (7, 'fast_food', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    (8, NULL, 'supermarket', NULL, NULL, NULL, NULL, NULL, NULL),
    (9, 'pharmacy', 'convenience', NULL, NULL, NULL, NULL, NULL, 'pharmacy'),
    (10, NULL, 'chemist', NULL, NULL, NULL, NULL, NULL, 'pharmacy'),
    (11, NULL, 'convenience', NULL, NULL, NULL, NULL, NULL, 'convenience'),
    (12, 'hospital', NULL, NULL, NULL, NULL, NULL, NULL, 'hospital'),
    (13, 'clinic', NULL, NULL, NULL, NULL, NULL, NULL, 'clinic'),
    (14, 'school', NULL, NULL, NULL, NULL, NULL, NULL, 'education'),
    (15, 'kindergarten', NULL, NULL, NULL, NULL, NULL, NULL, 'education'),
    (16, 'university', NULL, NULL, NULL, NULL, NULL, NULL, 'education'),
    (17, 'college', NULL, NULL, NULL, NULL, NULL, NULL, 'education'),
    (18, NULL, NULL, NULL, 'apartments', NULL, NULL, NULL, 'apartments'),
    (19, NULL, NULL, NULL, 'residential', NULL, NULL, NULL, NULL),
    (20, NULL, NULL, NULL, 'dormitory', NULL, NULL, NULL, NULL),
    (21, NULL, NULL, 'company', NULL, NULL, NULL, NULL, 'office'),
    (22, NULL, NULL, 'vacant', NULL, NULL, NULL, NULL, NULL),
    (23, NULL, NULL, 'no', NULL, NULL, NULL, NULL, NULL),
    (24, NULL, NULL, NULL, NULL, NULL, NULL, 'bus_stop', 'transit_stop'),
    (25, NULL, NULL, NULL, NULL, 'station', NULL, NULL, 'transit_station'),
    (26, NULL, NULL, NULL, NULL, 'halt', NULL, NULL, 'transit_station'),
    (27, NULL, NULL, NULL, NULL, 'tram_stop', NULL, NULL, 'transit_stop'),
    (28, 'bus_station', NULL, NULL, NULL, NULL, NULL, NULL, 'transit_station'),
    (29, NULL, NULL, NULL, NULL, NULL, 'station', NULL, 'transit_station'),
    (30, NULL, NULL, NULL, NULL, NULL, 'stop_position', NULL, 'transit_stop'),
    (31, NULL, NULL, NULL, NULL, NULL, 'platform', NULL, 'transit_stop'),
    (32, 'fuel', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    (33, 'cafe', NULL, NULL, 'apartments', NULL, NULL, NULL, 'cafe_drink'),
    (34, NULL, NULL, 'company', 'apartments', NULL, NULL, NULL, 'apartments');

DO $$
DECLARE
    r record;
    got text;
BEGIN
    FOR r IN SELECT * FROM classify_case ORDER BY id LOOP
        SELECT category INTO got
        FROM classify_feature(
            r.amenity, r.shop, r.office, r.building, r.railway, r.public_transport, r.highway
        );
        IF got IS DISTINCT FROM r.expected THEN
            RAISE EXCEPTION 'classify case % expected %, got %', r.id, r.expected, got;
        END IF;
    END LOOP;

    IF district_canon('Quận Đống Đa', NULL, NULL) IS DISTINCT FROM 'Đống Đa' THEN
        RAISE EXCEPTION 'district_canon bỏ sót tiền tố Quận';
    END IF;
    IF district_canon('Hai Bà Trưng', NULL, NULL) IS NOT NULL THEN
        RAISE EXCEPTION 'district_canon nhận quận ngoài phạm vi';
    END IF;
    IF NOT hanoi_name('Thành phố Hà Nội', NULL, NULL) THEN
        RAISE EXCEPTION 'hanoi_name không nhận tên thành phố';
    END IF;
END $$;
