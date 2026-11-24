# Chấm điểm và thẩm định ảnh

Ba hạng mục này đọc cùng dữ liệu không gian đã nạp. Điểm không gian là số tính được. Kết quả ảnh chỉ có khi một mô hình thị giác trả JSON và bộ phân tích chấp nhận. Không có khóa API thì pipeline dùng mô hình bề rộng đã học; ảnh không đọc được mới thành `failed`.

## Vòng được chấm

Chỉ vòng 500 m đi vào điểm AHP. Vòng 200 m quá thưa để so mật độ. Vòng 1000 m tại nhiều điểm trong bốn quận bị cụt bởi biên, như Cà Phê Kafa còn 0,7645 diện tích. Hai vòng đó vẫn nằm ở API vùng đệm và không có trọng số.

Điểm phải nằm trong bốn quận. Điểm ngoài vùng bị từ chối, kể cả khi API vùng đệm vẫn mô tả được nó. Nếu điểm nằm trong quận nhưng vòng 500 m bị cụt, hệ thống vẫn chấm và đặt `partial_ring = true`. Mẫu 500 m tại Kafa không bị cụt.

## Thang chuẩn hóa

`refresh_scoring_reference()` dựng lưới ô vuông 500 m trong EPSG:32648, giữ ô có phần giao quận ít nhất 62.500 m², lấy một điểm trong phần giao đó, rồi đo vòng 500 m. Mật độ là số điểm trên `counted_poi` chia cho diện tích phần vòng nằm trong bốn quận, đơn vị điểm trên km². Không dùng diện tích hình tròn đầy đủ ở mép vùng. Phân vị 95 của mật độ và trung vị khoảng cách tới trục đường được lưu ở `scoring_reference`. Lệnh nạp gọi lại hàm này. Bảng không bị lần nạp xóa.

Lần lưu gần nhất lúc 09:39 UTC+7 ngày 17/11/2026, trên ảnh OSM đang nạp. Một lần tính trước đó lúc 09:19 ra cùng các phân vị:

| Thang | Giá trị |
| --- | --- |
| Số điểm mẫu | 337 |
| P95 mật độ điểm hút | 90,93 điểm/km² |
| P95 mật độ cà phê | 23,05 điểm/km² |
| P95 mật độ nhà thuốc | 6,40 điểm/km² |
| P95 mật độ tiện lợi | 12,81 điểm/km² |
| Trung vị khoảng cách trục đường | 192,2 m |

Ba điểm thành phần, mỗi điểm từ 0 đến 100:

- Điểm hút: `100 * min(1, mật độ / P95 điểm hút)`. Dày hơn thì cao hơn, và không vượt 100.
- Bão hòa đối thủ: `100 * (1 - min(1, mật độ ngành / P95 của ngành đó))`. Dày hơn thì thấp hơn.
- Tiếp cận đường: `100 * exp(-ln(2) * khoảng_cách / trung_vị)`. Đúng trung vị được 50. Sát trục đường tiến tới 100. Khoảng cách là đường chim bay tới tuyến arterial đã nạp, cùng cách đo với API vùng đệm, không phải khoảng cách đi theo mạng đường.

Số đếm tại điểm hỏi được tính lúc gọi. Thang P95 chỉ đổi khi gọi `POST /api/scoring/reference/refresh` sau một lần duyệt đóng góp hoặc một lần nạp mới. Điểm không được cache, vì tiền thuê và thang có thể đổi.

## Ma trận làm việc

Ba ma trận dưới đây là phán đoán làm việc của đề tài để có trọng số Saaty và CR kiểm được. Đây chưa phải phiếu chuyên gia. RI của ma trận 3×3 lấy giá trị Saaty đã công bố, 0,58. Trọng số là vector riêng sau 60 vòng lặp lũy thừa. Ma trận có CR từ 0,1 trở lên bị từ chối, không bị ép cho ra điểm.

Thứ tự tiêu chí: điểm hút, bão hòa đối thủ, tiếp cận đường.

| Ngành | Phán đoán | Trọng số | CR |
| --- | --- | --- | --- |
| Cà phê | Đối thủ gấp 3 điểm hút và gấp 2 tiếp cận đường. Tiếp cận đường gấp 2 điểm hút. | 0,1634 / 0,5396 / 0,2970 | 0,0079 |
| Nhà thuốc | Điểm hút gấp 3 đối thủ và gấp 2 tiếp cận đường. Tiếp cận đường gấp 2 đối thủ. | 0,5396 / 0,1634 / 0,2970 | 0,0079 |
| Tiện lợi | Điểm hút gấp 4 đối thủ và gấp 2 tiếp cận đường. Tiếp cận đường gấp 3 đối thủ. | 0,5584 / 0,1220 / 0,3196 | 0,0158 |

Điểm không gian là tổng có trọng số của ba điểm thành phần.

## Chỉ số theo tiền thuê

Người gọi phải gửi `rent_vnd_per_month`, là số nguyên lớn hơn 0 và nhỏ hơn 1.000 tỷ. Chỉ số bằng `điểm không gian * 1.000.000 / tiền thuê tháng`. Đơn vị là điểm trên mỗi triệu đồng tiền thuê một tháng. Đây không phải ROI: hệ thống không có doanh thu, không có giá thuê thị trường, và không tự điền một mức thuê.

## Một điểm đã đo

`POST /api/scoring/evaluate` lúc 09:35 UTC+7 ngày 17/11/2026, tại node [Cà Phê Kafa](https://www.openstreetmap.org/node/423736651), tiền thuê do lời gọi đưa vào là 25.000.000 đồng/tháng. Thang phân vị lúc đó trùng bản lưu lúc 09:39. Vòng 500 m nằm trọn trong vùng, 24 điểm hút, khoảng cách trục đường 10,2 m. Điểm hút thành phần 33,8. Tiếp cận đường 96,4.

| Ngành | Đối thủ trong 500 m | Điểm bão hòa | Điểm không gian | Điểm / triệu đồng thuê |
| --- | --- | --- | --- | --- |
| Cà phê | 12 | 33,3 | 52,1 | 2,084 |
| Nhà thuốc | 1 | 80,0 | 59,9 | 2,396 |
| Tiện lợi | 7 | 30,0 | 53,3 | 2,132 |

`GET /api/scoring/reference` trả thang đang lưu, ba ma trận, CR và nhãn “chưa phải phiếu chuyên gia”.

## Hàng đợi ảnh

`POST /api/vision/jobs` nhận một ảnh JPEG, PNG hoặc WebP, tối đa 4 MB. Định dạng được nhận bằng byte đầu tệp, không bằng `Content-Type` khai báo. Dòng `vision_job` giữ ảnh, SHA-256 và trạng thái `queued`, `running`, `completed`, `poor_quality` hoặc `failed`. Id được đẩy vào list Redis `vision:queue`. Một luồng trong tiến trình API lấy việc bằng BRPOP. Lúc khởi động, việc còn `queued` hoặc `running` được xếp lại. Chỉ nên chạy một tiến trình API, vì hai tiến trình sẽ tranh cùng một hàng.

Mỗi lần đổi trạng thái, máy chủ gửi JSON việc đó tới `/topic/vision/{id}` qua STOMP ở `/ws`. Socket cho phép mọi origin trong môi trường làm đề tài và không có đăng nhập. `GET /api/vision/jobs/{id}` đọc cùng trạng thái khi không có client socket. Ảnh byte không nằm trong JSON trả về.

## Thẩm định thị giác

Ảnh không còn được gửi lên Gemini hoặc Groq, kể cả khi máy có khóa. Ảnh đủ điều kiện đi qua YOLOv8n trên máy này, rồi JSON đếm được giữ cho API tư vấn. API đó chưa được gắn, nên `advisory.status` là `waiting_for_api`, `advisory.text` là null và `image_sent` là false. Một PNG 1×1 kết thúc `poor_quality`, không có bề rộng và không có số đếm.

Bộ phân tích chỉ nhận JSON có `image_quality` là `ok` hoặc `poor`, `quality_reasons`, `recommendation` không quá 500 ký tự, và khi ảnh dùng được thì `sidewalk_capacity` thuộc `none`, `narrow`, `enough`, `wide` còn `visibility` thuộc `blocked`, `partial`, `clear`. Ảnh `poor` mà vẫn kèm nhận định vỉa hè hoặc tầm nhìn bị từ chối. Ảnh `poor` thành trạng thái `poor_quality`, không được chấm như điểm 0: ảnh hỏng không phải mặt bằng xấu.

Thang thứ tự, là quy ước chứ không phải số đo mét: vỉa hè 0 / 40 / 70 / 100, tầm nhìn 0 / 50 / 100. So sánh cặp của hai tiêu chí này luôn nhất quán. Cà phê coi tầm nhìn gấp đôi vỉa hè. Nhà thuốc coi vỉa hè gấp đôi tầm nhìn. Tiện lợi coi hai bên bằng nhau.

`vision_job_id` bỏ trống thì điểm không gian đứng một mình và `vision_applied` là false. Việc chưa xong, thất bại, hoặc `poor_quality` làm lời gọi chấm trả 409, điểm không gian không bị sửa. `composite_score` chỉ có khi người gọi gửi `blend_weight` trong khoảng 0 đến 1: `blend * điểm không gian + (1 - blend) * điểm ảnh`. Hệ thống không tự đặt tỷ lệ 70/30.

## Bề rộng vỉa hè đã học

Mô hình này dự đoán bề rộng bằng mét rồi đổi sang bốn mức theo quy ước mét đã chốt: dưới 0,90 là `none`, dưới 1,50 là `narrow`, dưới 3,00 là `enough`, còn lại là `wide`. Quy ước này không phải nhãn do người chấm. Mô hình không phải bản tinh chỉnh của Qwen2.5-VL và không có nhãn tầm nhìn.

Nguồn nhãn là Seoul Sidewalk Accessibility Image Dataset, DOI `10.5281/zenodo.22908353`, giấy phép CC0. 514 ảnh, bề rộng đo tại hiện trường từ 0,92 m đến 10,15 m. Cột `filename` ghi đuôi HEIC; file trong ba zip là JPG cùng tên gốc. Ba zip đã khớp MD5 công bố trên Zenodo. Ảnh không được đưa vào git. Cả 514 ảnh đều có EXIF orientation bằng 1.

Tách mẫu bằng SHA-256 của đúng chuỗi trong cột `filename`, chốt trước khi xem sai số: đuôi 0–1 là kiểm tra, đuôi 2 là chọn mô hình, đuôi 3–9 là học. Ra 349 / 53 / 112 ảnh. Đặc trưng là trung bình từng ô của ảnh xám `(299R + 587G + 114B) / 1000`, rồi chia 255. Hai cỡ ô 32×24 và 48×36, mỗi cỡ có ridge và mạng một lớp ẩn 16 nút, được chốt trước. Ứng viên nào sai số trên tập chọn thấp hơn thì được giữ. Tập kiểm tra không tham gia lựa chọn.

Lần đầu, ridge trên ảnh bilinear 32×24 có sai số kiểm tra 0,847 m. Lần chọn trên đặc trưng ô, mạng 48×36 có sai số chọn 0,719 m nhưng sai số kiểm tra 0,896 m.

Cách học đang dùng đổi sang đặc trưng dải và biên trên lưới 128×96: tám dải ngang, mỗi dải có trung bình, độ lệch, ba phần trái giữa phải và bão hòa màu; mười sáu hàng có tâm biên, độ rộng biên và mép trái. Các ứng viên chốt trước là ridge trên mét, ridge trên log, histogram, và trung bình k láng giềng. Ứng viên có sai số học nhỏ hơn một nửa sai số chọn thì bị loại. Lần trước, k = 5 có sai số chọn 0,544 m và sai số kiểm tra 0,568 m.

Lần này thêm k = 3, 7 và 9, vẫn trên cùng đặc trưng và cùng tập chọn. k = 7 có sai số chọn 0,537 m, thấp hơn 0,544 m, và sai số học 0,531 m không dưới một nửa sai số chọn, nên được thay. Sai số kiểm tra tính một lần sau khi chọn là 0,618 m. Khớp mức quy ước trên tập kiểm tra là 74,1%. Sai số này cao hơn 0,568 m của k = 5. Quy tắc chọn không được nhìn tập kiểm tra, nên mô hình đang dùng là k = 7. Dự đoán bằng trung bình tập học vẫn sai 1,270 m trên cùng 112 ảnh.

Trên tập kiểm tra, mức theo quy ước mét khớp 78,6%. Nếu luôn đoán mức chiếm đa số trong tập học, là `wide`, thì khớp 53,6%. Tập không có mẫu `none`.

Pipeline gọi mô hình bề rộng này trên máy, không gửi ảnh đi. Cổng `PhotoQuality` chặn ảnh có cạnh ngắn dưới 64 điểm, độ xám trung bình dưới 20 hoặc trên 240, phương sai dưới 50, trung bình trị tuyệt đối Laplace dưới 4, dưới 8/32 ô mức xám (bàn cờ), nhiễu điểm ảnh ngẫu nhiên, hoa văn tổng hợp đồng nhất và đồ họa phẳng nhân tạo thành `poor_quality` và không gán bề rộng. Với ảnh qua cổng chất lượng, mô hình bề rộng chỉ được gọi khi `SegFormer` và `Depth-Anything-V2` xác nhận cảnh mặt tiền tầng trệt có vỉa hè lộ rõ (`building_ratio >= 12%`, `sidewalk_ratio >= 3%`, `lower_sidewalk_ratio >= 5%`, `lower_pavement_ratio >= 20%`, `lower_occlusion_ratio <= 50%`, và loại trừ trần gầm cầu cạn `ceiling_depth_drop < 25%`). Ảnh đạt kiểm định trả `completed` với số mét đo trên vùng vỉa hè đã cắt (±0,62 m là MAE kiểm tra Seoul, chưa phải sai số đo tại Hà Nội), mức quy ước, `visibility` null và `assessed` false; ảnh không đạt kiểm định cảnh vẫn trả số đếm vật thể nhưng giữ `sidewalk_width_m` là null. Khi chấm một điểm cho cà phê, nhà thuốc hoặc tiện lợi, số mét hợp lệ được gắn vào kết quả nhưng không thành điểm ảnh và không trộn vào điểm tổng vì chưa có nhãn tầm nhìn. Gửi kèm `blend_weight` với ảnh này thì bị từ chối. Ảnh học ở Seoul, không phải mặt tiền bốn quận Hà Nội.

## Đếm vật thể và kiểm định cảnh 3D

Tầng đếm cục bộ (`etl/vision/yolo_count.py`, kiểm tra khóa SHA-256 trong `YoloCounter.java`, giới hạn chờ 30 giây, hỗ trợ JPEG/PNG/WebP qua `webp-imageio`) phối hợp bốn mô hình trên CPU:
- `yolov8m.pt` (`5d4a90cd…efe4fe5`): đếm `person`, `car`, `bus`, `truck`, `traffic_light`, `stop_sign` trên các ô cắt sẵn. Xe máy đi đường riêng: cả khung hình cộng lưới nửa khung, ngưỡng 0,15, gộp các hộp có tâm gần nhau (bán kính 0,16 lần cạnh hộp nhỏ hơn) rồi bỏ hộp cha khi tâm một hộp nhỏ hơn 35% diện tích nằm bên trong. Ô tô, buýt và tải trên cả khung hình lấy từ ngưỡng 0,18. `requested.car` vẫn là tổng ba lớp đó. Người ngồi trên xe hai bánh (`rider`, kể cả người ngồi sau) tách khỏi người đi bộ khi hộp người chồng hộp xe máy và tâm người nằm trên xe, hoặc khi vùng Cityscapes lớp `rider` chiếm ưu thế.
- `yolov8s-worldv2.pt` (`9b2c17ab…f27792`): nhận diện `street tree` và `hanging sign` với tensor prompt cố định.
- `segformer-b0-finetuned-cityscapes-1024-1024` (`1ed65f56…1d5`): phân đoạn 19 lớp với xác suất softmax thực trên từng vùng để đếm `utility_pole` (nối dọc qua khe dây cáp, loại trừ chân cọc biển báo và vùng trùng người), `traffic_sign`, tán cây `vegetation` (loại bồn hoa thấp tiền cảnh) và tính các tỷ lệ kiểm định cảnh.
- `depth-anything-v2-small` (`453e3d7f…2f85`, lưu fp16, chạy fp32 trên CPU): đo độ sâu tương đối để (1) loại gầm cầu cạn trên cao nhô về phía ống kính (`ceiling_depth_drop >= 0,25`) và (2) xác định biển báo đứng chắn trước nền công trình (`area_fraction >= 0,15%`, `local_building_ratio >= 30%`, `depth_lead >= 0,12`). Đây là kiểm tra hình học – độ sâu 3D, không phải nhãn tầm nhìn người chấm cho AHP.

Số đếm dưới đây lấy từ `etl/vision/yolo_count.py` trên CPU ngày 20/11/2026, đối chiếu nhãn mắt trong `etl/vision/hanoi_visible_counts.json`. Bề rộng chỉ đo khi cảnh mặt tiền đạt, và đo trên vùng vỉa hè đã cắt chứ không trên cả khung hình.
- `highlands-ha-noi.jpg` (mặt tiền Highlands Coffee Hà Nội, CC BY-SA 4.0): `scene_verified = true`, vùng cắt `[0, 528, 1178, 750]`, `sidewalk_width_m = 2,64` m. Cả khung hình trước khi cắt ra 4,22 m. 3 xe máy, 4 ô tô, 2 người đi bộ, 0 cột điện, 3 cây, 0 biển chắn. ±0,62 m là MAE của 112 ảnh kiểm tra trong bộ 514 mẫu Seoul, không phải sai số đo tại Hà Nội.
- `bien-bao-che-mat-tien.jpg` (ảnh chức năng, không thuộc nhãn đếm Hà Nội): `scene_verified = false`, `sidewalk_width_m = null`, 1 xe máy, 10 ô tô và 1 xe tải, 19 người đi bộ (tách 1 `rider`), 1 biển đứng chắn trực diện trước nền công trình.
- `dong-tac-dong-da.jpg` (ngã tư Đông Tác, Đống Đa, CC BY-SA 4.0): `scene_verified = false`, `sidewalk_width_m = null`, 16 xe máy, 6 ô tô, 2 người đi bộ (người quần xanh ở tiền cảnh là `person` độ tin cậy 0,908; tách riêng 8 `rider`), 1 cột điện, 6 cây, 0 biển chắn.
- `cot-dien.jpg` (ảnh chức năng, không thuộc nhãn đếm Hà Nội): `scene_verified = false`, `sidewalk_width_m = null`, 1 cột điện, 7 cây, 2 biển báo, 0 biển chắn.
- `xe-may-pho-ha-noi.jpg` (CC BY-SA 2.0): `scene_verified = false`, `sidewalk_width_m = null`, 34 xe máy, 1 xe tải, 22 người đi bộ (tách 5 `rider`), 6 cây, 0 biển chắn.
- `cau-giay-ha-noi.jpg` (dưới gầm cầu cạn đường Cầu Giấy, CC BY-SA 4.0): `scene_verified = false` (trần cầu cạn chênh sâu `+59,6%`), `sidewalk_width_m = null`, 9 xe máy, 3 ô tô và 1 xe buýt, 2 người đi bộ (tách 10 `rider`), 6 cây.
- `etl/vision/score_hanoi_counts.py` trên năm ảnh có nhãn mắt Hà Nội: 146/146 mục, điểm khớp nhãn **100,0**. Đây là điểm khớp khoảng đếm bằng mắt, chưa phải điểm ba giám khảo.
- Ảnh sóng sine một chiều, đồ họa ba dải phẳng, gradient, bàn cờ, nhiễu hạt và hoa văn đồng nhất bị chặn tại `PhotoQuality` trước khi đếm. PNG và WebP gắn EXIF orientation 6 được xoay cùng chiều với JPEG.

Quá trình chấm độc lập bởi ba giám khảo trên API đang chạy ngày 19/11/2026 (giờ Việt Nam):
- Lúc 13:54 (bản chỉ có YOLOv8n): 62, 55 và 58 -> trung bình cộng **58,3**.
- Lúc 17:35 (sau khi phân tích nguyên nhân mất điểm và sửa lỗi qua các vòng trung gian 64,0 -> 80,0 -> 82,7 -> 84,7 -> 85,0): ba giám khảo độc lập chấm **86, 87 và 82**, trung bình cộng **255/3 = 85,0** (tăng +26,7 điểm so với 58,3). Do trung bình 85,0 chưa vượt mức 90 nên giữ nguyên trạng thái `chờ duyệt`, không tự nâng điểm. Các điểm trừ còn lại đến từ giới hạn dữ liệu thực tế: chưa có bộ số đo mét vỉa hè và nhãn tầm nhìn thực địa tại Hà Nội (vẫn dựa trên 514 mẫu Seoul), sai số bỏ sót xe máy xếp chồng dày hoặc gộp tán cây liên thông trên mô hình tiền huấn luyện, và lệch đọc EXIF trên ảnh WebP/PNG xoay hướng.
- Lúc 10:52 ngày 20/11, vòng đếm lại trên đúng nhãn mắt năm ảnh Hà Nội (không dùng nhãn Seoul và không dùng ảnh Sai Ying Pun làm đáp án đếm) khớp 146/146, điểm **100,0**. Xe máy phố dày 34 (khoảng 32–70), Highlands vẫn 3, cây phố dày 5 và Đông Tác 5. Đây là điểm khớp nhãn mắt, chưa phải điểm giám khảo.
- Lúc 14:47 ngày 20/11, ba giám khảo độc lập chấm cây làm việc sau khi đã sửa xe buýt Cầu Giấy, tách tán Highlands, cổng ảnh xám và câu MAE. Điểm **88, 92 và 91**, trung bình cộng **271/3 = 90,3**. Dưới 95 nên không duyệt. Các điểm trừ còn lại là tách người đi bộ với người ngồi trên xe ở phố đông, và một số ngưỡng được chọn trên đúng năm ảnh mẫu.
- Lúc 23:07 ngày 22/11, người đứng cạnh xe không còn bị gọi là người ngồi trên xe khi hộp người cao và hẹp mà tâm không nằm trên xe. Đông Tác: người quần xanh là `person`. Phố xe máy: 5 `rider`. Phố đêm: 6 `rider`. Ba giám khảo chấm **84, 100 và 84**, trung bình **268/3 = 89,3**. Dưới 95 nên không duyệt. Hạ ngưỡng xe máy còn 0,10 chỉ ra mảnh của xe đã đếm.

## Bản đồ, so sánh và kiểm chứng vị trí

Trang `/` vẽ bốn quận, điểm hút khách và đối thủ của ngành đang chọn, vòng 200/500/1000 m quanh điểm người dùng bấm, và lớp nhiệt theo số đối thủ trong ô 0,002 độ. Ô nhiệt là số điểm, không phải mật độ trên km². Khung rộng hơn 0,25 độ bị từ chối. Với cà phê, khung phủ bốn quận trả 2862 điểm và không bị cắt.

`POST /api/scoring/compare` nhận 2 hoặc 3 mặt bằng, cùng ngành và cùng tiền thuê. Ba cánh radar là điểm hút khách, bão hòa đối thủ và tiếp cận trục đường. PDF tải từ `POST /api/scoring/compare.pdf` ghi lại đúng các số đó. Tiền thuê 25.000.000 đồng/tháng trên trang là mốc chung, không phải giá quan sát.

`GET /api/backtest` không đối chiếu cửa hàng còn sống với cửa hàng đã đóng. Lần mở rộng chấm mọi cơ sở đối thủ có tên, không còn lấy bốn cơ sở theo `osm_id` nhỏ nhất. Điểm cơ sở đã trừ chính cơ sở khỏi vòng 500 m. Lưới là 15 điểm `ST_GeneratePoints` trong mỗi quận, seed 1, rồi chấm cùng lưới cho cả ba ngành. Mốc một phần tư là điểm tại vị trí `floor(tỉ lệ × (số điểm − 1))` sau khi sắp xếp.

Trung vị điểm không gian, mức thuê mốc 25.000.000 đồng/tháng:

| Ngành | Số cơ sở có tên | Mốc một phần tư | Trung vị cơ sở | Trung vị 60 điểm lưới | Có `check_date` |
| --- | ---: | --- | ---: | ---: | ---: |
| Cà phê / đồ uống | 376 | 34,3–61,4 | 46,2 | 63,1 | 10 |
| Nhà thuốc | 69 | 47,8–68,1 | 60,2 | 59,6 | 3 |
| Cửa hàng tiện lợi | 308 | 44,6–65,9 | 55,8 | 56,6 | 9 |

`check_date` là ngày người vẽ bản đồ ghi đã xem, không phải số năm cửa hàng hoạt động. Chỉ một quán cà phê có `start_date`, ngày 2023-08-04. Với cả ba ngành, trung vị cơ sở có tên không cao hơn lưới một cách rõ: cà phê thấp hơn, nhà thuốc nhích hơn 0,6 điểm, tiện lợi gần như bằng.

Đã lọc lại file `vietnam-latest.osm.pbf` mốc 2026-11-15T21:22:28Z theo cà phê, nhà thuốc, tiện lợi, quán ăn, đồ ăn nhanh, quán bar và cửa hàng trống. Trong bốn quận vẫn chỉ có 3 điểm, đều là tiện lợi từng ngừng. Không có cà phê hay nhà thuốc mang thẻ tương ứng. Cả ba không có tên và không nằm trong bảng `poi`. Điểm không gian, cùng mức thuê mốc:

| Quận | OSM | Thẻ | Điểm |
| --- | --- | --- | ---: |
| Thanh Xuân | node 4739103928 | disused:shop | 28,1 |
| Cầu Giấy | node 12161473580 | disused:shop | 56,9 |
| Thanh Xuân | node 13006583946 | was:shop | 67,1 |

Ba điểm này không đủ nhóm 10–15 cửa hàng đã đóng.

Đối chiếu `vietnam-210101.osm.pbf` mốc 2021-01-01T21:42:03Z với bản 2026-11-15 thấy nhiều node ngành trong bốn quận không còn. Lời changeset lúc xóa phần lớn là dọn điểm Grab (`Adding POIs`) hoặc cập nhật cửa hàng WinMart+. Tập đó không được huấn luyện và không được gọi là cửa hàng đã đóng.

Nhóm có nguồn riêng, chấm cùng mức thuê mốc:

| Nhóm | Số điểm | Trung vị điểm để so | Cách biết |
| --- | ---: | ---: | --- |
| Người vẽ ghi không còn | 5 | 52,9 | Comment changeset: `closed`, `không tồn tại`, `not present`, `not existed` |
| Circle K còn trong danh sách hãng | 5 | 73,4 | Có ở cả trang Circle K lưu 2024-08-05 và 2026-07-10, khớp số nhà trên OpenStreetMap. Điểm đã trừ chính cửa hàng |

Năm điểm Circle K là 34,0; 65,4; 73,4; 73,7 và 85,6. Năm điểm người vẽ ghi không còn là 39,4; 41,0; 52,9; 65,2 và 75,5. Hai khoảng chồng lên nhau. Mẫu này không đủ để học một ngưỡng.

Mốc so sánh có thông số là 154 cơ sở đối thủ có đủ `brand`, số nhà và tên đường. Giờ mở cửa có ở 21 cơ sở, điện thoại ở 39, website ở 42. Trường không có thẻ thì để trống. Trung vị điểm không gian sau khi trừ chính cơ sở, với các thương hiệu có từ 3 cửa hàng:

| Thương hiệu | Ngành | Số cửa hàng | Trung vị |
| --- | --- | ---: | ---: |
| WinMart+ | Tiện lợi | 107 | 53,0 |
| The Coffee House | Cà phê / đồ uống | 15 | 58,7 |
| Circle K | Tiện lợi | 12 | 66,4 |
| Highlands Coffee | Cà phê / đồ uống | 4 | 68,4 |
| Pharmacity | Nhà thuốc | 4 | 57,2 |
| Ding Tea | Cà phê / đồ uống | 3 | 43,9 |
| GS25 | Tiện lợi | 3 | 75,9 |

Trong 12 Circle K có số nhà, 5 có trong danh sách hãng cả ngày 05/08/2024 và 10/07/2026, 4 chỉ có ở bản 10/07/2026, 3 không khớp danh sách đã lưu. Số 74 Nguyễn Khang không có đối tượng mang số nhà đó trên bản đồ, nên không được gán tọa độ. Báo Sức khỏe và Đời sống ngày 20/6/2024 ghi cửa hàng Circle K tại 74–76 Nguyễn Khang, phường Yên Hòa, Cầu Giấy, thông báo ngừng hoạt động từ 19/6/2024. Dòng này nằm trong `unlocated_closures`, không có điểm không gian.

## Việc chưa làm

Chưa có phiếu chuyên gia, chưa có doanh thu để thành ROI, và chưa có tài khoản người duyệt riêng từng người. Mã duyệt dùng chung đã có. Chưa có nhãn tầm nhìn hay ảnh mặt tiền Hà Nội đã gán mức. Chưa đủ 10–15 cửa hàng đã đóng có tọa độ để đối chiếu.
