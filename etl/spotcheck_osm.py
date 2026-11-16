#!/usr/bin/env python3
"""Đối chiếu một mẫu node trong PostGIS với API công khai của OpenStreetMap.

File PBF là ảnh chụp tại mốc trong header. API trực tiếp có thể đã đổi sau mốc đó.
Nút được tính là khớp nếu còn tồn tại và lệch không quá 500 mét.
"""

import json
import math
import sys
import time
import urllib.error
import urllib.request

MAX_METERS = 500
MIN_CONFIRMED = 5
USER_AGENT = "bando-kinhdoanh-thesis/0.1 (educational snapshot check)"


def haversine_m(lat1, lon1, lat2, lon2):
    radius = 6371000.0
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    d_phi = math.radians(lat2 - lat1)
    d_lon = math.radians(lon2 - lon1)
    a = math.sin(d_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(d_lon / 2) ** 2
    return 2 * radius * math.asin(math.sqrt(a))


def fetch_node(osm_id):
    url = f"https://api.openstreetmap.org/api/0.6/node/{osm_id}.json"
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def main():
    source = json.load(open(sys.argv[1], encoding="utf-8"))
    if len(source) < MIN_CONFIRMED:
        raise SystemExit(f"Chỉ có {len(source)} node để đối chiếu, cần ít nhất {MIN_CONFIRMED}.")

    results = []
    for index, row in enumerate(source):
        if index:
            time.sleep(1)
        item = {
            "osm_id": row["osm_id"],
            "category": row["category"],
            "name": row["name"],
            "stored_lat": row["lat"],
            "stored_lon": row["lon"],
            "osm_key": row["osm_key"],
            "osm_value": row["osm_value"],
        }
        try:
            payload = fetch_node(row["osm_id"])
        except urllib.error.HTTPError as error:
            item["status"] = f"http_{error.code}"
            results.append(item)
            continue
        elements = payload.get("elements") or []
        if not elements:
            item["status"] = "missing"
            results.append(item)
            continue
        element = elements[0]
        distance = haversine_m(row["lat"], row["lon"], element["lat"], element["lon"])
        tags = element.get("tags") or {}
        item["live_lat"] = element["lat"]
        item["live_lon"] = element["lon"]
        item["distance_m"] = round(distance, 1)
        item["live_tag"] = tags.get(row["osm_key"])
        item["status"] = "confirmed" if distance <= MAX_METERS else "moved"
        results.append(item)

    confirmed = sum(1 for item in results if item["status"] == "confirmed")
    missing = sum(1 for item in results if item["status"] not in {"confirmed", "moved"})
    moved = sum(1 for item in results if item["status"] == "moved")
    report = {
        "rule": f"Node còn trên API OSM và lệch tối đa {MAX_METERS} m so với hình học đã nạp.",
        "checked": len(results),
        "confirmed_within_500m": confirmed,
        "missing_on_live": missing,
        "moved_over_500m": moved,
        "results": results,
    }
    with open(sys.argv[2], "w", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print(
        f"spotcheck checked={len(results)} confirmed={confirmed} missing={missing} moved={moved}"
    )
    if confirmed < MIN_CONFIRMED:
        raise SystemExit(
            f"Chỉ xác nhận được {confirmed} node trên API OSM, dưới ngưỡng {MIN_CONFIRMED}."
        )


if __name__ == "__main__":
    main()
