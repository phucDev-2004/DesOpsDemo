# 📖 Bài Đào Tạo SRE cấp tốc: Quản Làm Chủ CPU Server

Chào em (Intern)! Hôm nay anh sẽ hướng dẫn em giải bài toán kinh điển của mọi Backend Engineer: **Làm sao để giám sát "nhịp tim" (CPU) của Server, biết lúc nào nó quá tải (60%, 90%) và cách lên biện pháp phòng vệ**.

---

## 🌻 Tình Huống: Bài toán sống còn của CPU

Khi code của ta chỉ xoay quanh **I/O Bound** (đọc ghi file, gọi DB) thì CPU thường rất nhàn hạ rảnh rỗi. Nhưng đối với các tác vụ **CPU Bound** (như Băm mật khẩu BCrypt/SHA-256, Resize hình ảnh, nén file Zip, Tính toán vòng lặp lớn), CPU sẽ bị vắt kiệt. 
Nếu CPU cán mốc **100%**, toàn cục Server đóng băng, các Request dù nhẹ gọn nhất cũng bị gục ngã vì không có "bộ não" nào hở ra để phân luồng cho chúng cả.

---

## 1. Cách thiết lập cấu hình Cảnh Báo (Alerting) khi CPU > 60%

Để máy chủ "bí mật nhắn tin" báo động cho mình ngay khi CPU đạt 60%, ở cấp độ Production, các công ty thường xây dựng bộ 3 quyền lực: **Actuator + Prometheus + Grafana/AlertManager**.

- **Bước 1**: Nhúng thư viện `spring-boot-starter-actuator` và `micrometer-registry-prometheus` vào file `pom.xml`.
- **Bước 2**: Spring Actuator sẽ tự động lộ ra API tổng hợp nhịp tim tại `/actuator/prometheus`. Ở đây có chứa thông số `process_cpu_usage` (từ 0.0 đến 1.0).
- **Bước 3**: Server **Prometheus** (một hệ thống bên ngoài) sẽ tự động hút data từ `/actuator/prometheus` mỗi 15 giây.
- **Bước 4**: Viết rule dặn dò trong AlertManager:
```yaml
groups:
- name: alert.rules
  rules:
  - alert: HighCPUUsage
    expr: process_cpu_usage > 0.60
    for: 1m
    labels:
      severity: warning
    annotations:
      summary: "Cảnh báo ngập CPU Server!"
      description: "CPU của server đã vượt ngưỡng 60% trong 1 phút qua (Hiện tại: {{ $value | humanizePercentage }})."
```
👉 Kết quả: Khi CPU chạm 61% và giữ nguyên trong 1 phút, Prometheus kết hợp với AlertManager sẽ tự động bắn 1 cái tin nhắn báo động cực gắt vào **kênh chat Slack/Telegram** của nhóm Dev. Giúp anh em vào xử lý trước khi khách hàng kịp phàn nàn.

---

## 2. Mô phỏng "Ép Xung" CPU lên 90%
Để em hiểu CPU phồng rộp là như thế nào, anh vừa viết sẵn 1 API `CpuTestController.java` nhúng rễ ngay trong Source code của chúng ta.

Em hãy bật hệ thống tải API sau: 
`http://localhost:8081/cpu/spike-all-cores`

**Vấn Đề Gặp Phải (Quá Trình Đốt CPU):**
- Hàm này phát hiện em có bao nhiêu Core CPU vật lý (ví dụ: 8 Cores), nó sẽ đẻ ra 8 Thread tương ứng. Ở bên trong mỗi Thread là một vòng lặp Vô Tận (while true) làm toán phức tạp `Math.pow()`.
- Lệnh này ép tất cả Core phải làm toán đến quên ăn quên ngủ.
- **Hiện tượng**: Quạt tản nhiệt của máy tính em ráp sẽ bắt đầu gầm ré lên. Em mở `Task Manager` (Windows) hoặc gõ `htop` (Linux) sẽ thấy biểu đồ CPU Spring Boot chạm thẳng trần nhà 90%-100%. Nếu lúc này em thử gọi API `/posts` đơn giản, API đó sẽ response siêu chậm (Lag).

> *Lưu ý: Nếu bật Spike Test, em phải tắt nóng server Spring Boot đi bật lại để thoát vòng lặp vô tận nhé!*

---

## 3. Bí Kíp Ngăn Chặn CPU cán mốc 90% (Preventive Defenses)

Chúng ta không để Server nằm khấn hy vọng người dùng không tìm ra đường băm nát CPU. Dưới cương vị là SRE, anh em ta áp dụng 3 tấm khiên sau:

### Khiên 1: Cắt Giảm Lưu Lượng (Rate Limiting)
Đôi khi CPU lên 90% vì một gã Hacker hoặc Bot đang gửi 10,000 requests/s gọi vào API băm Password.
Dùng thư viện **Bucket4j** (hoặc Redis Rate Limiter): Đặt luật _"Chỉ cho phép 1 IP gọi API `/login` tối đa 5 lần mỗi phút"_. CPU được cứu rỗi từ vòng gửi xe.

### Khiên 2: Cô Lập Tài Nguyên (Bulkhead Pattern / Thread Pool Isolation)
Nếu hệ thống bắt buộc phải Resize ẢNH (việc này ngốn sạch CPU), em tuyệt đối không chạy nó trên Tomcat Default HTTP Threads.
- **Giải pháp**: Xây dựng một ThreadPool riêng kẹp khuôn cực hẹp: `MAX_THREADS = 2`.
- Như vậy khi có nghìn người gửi Ảnh, chỉ có 2 Cores CPU bị dồn để Resize Ảnh mà thôi. Các Core còn lại được bảo toàn để phục vụ những người chỉ vào Đọc/Xem Post (thao tác I/O rất nhẹ). Server em không bao giờ chấn thương chểt cứng.

### Khiên 3: Dùng Bộ Nhớ Đệm (Caching)
Thay vì bắt CPU phải chạy thuật toán cực kỳ đồ sộ từ vòng lặp Array để trả về list Top 10 bài viết xem nhiều.
- **Giải pháp**: CPU tính mệt mỏi ra Top 10 xong thì nhét kết quả đó vào **Memcached/Redis**. 
- 10.000 users sau vào, ta móc từ Redis ra trả về ngay trong 2 mili-giây. Bypass 100% logic tính toán của CPU.

---
Tuyệt vời! Em bắt đầu nắm luật sinh tồn của Resource System rồi đó. Có câu hỏi nào dành cho anh không?
