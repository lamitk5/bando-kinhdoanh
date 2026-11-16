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

Chín hạng mục dưới đây bám bốn phân hệ của đề tài. Chưa có mục nào `thành công`.

| # | Phân hệ | Hạng mục | Trạng thái |
| --- | --- | --- | --- |
| 1 | Spatial Engine | Nạp OSM vào PostGIS, chỉ mục GIST, cache Redis | `chờ duyệt` |
| 2 | Spatial Engine | Truy vấn vùng đệm 200 / 500 / 1000 m (điểm hút khách, đối thủ, trục đường) | `chưa làm` |
| 3 | Spatial Engine | Đóng góp POI có kiểm duyệt | `chưa làm` |
| 4 | AHP Scoring Core | Ma trận AHP theo từng ngành, CR dưới 0.1, thang điểm 0–100, chỉ số hiệu quả theo giá thuê | `chưa làm` |
| 5 | Multimodal AI | Hàng đợi Redis và WebSocket cho pipeline ảnh | `chưa làm` |
| 6 | Multimodal AI | Vision AI: vỉa hè, tầm nhìn, cảnh báo ảnh kém, báo cáo khuyến nghị dạng JSON | `chưa làm` |
| 7 | Web GIS | Bản đồ OpenLayers: bán kính, phân loại đối thủ / khách tiềm năng, heatmap | `chưa làm` |
| 8 | Web GIS | So sánh 2–3 mặt bằng (radar) và xuất PDF | `chưa làm` |
| 9 | Kiểm chứng | Back-test 10–15 địa điểm thực tế tại Hà Nội | `chưa làm` |

## Nhật ký

Ghi từ trên xuống dưới, mục mới nhất ở đầu. Tài liệu tổng quan và sổ tiến độ là việc soạn thảo, không phải một trong chín hạng mục chức năng ở trên.

| Thời điểm (UTC+7) | Hạng mục | Trạng thái | Cách kiểm tra |
| --- | --- | --- | --- |
| 2026-11-16 19:16 | Nạp OSM vào PostGIS, chỉ mục GIST, cache Redis | `chờ duyệt` | Đọc [DU-LIEU.md](DU-LIEU.md). Bốn quận lấy từ relation `boundary=historic` vì ảnh Geofabrik 2026-11-15 không còn ranh quận hành chính hiện hành. Mở [manifest.json](../data/manifest.json) và [spotcheck.json](../data/spotcheck.json): 20/20 node mẫu khớp API OpenStreetMap trong 500 m. Chạy API, `GET /api/spatial/health` phải 200; gọi `/api/spatial/summary` hai lần, lần hai có `cache.hit=true`. Đối chiếu một điểm, ví dụ [Cà Phê Kafa](https://www.openstreetmap.org/node/423736651). |
| 2026-11-16 18:07 | Viết tổng quan dự án, sổ tiến độ và quy tắc duyệt | `chờ duyệt` | Đọc [TONG-QUAN.md](TONG-QUAN.md), sổ này và `.cursor/rules/tien-do.mdc`. Xác nhận nội dung đúng đề cương và cổng duyệt rõ ràng. |
