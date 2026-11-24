-- Kiểm tra cổng kiểm duyệt rồi ROLLBACK. Không nằm trong etl/import_osm.sh.
-- Khối này không được để lại dòng và không được xóa đóng góp có sẵn.

BEGIN;

DO $$
DECLARE
    anchor geometry;
    sample_lon double precision;
    sample_lat double precision;
    before jsonb;
    pending jsonb;
    approved jsonb;
    duplicate jsonb;
    rejected jsonb;
    self_review jsonb;
    after jsonb;
    competitor_before integer;
    competitor_pending integer;
    competitor_approved integer;
    contribution_before integer;
    contribution_part integer;
    epoch_before bigint;
    epoch_after bigint;
    created_ids bigint[] := ARRAY[]::bigint[];
    attractor_before integer;
    attractor_approved integer;
BEGIN
    SELECT geom INTO anchor
    FROM poi
    WHERE osm_type = 'N' AND osm_id = 423736651 AND category = 'cafe_drink';
    IF anchor IS NULL THEN
        RAISE EXCEPTION 'Không thấy node OSM 423736651 để đặt điểm kiểm tra';
    END IF;

    sample_lon := ST_X(ST_Project(anchor::geography, 15, 0)::geometry);
    sample_lat := ST_Y(ST_Project(anchor::geography, 15, 0)::geometry);

    before := buffer_profile(ST_X(anchor), ST_Y(anchor), 'cafe_drink');
    competitor_before := (before -> 'rings' -> 0 ->> 'competitor_count')::integer;
    attractor_before := (before -> 'rings' -> 0 ->> 'attractor_count')::integer;
    contribution_before := (before -> 'rings' -> 0 ->> 'contribution_competitor_count')::integer;
    SELECT epoch INTO epoch_before FROM contribution_epoch WHERE id = 1;

    pending := submit_poi_contribution(
        'KIỂM TRA ĐÓNG GÓP', 'cafe_drink', sample_lon, sample_lat, 'dòng kiểm tra', 'bộ kiểm tra'
    );
    IF pending ->> 'status' IS DISTINCT FROM 'pending'
        OR (pending ->> 'counted_in_buffer')::boolean
        OR pending ->> 'role' IS DISTINCT FROM 'competitor'
        OR (pending ->> 'possible_duplicate')::boolean
        OR pending ->> 'source' IS DISTINCT FROM 'field' THEN
        RAISE EXCEPTION 'Đóng góp mới không ở trạng thái chờ: %', pending;
    END IF;
    created_ids := created_ids || (pending ->> 'id')::bigint;

    competitor_pending := (
        buffer_profile(ST_X(anchor), ST_Y(anchor), 'cafe_drink') -> 'rings' -> 0 ->> 'competitor_count'
    )::integer;
    IF competitor_pending <> competitor_before THEN
        RAISE EXCEPTION 'Đóng góp đang chờ đã làm đổi số đối thủ từ % thành %', competitor_before, competitor_pending;
    END IF;

    approved := decide_poi_contribution((pending ->> 'id')::bigint, 'approved', NULL, 'người duyệt kiểm tra');
    IF approved ->> 'status' IS DISTINCT FROM 'approved'
        OR NOT (approved ->> 'counted_in_buffer')::boolean THEN
        RAISE EXCEPTION 'Duyệt không đưa điểm vào vùng đệm: %', approved;
    END IF;

    competitor_approved := (
        buffer_profile(ST_X(anchor), ST_Y(anchor), 'cafe_drink') -> 'rings' -> 0 ->> 'competitor_count'
    )::integer;
    contribution_part := (
        buffer_profile(ST_X(anchor), ST_Y(anchor), 'cafe_drink') -> 'rings' -> 0 ->> 'contribution_competitor_count'
    )::integer;
    attractor_approved := (
        buffer_profile(ST_X(anchor), ST_Y(anchor), 'cafe_drink') -> 'rings' -> 0 ->> 'attractor_count'
    )::integer;
    SELECT epoch INTO epoch_after FROM contribution_epoch WHERE id = 1;
    IF competitor_approved <> competitor_before + 1
        OR contribution_part <> contribution_before + 1
        OR epoch_after <> epoch_before + 1 THEN
        RAISE EXCEPTION 'Sau duyệt, đối thủ % (phần đóng góp %, epoch %) không tăng đúng 1 so với % / % / %',
            competitor_approved, contribution_part, epoch_after, competitor_before, contribution_before, epoch_before;
    END IF;
    IF attractor_approved <> attractor_before THEN
        RAISE EXCEPTION 'Duyệt một quán cà phê làm đổi số điểm hút từ % thành %', attractor_before, attractor_approved;
    END IF;

    BEGIN
        PERFORM decide_poi_contribution((pending ->> 'id')::bigint, 'rejected', 'không được duyệt lần hai', 'người duyệt kiểm tra');
        RAISE EXCEPTION 'Đóng góp đã duyệt vẫn bị quyết định lại';
    EXCEPTION
        WHEN SQLSTATE 'P0001' THEN
            NULL;
    END;

    duplicate := submit_poi_contribution(
        'Cà Phê Kafa', 'cafe_drink', sample_lon, sample_lat, NULL, 'bộ kiểm tra'
    );
    created_ids := created_ids || (duplicate ->> 'id')::bigint;
    IF NOT (duplicate ->> 'possible_duplicate')::boolean OR (duplicate ->> 'counted_in_buffer')::boolean THEN
        RAISE EXCEPTION 'Tên trùng trong 30 m không được gắn cờ, hoặc đã bị tính: %', duplicate;
    END IF;
    rejected := decide_poi_contribution((duplicate ->> 'id')::bigint, 'rejected', 'trùng tên, chỉ để kiểm tra', 'người duyệt kiểm tra');
    IF rejected ->> 'status' IS DISTINCT FROM 'rejected' OR (rejected ->> 'counted_in_buffer')::boolean THEN
        RAISE EXCEPTION 'Từ chối không đúng: %', rejected;
    END IF;
    IF (SELECT epoch FROM contribution_epoch WHERE id = 1) <> epoch_after THEN
        RAISE EXCEPTION 'Từ chối đã làm tăng epoch';
    END IF;
    IF (
        buffer_profile(ST_X(anchor), ST_Y(anchor), 'cafe_drink') -> 'rings' -> 0 ->> 'competitor_count'
    )::integer <> competitor_before + 1 THEN
        RAISE EXCEPTION 'Dòng bị từ chối vẫn làm đổi số đếm';
    END IF;

    BEGIN
        PERFORM submit_poi_contribution('KIỂM TRA ĐÓNG GÓP', 'cafe_drink', 105, 21, NULL, 'bộ kiểm tra');
        RAISE EXCEPTION 'Điểm ngoài bốn quận đáng lẽ bị từ chối';
    EXCEPTION
        WHEN SQLSTATE '22023' THEN
            NULL;
    END;

    self_review := submit_poi_contribution(
        'KIỂM TRA TỰ DUYỆT', 'cafe_drink', sample_lon, sample_lat, NULL, 'bộ kiểm tra'
    );
    created_ids := created_ids || (self_review ->> 'id')::bigint;
    BEGIN
        PERFORM decide_poi_contribution((self_review ->> 'id')::bigint, 'approved', NULL, 'bộ kiểm tra');
        RAISE EXCEPTION 'Người gửi tự duyệt đáng lẽ bị từ chối';
    EXCEPTION
        WHEN SQLSTATE '22023' THEN
            NULL;
    END;
    IF (SELECT status FROM poi_contribution WHERE id = (self_review ->> 'id')::bigint) <> 'pending' THEN
        RAISE EXCEPTION 'Lần tự duyệt đã làm đổi trạng thái';
    END IF;

    BEGIN
        PERFORM submit_poi_contribution('KIỂM TRA ĐÓNG GÓP', 'restaurant', sample_lon, sample_lat, NULL, 'bộ kiểm tra');
        RAISE EXCEPTION 'category restaurant đáng lẽ bị từ chối';
    EXCEPTION
        WHEN SQLSTATE '22023' THEN
            NULL;
    END;

    DELETE FROM poi_contribution WHERE id = ANY (created_ids);
    after := buffer_profile(ST_X(anchor), ST_Y(anchor), 'cafe_drink');
    IF (after -> 'rings' -> 0 ->> 'competitor_count')::integer <> competitor_before
        OR (after -> 'rings' -> 0 ->> 'contribution_competitor_count')::integer <> contribution_before THEN
        RAISE EXCEPTION 'Sau khi xóa đúng các dòng vừa tạo, số đếm không trở lại: %', after -> 'rings' -> 0;
    END IF;
END;
$$;

ROLLBACK;
