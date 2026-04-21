# 🎯 Nhập Môn SRE: Tối Ưu Hiệu Suất & Xử Lý Nút Thắt Cổ Chai (Bottlenecks)

Chào bạn, tài liệu này được biên soạn để trang bị cho bạn **tư duy của một Kỹ sư Khả dụng Hệ thống (SRE - Site Reliability Engineer)**. Thay vì chỉ viết code cho chạy được, chúng ta sẽ học cách thiết kế code để **chạy được dưới khối lượng truy cập khổng lồ**.

Chúng ta sẽ đi qua một Case Study (Bài toán thực tế) đã được giả lập trong dự án của bạn để phân tích lỗi sai và cách khắc phục.

---

## 🏗️ 1. Bài Toán & Thiết Lập Hiện Trường

**Yêu cầu:** Xây dựng tính năng "Đăng bài viết kèm hình ảnh". Khi một bức ảnh lớn (vài Megabytes) được upload, máy chủ phải tốn thời gian xử lý và lưu nó vào ổ cứng (Disk I/O).

**Bối cảnh (Mô phỏng Production nhỏ):**
1. **Tomcat Max Threads (20)**: Server chỉ có tối đa 20 luồng (worker) để đón luồng truy cập (request) từ khách hàng.
2. **HikariCP Max Pool Size (5)**: Kho chứa chỉ có tối đa 5 kết nối Cơ sở dữ liệu (Database Connection) được phép mở cùng lúc.
3. **Mô phỏng Ổ cứng chậm**: Mỗi tấm ảnh cần lưu mất 0.5 giây (Giả lập bằng `Thread.sleep(500)`).

Hệ thống cung cấp hai phiên bản API:
- **Phiên bản Đồng bộ (Sync)**: Client gửi Request ➡️ Server lưu Text ➡️ Server lưu Ảnh vào ổ cứng (Chờ 0.5 giây/ảnh) ➡️ Báo thành công.
- **Phiên bản Bất đồng bộ (Async)**: Client gửi Request ➡️ Server lưu Text ➡️ Server ngắt luồng, trả lời thành công ngay lập tức ➡️ Chuyển việc lưu Ảnh cho một lực lượng chạy ngầm phía sau (Background Threads).

---

## 🕵️ 2. Mổ Xẻ Vấn Đề (Root Cause Analysis)

Khi chúng ta dùng `LoadTestSimulator` bắn khoảng 4000 lượt thao tác vào hai API trên, hệ thống ngay lập tức tử trận. Dưới đây là kiến thức sư phạm giúp bạn hiểu **Tại sao nó chết?**

> [!CAUTION]
> **Vấn đề Cốt lõi: Lạm dụng Annotation `@Transactional`**
> Trong Spring, `@Transactional` mở một kết nối (Connection) từ Database Pool ngay khi bắt đầu hàm và giữ chặt kết nối đó cho đến khi kết thúc hàm. Nếu bạn nhét các lệnh chậm chạp (Lưu file, gọi API bên thứ 3) vào bên trong kẹp này, Database Connection sẽ bị **giam lỏng một cách vô nghĩa**!

### Triệu chứng ở API Đồng Bộ (Sync Mode)
* **Hiện tượng:** Khách hàng bị treo màn hình rất lâu (trung bình 28 giây). Log Server văng đầy lỗi `CannotCreateTransactionException`. Tỉ lệ tạch cực kỳ cao.
* **Nguyên nhân y khoa:**
  - 1 User upload 3 ảnh mất 1.5 giây. Trong 1.5 giây đó, 1 DB Connection bị chiếm dụng (mặc dù nó chả làm gì ngoài việc ngủ chờ lưu ảnh). 
  - Nghĩa là chỉ cần 5 ông khách hàng thao tác cùng một lúc, 5/5 Connections bị bắt sạch. Khách hàng thứ 6 đến, xin tạo Connection, DB Pool bảo: *"Hết rồi, đứng đợi 30 giây đi"*. Quá 30 giây vẫn không có ai nhả Connection ra ➡️ Lỗi Timeout ném thẳng vào mặt User.
  - Tệ hại hơn, luồng phục vụ của Tomcat (Thread) không thể nhảy sang phục vụ User khác nếu luồng đó đang bị kẹt cứng (blocking) vì cái I/O siêu chậm trên!

### Triệu chứng ở API Bất Đồng Bộ (Async Mode)
* **Hiện tượng:** Khách hàng nhận được phản hồi nhanh (< 1 giây). Tuy nhiên, mở Database ra kiểm tra thì thấy có hơn 1100 bài đăng bị tạo "Mất Ảnh".
* **Nguyên nhân y khoa:**
  - Đội quân chạy ngầm Spring `@Async` được mặc định bởi một `ThreadPoolTaskExecutor` rất nhỏ (VD: Chứa 10 công nhân, hàng chờ chứa được 25 hồ sơ).
  - Khi 4000 User xô đẩy nhau, hàng chờ (Queue) ngay lập tức bị tràn. Những hồ sơ dư thừa chui vào sau sẽ **bị ném thẳng vào sọt rác** sinh ra lỗi `TaskRejectedException`. 
  - Khách hàng không hề biết ảnh mình bị ném sọt rác vì API chính đã trả về là thành công mất rồi ➡️ Dữ liệu vĩnh viễn thâm hụt (Vỡ tính nguyên tử của Data - Lack of Atomicity).

---

## 🛠️ 3. Giải Cứu & Tối Ưu Hệ Thống (SRE Solutions)

Bây giờ chúng ta sẽ châm cứu bắt mạch và đưa ra phác đồ cấp cứu.

### Bước 1: Rút ngắn giới tuyến của Transaction
* **Chỉnh sửa:** Loại bỏ `@Transactional` ở hàm Root mẹ. Chia nhỏ quy trình ra. Lưu Text và khởi tạo ID ở 1 tầng riêng. Trực tiếp chạy quá trình Disk I/O. Có link file hoàn chỉnh rồi, mới gọi riêng hàm `save()` vào DB lúc cuối.
* **Tác dụng:** `Thread.sleep(500)` vẫn bắt công nhân Tomcat dừng lại chờ, nhưng **người công nhân đó không cầm chìa khóa cửa kho của Database**. Database Connection rỗng tuếch nhàn rỗi cho hàng ngàn tiến trình khác mượn thoải mái. Tỉ lệ nghẽn nút chai DB giảm 100%.

### Bước 2: Nâng Cấp Lá Phổi Hệ Thống (`application.properties`)
* **Chỉnh sửa:** 
  - Nới lỏng Tomcat (`server.tomcat.threads.max=200`, `accept-count=100`) cho phép nó nhận một lúc vài trăm công khách mà không đánh rơi khách VIP.
  - Tăng `spring.datasource.hikari.maximum-pool-size=30`, cân bằng lại lượng ống nối tới CSDL một cách hợp lý thay vì bóp chết nó ở con số 5.

### Bước 3: Không Đánh Rơi Exception với Cấu Hình Ngầm
* **Chỉnh sửa:**
  - Tại `AsyncConfig.java`, mở rộng ThreadPool lên `MaxPoolSize=50` và hàng đợi `QueueCapacity=5000`.
  - **Kỹ thuật SRE chốt chặn (Rejection Policy):** Gắn thêm tính năng `CallerRunsPolicy()`. Đặc điểm của chính sách này: "Nếu kho chứa đầy, đích thân người nhờ tôi (Main Thread của Tomcat) phải tự đi mà xách file đi lưu, tôi cấm vứt đồ đi".
* **Tác dụng:** Chấm dứt vĩnh viễn lỗi mất ảnh khi server rơi vào tình trạng ngập lụt.

---

## 🎯 4. Lời Khuyên Dành Cho Intern (Kết Quả & Định Hướng)

Khi chạy lại `LoadTestSimulator`, tỉ lệ thành công đạt 100%. Không còn rớt cấu trúc DB hay nghẽn Cổ Chai.

**Tư duy dành cho bạn để vươn tầm High-Level Architecture:**
Đến đây, bạn đã biết cách cứu vớt 1 Server đơn chiếc. Nhưng khi công ty bạn có chiến dịch Flash Sale (Hàng ngàn RPS thực tế), kỹ thuật cục bộ là không đủ:
1. **Lên Cloud, Ngừng lưu tại chỗ:** Chuyển dịch vụ lưu ảnh ra khỏi cái đĩa cứng chậm chạp của Spring Boot. Cấp cho Frontend một đường link S3 (`Presigned URL`) và ép thiết bị của Frontend tự ném file tải lên không dây. Server bạn chỉ hớt lấy cái chuỗi `string URL` để gắn mác, xoá tận gốc ác mộng Blocking Disk I/O.
2. **Dùng Hàng Đợi Tin Nhắn:** Dẹp bỏ `@Async` ngầm ở CPU. Tải một con thỏ `RabbitMQ` hoặc `Apache Kafka`. Viết hoá đơn công việc, ném hoá đơn vào con RabbitMQ đó. Sẽ có hàng chục Server phụ (Worker/Consumer) đua nhau bắt hoá đơn đi xử lý. Sập một Server phụ? Hoá đơn vẫn nằm im trong hàng chờ RabbitMQ không bao giờ rớt.

*Hy vọng tài liệu sư phạm này giúp bạn hiểu tường tận bản chất của lập trình chịu tải!*
