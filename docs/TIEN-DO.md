# Sổ tiến độ

Sổ này ghi tiến độ triển khai. Cách hệ thống vận hành nằm ở [TONG-QUAN.md](TONG-QUAN.md).

## Quy ước trạng thái

| Trạng thái | Ý nghĩa |
| --- | --- |
| `chưa làm` | Chưa bắt đầu. |
| `đang làm` | Đang triển khai. |
| `chờ duyệt` | Đã làm xong và có cách kiểm tra. Người phụ trách chưa xác nhận. |
| `thành công` | Người phụ trách đã xem và nói chức năng ổn. |
| `cần sửa` | Người phụ trách đã xem và yêu cầu sửa. |

Câu xác nhận hợp lệ gồm các ý như: "ok", "duyệt", "chức năng này được", hoặc nói rõ hạng mục nào đã ổn.

## Quy tắc cập nhật

- Mỗi lần xong một hạng mục, ghi một dòng nhật ký: ngày giờ Việt Nam (UTC+7), hạng mục, trạng thái `chờ duyệt`, và cách kiểm tra.
- Không tự đổi `chờ duyệt` thành `thành công`. Chỉ khi người phụ trách xác nhận chức năng ổn thì lần làm việc sau mới ghi `thành công`.
- Nếu người phụ trách yêu cầu sửa khi chưa duyệt, chuyển `cần sửa`, làm lại, rồi đưa về `chờ duyệt`.
- Một hạng mục `thành công` bị yêu cầu sửa thì trở lại `cần sửa` cho đến khi được duyệt lại.

## Hạng mục

Chín hạng mục dưới đây bám bốn phân hệ của đề tài. Năm mục đã `thành công` vì người phụ trách yêu cầu ba giám khảo chấm thang 100 và tự gán khi điểm trung bình đạt mức đã yêu cầu. Mức của mục 6 là 93.

| # | Phân hệ | Hạng mục | Trạng thái |
| --- | --- | --- | --- |
| 1 | Spatial Engine | Nạp OSM vào PostGIS, chỉ mục GIST, cache Redis | `thành công` |
| 2 | Spatial Engine | Truy vấn vùng đệm 200 / 500 / 1000 m (điểm hút khách, đối thủ, trục đường) | `thành công` |
| 3 | Spatial Engine | Đóng góp POI có kiểm duyệt | `chờ duyệt` |
| 4 | AHP Scoring Core | Ma trận AHP theo từng ngành, CR dưới 0.1, thang điểm 0–100, chỉ số hiệu quả theo giá thuê | `thành công` |
| 5 | Multimodal AI | Hàng đợi Redis và WebSocket cho pipeline ảnh | `thành công` |
| 6 | Multimodal AI | Vision AI: vỉa hè, tầm nhìn, cảnh báo ảnh kém, báo cáo khuyến nghị dạng JSON | `thành công` |
| 7 | Web GIS | Bản đồ OpenLayers: bán kính, phân loại đối thủ / khách tiềm năng, heatmap | `chưa làm` |
| 8 | Web GIS | So sánh 2–3 mặt bằng (radar) và xuất PDF | `chưa làm` |
| 9 | Kiểm chứng | Back-test 10–15 địa điểm thực tế tại Hà Nội | `chưa làm` |

## Nhật ký

Ghi từ trên xuống dưới, mục mới nhất ở đầu. Tài liệu tổng quan và sổ tiến độ là việc soạn thảo, không phải một trong chín hạng mục chức năng ở trên.

| Thời điểm (UTC+7) | Hạng mục | Trạng thái | Cách kiểm tra |
| --- | --- | --- | --- |
| 2026-11-17 21:06 | Đổi cách học sang dải và biên, rồi ba giám khảo chấm lại mục 6 | `thành công` | Đọc mục bề rộng trong [CHAM-DIEM.md](CHAM-DIEM.md) và [geometry_selection.json](../etl/vision/geometry_selection.json). Trung bình 5 láng giềng thắng vì sai số chọn 0,544 m, thấp hơn 0,719 m của mạng trước. Sai số kiểm tra 0,568 m, so với 1,270 m của trung bình tập học. Khớp mức quy ước 78,6%, mức đa số khớp 53,6%. Sai số học 0,473 m bị lạc quan vì ảnh học nằm trong nhóm của chính nó. PNG 1×1 thành `poor_quality`. Ảnh đủ biến thiên thành `completed` với số mét và `visibility` null. Ba ngành nhận số mét, `vision_applied` false, không có điểm ảnh. Gửi `blend_weight` thì 409. Ba giám khảo: 92, 100 và 100, trung bình 97,3. Giám khảo 1 trừ vì chưa có tầm nhìn. Hai giám khảo kia không trừ. Trung bình đạt mức 93 nên ghi `thành công`. Tầm nhìn vẫn chưa được đo. |
| 2026-11-17 18:37 | Nối mô hình bề rộng vào pipeline và chấm lại mục 6 | `chờ duyệt` | Đọc mục bề rộng trong [CHAM-DIEM.md](CHAM-DIEM.md). Mạng 48×36 được chọn vì sai số tập chọn 0,719 m. Sai số kiểm tra 0,896 m, so với 1,270 m của trung bình tập học và 0,847 m của ridge lần trước. Độ khớp mức quy ước 58,9%, mức đa số khớp 53,6%. PNG 1×1 thành `poor_quality`. Ảnh đủ biến thiên thành `completed`, có số mét, `visibility` null, `assessed` false. Ba giám khảo: 85, 90 và 91, trung bình 88,7. Dưới 93 nên vẫn `chờ duyệt`. |
| 2026-11-17 15:44 | Huấn luyện bề rộng vỉa hè, rồi ba giám khảo chấm lại mục 6 | `chờ duyệt` | Đọc mục bề rộng trong [CHAM-DIEM.md](CHAM-DIEM.md). 514 ảnh Seoul, DOI 10.5281/zenodo.22908353, bề rộng đo tại chỗ. Tách 349/53/112. Ridge phạt 1.000 được chọn trên tập chọn. Sai số kiểm tra 0,847 m, so với 1,270 m nếu đoán bằng trung bình tập học. Không tinh chỉnh Qwen. Không có nhãn tầm nhìn. Pipeline ảnh không gọi mô hình này. Ba giám khảo chấm mục 6: 84, 86 và 88, trung bình 86. Dưới 90 nên vẫn `chờ duyệt`. |
| 2026-11-17 14:44 | Ba giám khảo chấm AHP, hàng đợi ảnh và Vision JSON | `thành công` cho mục 4 và 5; mục 6 vẫn `chờ duyệt` | Người phụ trách yêu cầu chấm lại thang 100 sau khi tải mô hình. Không huấn luyện: chưa có ảnh vỉa hè gán nhãn và máy không có GPU, nên không có số chính xác mới để ghi. Hai file GGUF Qwen2.5-VL-3B-Instruct trên máy đã đối chiếu SHA-256 nhưng API chưa gọi. Ba giám khảo đo lại độc lập. Mục 4: 100, 98 và 100, trung bình 99,3. Mục 5: 100, 94 và 100, trung bình 98. Mục 6: 94, 69 và 72, trung bình 78,3. Điểm trừ lớn của mục 6 là ảnh thật chỉ kết thúc `failed`, chưa có kết quả vỉa hè hay tầm nhìn. |
| 2026-11-17 09:36 | Vision AI: JSON có cấu trúc, ảnh kém không thành điểm 0 | `chờ duyệt` | Đọc [CHAM-DIEM.md](CHAM-DIEM.md). Bộ phân tích nhận JSON `ok` với vỉa hè `enough` và tầm nhìn `clear`, và nhận JSON `poor` có hai trường đo để null. JSON `poor` mà vẫn ghi vỉa hè bị từ chối. Gửi một PNG 1×1 khi không có `GEMINI_API_KEY` và `GROQ_API_KEY`: `GET /api/vision/jobs/{id}` kết thúc `failed`, `result` null, thông báo nói chưa có khóa và không bịa kết quả. Ảnh không phải JPEG, PNG hoặc WebP trả 400. Chưa có lần gọi mô hình thật. |
| 2026-11-17 09:36 | Hàng đợi Redis và WebSocket STOMP | `chờ duyệt` | Cùng ảnh PNG 1×1: phản hồi tạo việc là `queued`, không kèm byte ảnh. Đăng ký STOMP `/topic/vision/{id}` rồi thấy một khung `failed`. `vision_job` không bị lệnh nạp xóa. Chỉ chạy một tiến trình API vì hai tiến trình tranh cùng list `vision:queue`. Socket `/ws` đang mở mọi origin và chưa có đăng nhập. |
| 2026-11-17 09:36 | Chấm điểm AHP, CR và chỉ số theo tiền thuê | `chờ duyệt` | Đọc [CHAM-DIEM.md](CHAM-DIEM.md). `GET /api/scoring/reference` có 337 mẫu. Các phân vị lưu đủ số lẻ và làm tròn thành P95 điểm hút 90,93, cà phê 23,05, nhà thuốc 6,40, tiện lợi 12,81 điểm/km², trung vị đường 192,2 m. Nhãn ma trận nói đây là ma trận làm việc, chưa phải phiếu chuyên gia. CR của ba ngành là 0,0079, 0,0079 và 0,0158. `POST /api/scoring/evaluate` tại Cà Phê Kafa với tiền thuê 25.000.000 đồng/tháng cho cà phê 52,1 điểm, nhà thuốc 59,9, tiện lợi 53,3. Điểm ngoài bốn quận trả 400. `blend_weight` không kèm việc ảnh trả 400. Việc `poor_quality` trả 409, không bị tính điểm 0. Chỉ số thuê là điểm trên mỗi triệu đồng, không phải ROI. |
| 2026-11-17 09:06 | Ba giám khảo chấm hạng mục đã làm | `thành công` cho mục 1 và 2; mục 3 vẫn `chờ duyệt` | Người phụ trách yêu cầu ba giám khảo độc lập chấm thang 100 và tự gán `thành công` khi điểm trung bình trên 90. Mục 1: 95, 95 và 96, trung bình 95,3. Mục 2: 98, 96 và 90, trung bình 94,7. Mục 3: 90, 89 và 80, trung bình 86,3. Điểm trừ lớn của mục 3 là chưa có người duyệt riêng với người gửi. |
| 2026-11-17 08:43 | Đóng góp POI có kiểm duyệt | `chờ duyệt` | Đọc mục đóng góp trong [DU-LIEU.md](DU-LIEU.md). Gửi `POST /api/spatial/contributions` một quán cà phê trong bốn quận: dòng phải là `pending` và vùng đệm chưa đổi. `approve` làm số đối thủ tăng 1, `contribution_competitor_count` thành 1, và khóa cache đổi epoch. Gọi duyệt lần hai trả 409. `reject` không làm tăng số đếm và bắt buộc có lý do. `health` vẫn đếm 3290 POI OpenStreetMap. Chưa có đăng nhập. Lệnh nạp không chạy file kiểm tra và không xóa bảng này. Lần tự kiểm tra đã xóa đúng id vừa tạo; bảng đóng góp trống, `contribution_epoch` còn 1. |
| 2026-11-17 08:26 | Tự kiểm tra lại vùng đệm 200 / 500 / 1000 m | `chờ duyệt` | Gọi lại địa chỉ Cà Phê Kafa. Đối thủ cà phê vẫn 6 / 12 / 35, điểm hút 4 / 24 / 127, vòng 1000 m vẫn `counts_complete=false` với phần diện tích 0,7645, trục đường vẫn Nguyễn Khánh Toàn 10,2 m. Nhà thuốc cùng điểm ra đối thủ 0 / 1 / 4. `restaurant` và tọa độ 0,0 trả 400. Cache tóm tắt đã hết hạn 6 giờ nên lần gọi đầu ghi lại, lần sau `cache.hit=true`. Hạng mục 2 chưa được người phụ trách xác nhận. |
| 2026-11-16 20:33 | Truy vấn vùng đệm 200 / 500 / 1000 m | `chờ duyệt` | Đọc mục vùng đệm trong [DU-LIEU.md](DU-LIEU.md). Gọi `GET /api/spatial/buffers?lat=21.0378611&lon=105.8010007&business_type=cafe_drink` (node [Cà Phê Kafa](https://www.openstreetmap.org/node/423736651)). Lần kiểm tra này đếm đối thủ cà phê 6 / 12 / 35 và điểm hút khách 4 / 24 / 127; vòng 200 m và 500 m có `counts_complete=true`, vòng 1000 m còn 0,7645 diện tích trong bốn quận nên `counts_complete=false` (mép Cầu Giấy cách điểm khoảng 530 m). Trục đường gần nhất là [Nguyễn Khánh Toàn](https://www.openstreetmap.org/way/180131788), 10,2 m. Gọi lại cùng tọa độ, lần hai phải có `cache.hit=true`. Cùng điểm với `pharmacy` phải ra số đối thủ khác. `business_type=restaurant` và tọa độ 0,0 phải trả 400. Đây chưa phải điểm số. |
| 2026-11-16 19:16 | Nạp OSM vào PostGIS, chỉ mục GIST, cache Redis | `chờ duyệt` | Đọc [DU-LIEU.md](DU-LIEU.md). Bốn quận lấy từ relation `boundary=historic` vì ảnh Geofabrik 2026-11-15 không còn ranh quận hành chính hiện hành. Mở [manifest.json](../data/manifest.json) và [spotcheck.json](../data/spotcheck.json): 20/20 node mẫu khớp API OpenStreetMap trong 500 m. Chạy API, `GET /api/spatial/health` phải 200; gọi `/api/spatial/summary` hai lần, lần hai có `cache.hit=true`. Đối chiếu một điểm, ví dụ [Cà Phê Kafa](https://www.openstreetmap.org/node/423736651). |
| 2026-11-16 18:07 | Viết tổng quan dự án, sổ tiến độ và quy tắc duyệt | `chờ duyệt` | Đọc [TONG-QUAN.md](TONG-QUAN.md), sổ này và `.cursor/rules/tien-do.mdc`. Xác nhận nội dung đúng đề cương và cổng duyệt rõ ràng. |
