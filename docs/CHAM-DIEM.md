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

Nếu có `GEMINI_API_KEY` thì gọi Gemini `generateContent`. Nếu không, nhưng có `GROQ_API_KEY`, thì gọi Groq `chat/completions`. Tên mô hình bắt buộc nằm ở `GEMINI_MODEL` hoặc `GROQ_MODEL`. Khóa không được ghi log. Không có khóa thì dùng mô hình bề rộng ở mục dưới. Một PNG 1×1 kết thúc `poor_quality`, không có bề rộng.

Bộ phân tích chỉ nhận JSON có `image_quality` là `ok` hoặc `poor`, `quality_reasons`, `recommendation` không quá 500 ký tự, và khi ảnh dùng được thì `sidewalk_capacity` thuộc `none`, `narrow`, `enough`, `wide` còn `visibility` thuộc `blocked`, `partial`, `clear`. Ảnh `poor` mà vẫn kèm nhận định vỉa hè hoặc tầm nhìn bị từ chối. Ảnh `poor` thành trạng thái `poor_quality`, không được chấm như điểm 0: ảnh hỏng không phải mặt bằng xấu.

Thang thứ tự, là quy ước chứ không phải số đo mét: vỉa hè 0 / 40 / 70 / 100, tầm nhìn 0 / 50 / 100. So sánh cặp của hai tiêu chí này luôn nhất quán. Cà phê coi tầm nhìn gấp đôi vỉa hè. Nhà thuốc coi vỉa hè gấp đôi tầm nhìn. Tiện lợi coi hai bên bằng nhau.

`vision_job_id` bỏ trống thì điểm không gian đứng một mình và `vision_applied` là false. Việc chưa xong, thất bại, hoặc `poor_quality` làm lời gọi chấm trả 409, điểm không gian không bị sửa. `composite_score` chỉ có khi người gọi gửi `blend_weight` trong khoảng 0 đến 1: `blend * điểm không gian + (1 - blend) * điểm ảnh`. Hệ thống không tự đặt tỷ lệ 70/30.

## Bề rộng vỉa hè đã học

Mô hình này dự đoán bề rộng bằng mét rồi đổi sang bốn mức theo quy ước mét đã chốt: dưới 0,90 là `none`, dưới 1,50 là `narrow`, dưới 3,00 là `enough`, còn lại là `wide`. Quy ước này không phải nhãn do người chấm. Mô hình không phải bản tinh chỉnh của Qwen2.5-VL và không có nhãn tầm nhìn.

Nguồn nhãn là Seoul Sidewalk Accessibility Image Dataset, DOI `10.5281/zenodo.22908353`, giấy phép CC0. 514 ảnh, bề rộng đo tại hiện trường từ 0,92 m đến 10,15 m. Cột `filename` ghi đuôi HEIC; file trong ba zip là JPG cùng tên gốc. Ba zip đã khớp MD5 công bố trên Zenodo. Ảnh không được đưa vào git. Cả 514 ảnh đều có EXIF orientation bằng 1.

Tách mẫu bằng SHA-256 của đúng chuỗi trong cột `filename`, chốt trước khi xem sai số: đuôi 0–1 là kiểm tra, đuôi 2 là chọn mô hình, đuôi 3–9 là học. Ra 349 / 53 / 112 ảnh. Đặc trưng là trung bình từng ô của ảnh xám `(299R + 587G + 114B) / 1000`, rồi chia 255. Hai cỡ ô 32×24 và 48×36, mỗi cỡ có ridge và mạng một lớp ẩn 16 nút, được chốt trước. Ứng viên nào sai số trên tập chọn thấp hơn thì được giữ. Tập kiểm tra không tham gia lựa chọn.

Lần đầu, ridge trên ảnh bilinear 32×24 có sai số kiểm tra 0,847 m. Lần chọn trên đặc trưng ô, mạng 48×36 có sai số chọn 0,719 m nhưng sai số kiểm tra 0,896 m.

Cách học đang dùng đổi sang đặc trưng dải và biên trên lưới 128×96: tám dải ngang, mỗi dải có trung bình, độ lệch, ba phần trái giữa phải và bão hòa màu; mười sáu hàng có tâm biên, độ rộng biên và mép trái. Các ứng viên chốt trước là ridge trên mét, ridge trên log, histogram, và trung bình 5, 11 hoặc 21 láng giềng. Ứng viên có sai số học nhỏ hơn một nửa sai số chọn thì bị loại. Trung bình 5 láng giềng có sai số chọn 0,544 m, thấp nhất và thấp hơn 0,719 m, nên thay mô hình cũ. Sai số kiểm tra, tính một lần sau khi chọn, là 0,568 m. Sai số học 0,473 m bị lạc quan vì mỗi ảnh học nằm trong chính nhóm láng giềng của nó. Dự đoán bằng trung bình tập học thì sai 1,270 m trên cùng 112 ảnh.

Trên tập kiểm tra, mức theo quy ước mét khớp 78,6%. Nếu luôn đoán mức chiếm đa số trong tập học, là `wide`, thì khớp 53,6%. Tập không có mẫu `none`.

Không có khóa Gemini hoặc Groq thì pipeline gọi mô hình này. Ảnh có cạnh ngắn dưới 64 điểm, độ xám trung bình dưới 20 hoặc trên 240, hoặc phương sai dưới 50 thì thành `poor_quality` và không có bề rộng. Ngưỡng này nằm dưới phân bố 40 ảnh học đã xem trước, nơi độ xám trung bình khoảng 76–143 và phương sai trên 3000. Ảnh dùng được trả `completed` với số mét, mức quy ước, `visibility` null và `assessed` false. Khi chấm một điểm cho cà phê, nhà thuốc hoặc tiện lợi, số mét được gắn vào kết quả nhưng không thành điểm ảnh và không trộn vào điểm tổng, vì chưa có tầm nhìn. Gửi kèm `blend_weight` với ảnh này thì bị từ chối. Ảnh học ở Seoul, không phải mặt tiền bốn quận Hà Nội.

## Bản đồ, so sánh và kiểm chứng vị trí

Trang `/` vẽ bốn quận, điểm hút khách và đối thủ của ngành đang chọn, vòng 200/500/1000 m quanh điểm người dùng bấm, và lớp nhiệt theo số đối thủ trong ô 0,002 độ. Ô nhiệt là số điểm, không phải mật độ trên km². Khung rộng hơn 0,25 độ bị từ chối. Với cà phê, khung phủ bốn quận trả 2862 điểm và không bị cắt.

`POST /api/scoring/compare` nhận 2 hoặc 3 mặt bằng, cùng ngành và cùng tiền thuê. Ba cánh radar là điểm hút khách, bão hòa đối thủ và tiếp cận trục đường. PDF tải từ `POST /api/scoring/compare.pdf` ghi lại đúng các số đó. Tiền thuê 25.000.000 đồng/tháng trên trang là mốc chung, không phải giá quan sát.

`GET /api/backtest` không đối chiếu cửa hàng còn sống với cửa hàng đã đóng. Bảng `raw_poi` có 7385 dòng, 0 thẻ `disused` và 0 thẻ `abandoned`. Mười hai cơ sở là mỗi quận một cơ sở có tên cho mỗi ngành, chọn `osm_id` nhỏ nhất, không phải mẫu ngẫu nhiên và không phải nhóm hoạt động lâu năm. Mười hai điểm lưới là `ST_GeneratePoints` trong ranh mỗi quận, seed 1, ba điểm một quận. Điểm cơ sở dùng để so trung vị đã trừ một đối thủ vì chính cơ sở nằm ở tâm vòng 500 m.

Trung vị điểm không gian, mức thuê mốc 25.000.000 đồng/tháng:

| Ngành | 4 cơ sở, đã trừ chính nó | 12 điểm lưới |
| --- | ---: | ---: |
| Cà phê / đồ uống | 42,3 | 70,7 |
| Nhà thuốc | 45,3 | 62,5 |
| Cửa hàng tiện lợi | 58,6 | 59,5 |

Bốn cơ sở mỗi ngành không nằm ở điểm cao hơn lưới. Mẫu quá nhỏ và cách chọn theo `osm_id` không đại diện cho cửa hàng thành công.

## Việc chưa làm

Chưa có phiếu chuyên gia, chưa có doanh thu để thành ROI, chưa có tài khoản người duyệt, và chưa có nhãn tầm nhìn hay ảnh mặt tiền Hà Nội đã gán mức. Chưa có danh sách cửa hàng đã đóng cửa để đối chiếu.
