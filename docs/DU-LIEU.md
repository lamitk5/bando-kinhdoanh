# Dữ liệu không gian

Hạng mục nạp bản đồ đưa OpenStreetMap vào PostgreSQL/PostGIS, đánh chỉ mục GIST và cache bản tóm tắt trên Redis. Vùng đệm 200/500/1000 m được tính riêng, bằng mét. Đóng góp hiện trường nằm ở bảng riêng và chỉ vào vùng đệm sau khi được duyệt. Chưa chấm điểm AHP và chưa có tài khoản người duyệt.

Số liệu sau mỗi lần chạy nằm ở [manifest.json](../data/manifest.json). File đó do `etl/import_osm.sh` ghi từ câu lệnh SQL, không gõ tay. Nếu file chưa có, lần nạp chưa chạy xong.

## Nguồn

- Tải `https://download.geofabrik.de/asia/vietnam-latest.osm.pbf`.
- Giữ file MD5 đi kèm và chỉ nạp khi MD5 khớp. SHA-256 và mốc `osmosis_replication_timestamp` trong header PBF được ghi vào bảng `data_import`.
- Giấy phép: ODbL, ghi công OpenStreetMap contributors. Geofabrik là bản trích Việt Nam, đúng nguồn đề cương.
- File PBF không đưa vào git. Thư mục `data/raw/` và `data/work/` bị bỏ qua.

File ranh giới hành chính trong `data/work` được tạo lại khi SHA-256 của PBF nguồn đổi. Vùng nghiên cứu là hợp của bốn polygon quận, cắt từ chính file PBF bằng `osmium extract --strategy smart`. Sau đó một lần lọc thẻ giữ `amenity`, `shop`, `office`, `building=apartments`, `highway`, `railway`, `public_transport` rồi mới đưa vào osm2pgsql. Hộp bao không được dùng làm vùng phân tích.

## Ranh giới

Ảnh PBF ngày 2026-11-15 không còn bốn quận của đề tài ở `boundary=administrative`. Sau sắp xếp đơn vị hành chính, OSM gắn chúng là `boundary=historic` và `was:admin_level=6`, với tên đúng `Quận Cầu Giấy`, `Quận Đống Đa`, `Quận Thanh Xuân`, `Quận Bắc Từ Liêm`.

Script lọc đúng bốn tên đó rồi dựng multipolygon từ relation. Không lấy phường hiện hành chỉ vì tên chứa "Cầu Giấy", "Đống Đa", "Thanh Xuân" hoặc "Từ Liêm". Những phường đó là đơn vị khác, nhỏ hơn quận cũ.

Polygon Hà Nội dùng để kiểm tra bao phủ là relation hành chính `admin_level=4`, tên Thành phố Hà Nội. Lần nạp dừng nếu một quận phủ dưới 95% diện tích trong polygon này, nếu không đủ đúng bốn quận, hoặc nếu diện tích nằm ngoài khoảng 4–80 km². Khoảng diện tích chỉ để chặn mảnh vỡ và nhầm cả tỉnh, không phải số chính thức được gõ sẵn.

Hình học lưu EPSG:4326. Cột `area_km2` tính bằng `ST_Area(geom::geography)`. Mã relation và diện tích đo được ghi trong `data/manifest.json`.

## POI

`etl/study.lua` chỉ giữ đối tượng có thể thuộc phạm vi đề tài. Luật chọn hạng mục nằm ở `classify_feature` trong [etl/sql/001_functions.sql](../etl/sql/001_functions.sql). Một phần tử OSM nhận một hạng mục, theo thứ tự từ trên xuống.

| Hạng mục | Vai trò | Thẻ OSM được nhận |
| --- | --- | --- |
| pharmacy | đối thủ | `amenity=pharmacy`, nếu không có thì `shop=chemist` |
| convenience | đối thủ | `shop=convenience` |
| cafe_drink | đối thủ | `amenity=cafe`, hoặc `shop=coffee`, `shop=tea`, `shop=beverages` |
| hospital | điểm hút | `amenity=hospital` |
| clinic | điểm hút | `amenity=clinic`. Phòng khám không bị gộp vào bệnh viện |
| education | điểm hút | `amenity` là school, university, college, kindergarten |
| transit_station | điểm hút | `railway=station` hoặc `halt`, `amenity=bus_station`, `public_transport=station` |
| transit_stop | điểm hút | `highway=bus_stop`, `railway=tram_stop`, `public_transport=stop_position` hoặc `platform` |
| apartments | điểm hút | `building=apartments` |
| office | điểm hút | `office=*` trừ `vacant` và `no` |

Cố ý không nhận: nhà hàng, quán bar, đồ ăn nhanh, siêu thị, `building=residential`, `building=dormitory`. Những chỗ chỉ được gắn thẻ đó sẽ không có trong bảng `poi`. Đây là giới hạn của luật thẻ, không phải bằng chứng rằng ngoài đời không có cửa hàng.

Luật không sửa thẻ theo tên. Một điểm `amenity=hospital` vẫn vào nhóm bệnh viện kể cả khi tên là phòng khám hoặc nha khoa. Đó là cách gắn thẻ trên OSM, được giữ nguyên để đối chiếu, không bị viết lại cho khớp tên gọi.

Điểm đếm của vùng là `ST_PointOnSurface`. Điểm nút giữ nguyên tọa độ. Phần tử nằm ngoài bốn quận bị loại và được đếm ở `poi_outside_count`. Phần tử chỉ chạm biên được giữ với `on_boundary = true`. Nếu một điểm nằm trong hai quận, lần nạp dừng.

`possible_duplicate` đánh dấu hai phần tử cùng quận, cùng hạng mục, cùng tên, cách nhau không quá 30 m. Dòng không bị xóa. Đây là cờ chất lượng dữ liệu, chưa phải phân tích vùng đệm của hạng mục 2.

Số dòng là số phần tử OSM, chưa gộp về một cửa hàng ngoài đời.

## Đường

Way có `highway` được lưu thành LineString EPSG:4326. Bỏ các giá trị proposed, construction, abandoned, razed, platform, bus_stop và một số giá trị không phải tuyến đường (xem `025_features.sql`).

`arterial = true` khi highway thuộc motorway, trunk, primary, secondary hoặc các dạng `_link` của chúng. Tertiary trở xuống vẫn được lưu nhưng không đánh dấu trục chính. Định nghĩa này để dùng lại ở hạng mục sau, chưa phải điểm số.

`road_district.length_m` là chiều dài phần tuyến nằm trong từng quận, tính bằng `ST_Length(...::geography)`. Chiều dài không tính bằng độ.

## Chỉ mục và cache

Chỉ mục GIST bắt buộc: `district_geom_gix`, `poi_geom_gix`, `road_geom_gix`, và `road_arterial_geog_gix` trên `geom::geography` của các tuyến `arterial`. Thêm một chỉ mục geography trên `poi` để phục vụ cờ trùng 30 m và truy vấn vùng đệm.

Redis không chứa chỉ mục không gian. Redis cache kết quả đọc của `GET /api/spatial/summary` với khóa `spatial:summary:v1:{import_id}`. Kết quả `buffer_profile` được cache với khóa `spatial:buffer:v2:{import_id}:{business_type}:{lat6}:{lon6}:{epoch}`. `epoch` tăng mỗi lần một đóng góp được duyệt, nên kết quả tính trước quyết định đó không còn được đọc. Cả hai hết hạn sau 6 giờ. Lần đọc đầu trả `cache.hit = false`, lần sau trong hạn trả `true`. Mẫu POI và health không bị cache. Lần nạp mới xóa cả hai họ khóa ngay sau khi bảng đã qua kiểm tra SQL, trước bước đối chiếu API OpenStreetMap. `data_import` được tạo lại nên id có thể trùng lần trước; xóa sớm để JSON cũ không còn được trả khi một bước sau đó dừng.

Cache sai hoặc Redis tắt thì API báo lỗi, không trả số liệu giả như thể đã cache.

## API đọc

Ứng dụng Spring Boot trong `backend/` đọc bảng đã nạp bằng JDBC. JDBC được dùng vì câu truy vấn là SQL không gian. Spring Data JPA chưa đưa vào hạng mục này.

- `GET /api/spatial/health` trả 200 khi có 4 quận, đủ bốn chỉ mục GIST ở trên và Redis trả PONG. Thiếu thì trả 503 cùng nội dung giải thích.
- `GET /api/spatial/summary` là bản tóm tắt theo quận.
- `GET /api/spatial/buffers?lat=&lon=&business_type=` trả ba vòng 200 m, 500 m và 1000 m. `business_type` chỉ nhận `cafe_drink`, `pharmacy`, `convenience`.
- `GET /api/spatial/samples?limit=15` trả tối đa 50 dòng thật, kèm URL OpenStreetMap.

## Vùng đệm

`buffer_profile(lon, lat, business_type)` trong `etl/sql/070_buffer.sql` đếm bằng `ST_DWithin` trên `geography`. Bán kính cố định 200, 500, 1000 mét. Không có tham số bán kính tính bằng độ.

Tọa độ được làm tròn 6 chữ số thập phân trước khi truy vấn và trước khi lập khóa cache, tương ứng khoảng 0,1 m. Kinh độ ngoài 102–110 hoặc vĩ độ ngoài 8–24 bị từ chối với SQLSTATE 22023, API trả 400.

Mỗi vòng trả:

- `competitor_count`: POI cùng `category` với ngành đang hỏi. Cà phê không đếm nhà thuốc.
- `attractor_count` và `attractors_by_category`: mọi POI có `role = attractor`.
- `fraction_inside_study_area`: diện tích phần vòng tròn nằm trong hợp của bốn quận, chia cho diện tích vòng tròn, cả hai tính bằng mét vuông.
- `counts_complete`: đúng khi phần nằm trong vùng đạt ít nhất 0,999. Dưới ngưỡng này số đếm thiếu điểm ngoài bốn quận, vì bản trích chỉ chứa POI và đường trong vùng nghiên cứu.

POI nằm đúng tại tọa độ hỏi được tính vào số đếm. Mỗi vòng còn có `contribution_competitor_count` và `contribution_attractor_count` để tách phần đến từ đóng góp đã duyệt. Khi chưa có đóng góp nào được duyệt, hai số này bằng 0 và tổng trùng với POI OpenStreetMap. Khoảng cách tới trục đường là `ST_Distance` trên geography tới tuyến `arterial` gần nhất trong bảng đã nạp, làm tròn 0,1 m, kèm tên, loại highway và URL OpenStreetMap. Đường nằm trọn ngoài bốn quận không có trong bảng, nên với điểm sát mép hoặc ngoài vùng khoảng cách này có thể lớn hơn khoảng cách thật. Tuyến cắt biên được giữ nguyên, nên điểm gần nhất trên tuyến có thể nằm ngoài bốn quận và khoảng cách có thể ngắn hơn khoảng cách tới đoạn nằm trong vùng. Kết quả không phải điểm số AHP.

Mật khẩu `bando` trong `application.yml` chỉ dành cho cơ sở dữ liệu cục bộ của môi trường phát triển.

`docker-compose.yml` mô tả PostGIS 16 và Redis 7 cho máy có Docker. Phiên cài đặt này không có Docker; lần chạy kiểm tra dùng PostgreSQL 16, PostGIS 3.4 và Redis 7 cài bằng gói hệ thống.

## Đóng góp có kiểm duyệt

`poi_contribution` lưu điểm người dùng thấy ngoài hiện trường. Lệnh nạp không xóa bảng này: `015_districts.sql` không có `DROP` cho nó, và `etl/import_osm.sh` không chạy bước xóa đóng góp. Dòng không có `osm_id`: đây không phải phần tử OpenStreetMap. `counted_in_buffer` dùng cùng điều kiện với vùng đệm: dòng `approved` và còn bị một quận hiện tại phủ (`ST_Covers`), không phụ thuộc việc quận lúc gửi còn giữ nguyên id nội bộ.

Gửi điểm bằng `POST /api/spatial/contributions` với `name`, `category`, `lat`, `lon`, `submitter` và `note` tùy chọn. `category` chỉ nhận mười hạng mục đang dùng cho POI. Máy chủ gán `role`: ba ngành kinh doanh là `competitor`, các hạng mục còn lại là `attractor`. Trường `role` do client gửi bị từ chối. Điểm ngoài bốn quận, hoặc chạm hơn một quận, bị từ chối. Tên trùng một POI cùng hạng mục, cùng quận, trong 30 m thì `possible_duplicate = true`, dòng vẫn được nhận.

Dòng mới có `status = pending` và `counted_in_buffer = false`. `POST /api/spatial/contributions/{id}/approve` chuyển sang `approved`. `POST /api/spatial/contributions/{id}/reject` cần `decision_note`. Quyết định lần hai trả 409. Chỉ dòng `approved` còn nằm trong quận hiện tại mới vào `counted_poi` và vùng đệm. Duyệt một điểm xóa các khóa `spatial:buffer:*` để lần đọc sau không trả số cũ.

`GET /api/spatial/contributions?status=pending` liệt kê tối đa 100 dòng. `health` và `summary` vẫn chỉ đếm POI OpenStreetMap.

Chưa có đăng nhập. Bước duyệt là một lời gọi riêng, chưa phải phân quyền Spring Security. Người gọi biết địa chỉ API có thể tự duyệt dòng của mình.

## Kiểm tra

`etl/sql/040_classify_test.sql` khóa luật phân loại bằng các ca cố định, gồm những thẻ phải bị loại.

`etl/sql/030_verify.sql` dừng lần nạp nếu sai SRID, sai thẻ so với JSON, thiếu hạng mục, thiếu GIST hình học, hoặc không có trục đường. Nó không đối chiếu một số lượng POI gõ sẵn.

`etl/sql/060_buffer_checks.sql` kiểm tra hàm vùng đệm trên dữ liệu vừa nạp: đúng ba bán kính, số đếm không giảm khi bán kính tăng, khoảng cách trục đường khớp phép đo geography, điểm 105°E 21°N không được đánh dấu đếm đủ, và ngành `restaurant` bị từ chối. Kiểm tra này dừng nếu node OSM 423736651 không còn là `cafe_drink` trong bảng `poi`.

`etl/sql/065_contribution_checks.sql` không nằm trong lệnh nạp. File mở một giao dịch, tạo một đóng góp cách node đó 15 m, xác nhận lúc chờ thì số đếm không đổi, lúc duyệt thì số đối thủ và `epoch` tăng 1 so với lúc bắt đầu, lúc từ chối thì số đếm không tăng, rồi `ROLLBACK`. Dòng có sẵn không bị xóa.

`etl/spotcheck_osm.py` lấy tối đa hai node mỗi hạng mục, gọi API `api.openstreetmap.org`, và chỉ tính là khớp khi node còn tồn tại và lệch không quá 500 m. API trực tiếp có thể mới hơn ảnh PBF. Lệch thẻ sau mốc header không làm lần nạp thất bại. Dưới 5 node khớp thì script dừng.

Mẫu xem tay nằm ở [data/samples/poi.tsv](../data/samples/poi.tsv).

## Chạy lại

Cần PostgreSQL/PostGIS, Redis, osmium, osm2pgsql và file PBF đã đối chiếu MD5 trong `data/raw/`.

```bash
./etl/import_osm.sh
cd backend && mvn test && mvn spring-boot:run
```

Rồi gọi `http://127.0.0.1:8080/api/spatial/health` và `/api/spatial/summary` hai lần để thấy cache.
