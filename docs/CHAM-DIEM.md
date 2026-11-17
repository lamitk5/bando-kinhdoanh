# Chấm điểm và thẩm định ảnh

Ba hạng mục này đọc cùng dữ liệu không gian đã nạp. Điểm không gian là số tính được. Kết quả ảnh chỉ có khi một mô hình thị giác trả JSON và bộ phân tích chấp nhận. Không có khóa API thì việc ảnh kết thúc `failed`, không sinh nhận định vỉa hè.

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

Nếu có `GEMINI_API_KEY` thì gọi Gemini `generateContent`. Nếu không, nhưng có `GROQ_API_KEY`, thì gọi Groq `chat/completions`. Tên mô hình bắt buộc nằm ở `GEMINI_MODEL` hoặc `GROQ_MODEL`. Khóa không được ghi log. Không có khóa thì client ném lỗi tiếng Việt và việc thành `failed`. Lần kiểm tra bằng một PNG 1×1 đã kết thúc đúng như vậy.

Bộ phân tích chỉ nhận JSON có `image_quality` là `ok` hoặc `poor`, `quality_reasons`, `recommendation` không quá 500 ký tự, và khi ảnh dùng được thì `sidewalk_capacity` thuộc `none`, `narrow`, `enough`, `wide` còn `visibility` thuộc `blocked`, `partial`, `clear`. Ảnh `poor` mà vẫn kèm nhận định vỉa hè hoặc tầm nhìn bị từ chối. Ảnh `poor` thành trạng thái `poor_quality`, không được chấm như điểm 0: ảnh hỏng không phải mặt bằng xấu.

Thang thứ tự, là quy ước chứ không phải số đo mét: vỉa hè 0 / 40 / 70 / 100, tầm nhìn 0 / 50 / 100. So sánh cặp của hai tiêu chí này luôn nhất quán. Cà phê coi tầm nhìn gấp đôi vỉa hè. Nhà thuốc coi vỉa hè gấp đôi tầm nhìn. Tiện lợi coi hai bên bằng nhau.

`vision_job_id` bỏ trống thì điểm không gian đứng một mình và `vision_applied` là false. Việc chưa xong, thất bại, hoặc `poor_quality` làm lời gọi chấm trả 409, điểm không gian không bị sửa. `composite_score` chỉ có khi người gọi gửi `blend_weight` trong khoảng 0 đến 1: `blend * điểm không gian + (1 - blend) * điểm ảnh`. Hệ thống không tự đặt tỷ lệ 70/30.

## Việc chưa làm

Chưa có phiếu chuyên gia, chưa có doanh thu để thành ROI, chưa có tài khoản người duyệt, và chưa có lần gọi mô hình thật vì môi trường này chưa giữ khóa. Back-test 10–15 cửa hàng vẫn là hạng mục riêng.
