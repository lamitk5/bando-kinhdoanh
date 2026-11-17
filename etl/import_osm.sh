#!/usr/bin/env bash
# Nạp OpenStreetMap (Geofabrik Vietnam) vào PostGIS cho 4 quận nội thành Hà Nội.
# Dừng ngay khi checksum, ranh giới hoặc kiểm tra sau nạp thất bại.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

RAW="$ROOT/data/raw/vietnam-latest.osm.pbf"
MD5_FILE="$RAW.md5"
WORK="$ROOT/data/work"
ADMIN_PBF="$WORK/admin.osm.pbf"
STUDY_PBF="$WORK/study.osm.pbf"
SOURCE_URL="https://download.geofabrik.de/asia/vietnam-latest.osm.pbf"
ATTRIBUTION="OpenStreetMap contributors, ODbL. Source: Geofabrik Vietnam extract."
EXTRACT_STRATEGY="smart"

mkdir -p "$WORK" "$ROOT/data/samples"

log() {
    printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"
}

psql_bando() {
    PGPASSWORD=bando psql -h 127.0.0.1 -U bando -d bando -v ON_ERROR_STOP=1 "$@"
}

if [[ ! -s "$RAW" || ! -s "$MD5_FILE" ]]; then
    echo "Thiếu $RAW hoặc file MD5. Script này không tự tải để tránh ghi đè file đang kiểm tra." >&2
    exit 1
fi

byte_size="$(stat -c%s "$RAW")"
if (( byte_size <= 100000000 )); then
    echo "Từ chối file PBF nhỏ hơn 100MB ($byte_size byte)." >&2
    exit 1
fi

expected_md5="$(awk '{print $1}' "$MD5_FILE")"
actual_md5="$(md5sum "$RAW" | awk '{print $1}')"
if [[ "$expected_md5" != "$actual_md5" ]]; then
    echo "MD5 không khớp Geofabrik. expected=$expected_md5 actual=$actual_md5" >&2
    exit 1
fi
sha256="$(sha256sum "$RAW" | awk '{print $1}')"
osm_header_timestamp="$(osmium fileinfo --get=header.option.timestamp "$RAW")"
if [[ -z "$osm_header_timestamp" ]]; then
    echo "PBF không có mốc thời gian trong header." >&2
    exit 1
fi

resolved_url="$(curl -fsSI -L -o /dev/null -w '%{url_effective}' "$SOURCE_URL")"
http_last_modified_raw="$(curl -fsSI -L "$SOURCE_URL" | tr -d '\r' | awk -F': ' 'tolower($1)=="last-modified"{value=$2} END{print value}')"
http_last_modified="$(date -u -d "$http_last_modified_raw" +%Y-%m-%dT%H:%M:%SZ)"
retrieved_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
osmium_version="$(osmium --version 2>&1 | awk 'NR==1 {print $3}')"
osm2pgsql_version="$(osm2pgsql --version 2>&1 | sed -n 's/.*osm2pgsql version /osm2pgsql /p' | head -n 1)"
if [[ -z "$osm2pgsql_version" ]]; then
    echo "Không đọc được phiên bản osm2pgsql." >&2
    exit 1
fi
postgis_version="$(psql_bando -At -c 'SELECT PostGIS_Version();')"

log "MD5 khớp. sha256=$sha256 bytes=$byte_size osm=$osm_header_timestamp"

log "Kiểm tra hàm phân loại"
psql_bando -f "$ROOT/etl/sql/001_functions.sql" -f "$ROOT/etl/sql/040_classify_test.sql" >/dev/null

SOURCE_STAMP="$WORK/source.sha256"
if [[ ! -s "$ADMIN_PBF" || ! -f "$SOURCE_STAMP" || "$(cat "$SOURCE_STAMP")" != "$sha256" ]]; then
    log "Lọc ranh giới hành chính vì chưa có file hoặc PBF nguồn đã đổi"
    osmium tags-filter --overwrite -o "$ADMIN_PBF" "$RAW" r/boundary=administrative
    printf '%s\n' "$sha256" > "$SOURCE_STAMP"
fi
osmium fileinfo --get=header.option.timestamp "$ADMIN_PBF" >/dev/null

log "Nạp ranh giới hành chính hiện hành (để lấy polygon Hà Nội)"
PGPASSWORD=bando osm2pgsql \
    --create --output=flex --style="$ROOT/etl/admin.lua" \
    --database=bando --host=127.0.0.1 --user=bando \
    --cache=600 --number-processes=2 \
    "$ADMIN_PBF"

HISTORIC_PBF="$WORK/historic-districts.osm.pbf"
log "Lọc bốn quận historic theo đúng tên Quận"
osmium tags-filter --overwrite -o "$HISTORIC_PBF" "$RAW" \
    "r/name=Quận Cầu Giấy" \
    "r/name=Quận Đống Đa" \
    "r/name=Quận Thanh Xuân" \
    "r/name=Quận Bắc Từ Liêm"
log "Nạp polygon historic của bốn quận"
PGPASSWORD=bando osm2pgsql \
    --create --output=flex --style="$ROOT/etl/historic.lua" \
    --database=bando --host=127.0.0.1 --user=bando \
    "$HISTORIC_PBF"

log "Chọn 4 quận và kiểm tra nằm trong Hà Nội"
psql_bando \
    -v source_url="$SOURCE_URL" \
    -v resolved_url="$resolved_url" \
    -v sha256="$sha256" \
    -v byte_size="$byte_size" \
    -v md5_geofabrik="$expected_md5" \
    -v md5_ok=true \
    -v retrieved_at="$retrieved_at" \
    -v http_last_modified="$http_last_modified" \
    -v osm_header_timestamp="$osm_header_timestamp" \
    -v osmium_version="$osmium_version" \
    -v osm2pgsql_version="$osm2pgsql_version" \
    -v postgis_version="$postgis_version" \
    -v attribution="$ATTRIBUTION" \
    -f "$ROOT/etl/sql/015_districts.sql" \
    -f "$ROOT/etl/sql/016_assert_districts.sql"

psql_bando -At -c "SELECT ST_AsGeoJSON(ST_Union(geom), 6) FROM district;" > "$WORK/study-geom.json"
python3 - "$WORK/study-geom.json" "$WORK/study.geojson" <<'PY'
import json
import sys
geometry = json.load(open(sys.argv[1], encoding="utf-8"))
if geometry.get("type") not in {"Polygon", "MultiPolygon"}:
    raise SystemExit(f"Hình học vùng nghiên cứu không phải polygon: {geometry.get('type')}")
json.dump(
    {"type": "Feature", "properties": {"name": "study-area"}, "geometry": geometry},
    open(sys.argv[2], "w", encoding="utf-8"),
)
PY

log "Cắt PBF theo polygon 4 quận, strategy=$EXTRACT_STRATEGY"
osmium extract --overwrite --strategy="$EXTRACT_STRATEGY" \
    --polygon="$WORK/study.geojson" \
    --output="$STUDY_PBF" \
    "$RAW"
FEATURE_PBF="$WORK/study-features.osm.pbf"
log "Giữ node/way/relation có thẻ có thể thành POI hoặc đường"
osmium tags-filter --overwrite -o "$FEATURE_PBF" "$STUDY_PBF" \
    nwr/amenity nwr/shop nwr/office nwr/building=apartments \
    nwr/highway nwr/railway nwr/public_transport
study_bytes="$(stat -c%s "$FEATURE_PBF")"
if (( study_bytes < 1000000 )); then
    echo "File đối tượng vùng nghiên cứu quá nhỏ ($study_bytes byte)." >&2
    exit 1
fi
study_sha256="$(sha256sum "$FEATURE_PBF" | awk '{print $1}')"
log "Feature PBF sha256=$study_sha256 bytes=$study_bytes"

log "Nạp POI và đường trong vùng nghiên cứu"
PGPASSWORD=bando osm2pgsql \
    --create --output=flex --style="$ROOT/etl/study.lua" \
    --database=bando --host=127.0.0.1 --user=bando \
    --cache=600 --number-processes=2 \
    "$FEATURE_PBF"

log "Phân loại, lập chỉ mục, kiểm tra"
psql_bando \
    -v study_sha256="$study_sha256" \
    -v extract_strategy="$EXTRACT_STRATEGY" \
    -f "$ROOT/etl/sql/025_features.sql" \
    -f "$ROOT/etl/sql/030_verify.sql" \
    -f "$ROOT/etl/sql/080_contribution.sql" \
    -f "$ROOT/etl/sql/090_scoring.sql" \
    -f "$ROOT/etl/sql/095_vision.sql" \
    -f "$ROOT/etl/sql/070_buffer.sql"

# data_import bị tạo lại nên id có thể trùng lần nạp trước. Xóa cache ngay sau
# khi bảng mới đã ghi, trước các bước có thể dừng, để không trả JSON của lần nạp cũ.
# 065 không chạy ở đây: file đó chỉ để kiểm tra tay và tự rollback.
redis-cli EVAL "local keys = {}; for _, pattern in ipairs({'spatial:summary:*', 'spatial:buffer:*'}) do for _, key in ipairs(redis.call('keys', pattern)) do table.insert(keys, key) end end; for _, key in ipairs(keys) do redis.call('del', key) end; return #keys" 0 >/dev/null

psql_bando -f "$ROOT/etl/sql/060_buffer_checks.sql"
psql_bando -f "$ROOT/etl/sql/066_scoring_checks.sql"

psql_bando -At -f "$ROOT/etl/sql/050_manifest.sql" > "$ROOT/data/manifest.json"
psql_bando -f "$ROOT/etl/sql/055_samples.sql"
psql_bando -At -f "$ROOT/etl/sql/056_spotcheck_input.sql" > "$WORK/spotcheck-input.json"
python3 "$ROOT/etl/spotcheck_osm.py" "$WORK/spotcheck-input.json" "$ROOT/data/spotcheck.json"
python3 - "$ROOT/data/manifest.json" "$ROOT/data/spotcheck.json" <<'PY'
import json
import sys
manifest = json.load(open(sys.argv[1], encoding="utf-8"))
spot = json.load(open(sys.argv[2], encoding="utf-8"))
manifest["spotcheck"] = {
    "checked": spot["checked"],
    "confirmed_within_500m": spot["confirmed_within_500m"],
    "missing_on_live": spot["missing_on_live"],
    "moved_over_500m": spot["moved_over_500m"],
}
json.dump(manifest, open(sys.argv[1], "w", encoding="utf-8"), ensure_ascii=False, indent=2)
open(sys.argv[1], "a", encoding="utf-8").write("\n")
PY

log "Nạp xong. Xem data/manifest.json và data/spotcheck.json"
