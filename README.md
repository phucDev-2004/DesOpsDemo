# ⚡ DesOps Performance Lab

> **Từ Zero đến High-Concurrency** — Một lab kỹ thuật thực chiến: cố ý xây hệ thống bị vỡ, tìm nguyên nhân gốc rễ, và fix từng lớp bottleneck như một kỹ sư production thật sự.

---

## 🎯 Đây Là Gì?

Đây **không phải** một dự án tutorial copy-paste. Đây là một **lab kỹ thuật cá nhân** — nơi tôi cố tình xây một hệ thống có lỗi, đẩy nó đến giới hạn bằng load test, quan sát nó vỡ như thế nào, rồi tiến hành fix từng bước — đúng quy trình mà các đội SRE và Backend Platform làm trong môi trường production.

**Bài toán trung tâm của lab:** *"Điều gì xảy ra khi hàng nghìn người dùng đồng thời upload ảnh?"* — và tôi trả lời bằng cách đi qua từng failure mode, từng kiến trúc, từng bước sửa — cho đến khi hệ thống thực sự bền vững.

---

## 🧭 Hành Trình Kỹ Thuật

Mỗi phiên bản hệ thống kể một câu chuyện. Codebase giữ lại toàn bộ lịch sử implementation dưới dạng comment có chú thích để hành trình tiến hóa luôn hiển thị.

```
V1 ──► V2 ──► V3
SYNC   ASYNC  ASYNC
(Ngây thơ) (Lỗi ngầm) (An toàn production)
```

---

### 🔴 V1 — Đồng Bộ (Cách Làm Ngây Thơ)

**Hiện trạng:** Xây theo cách đơn giản nhất — một thread xử lý một request, I/O blocking hoàn toàn.

```
[Tomcat Thread] ──► Insert DB ──► Ghi File ──► Trả về Response
```

**Thứ gì vỡ khi có tải:**
- 200 users đồng thời → 200 Tomcat thread bị chiếm hết
- Request thứ 201 → xếp hàng chờ trong accept queue
- Request thứ 400 → connection timeout
- HikariCP pool cạn kiệt → `SQLTransientConnectionException`
- Response time tăng từ ~50ms lên 10.000ms+

**Nguyên nhân gốc rễ:** Blocking I/O giữ thread làm con tin. Web server không thể xử lý request mới vì toàn bộ thread đang ngồi chờ disk và database.

---

### 🟡 V2 — Bất Đồng Bộ (Cách Làm Có Lỗi Ngầm)

**Giải pháp thử nghiệm:** Chuyển xử lý ảnh sang background thread bằng `CompletableFuture`.

**Thứ gì vỡ âm thầm — không có exception, không có log lỗi:**

```
[Tomcat Thread] ──► Tạo CompletableFuture ──► Trả về 202 ✓
                            │
                   [Background Thread]
                            │
                   gọi MultipartFile.getBytes()
                            │
                   ❌ FILE ĐÃ BỊ XÓA — Tomcat dọn dẹp rồi!
```

**Nguyên nhân gốc rễ:** `MultipartFile` được backed bởi một file tạm thời gắn với vòng đời HTTP request. Ngay khi controller trả response, Tomcat dọn dẹp file tạm đó. Background thread giữ reference đến một resource đã bị xóa → **mất dữ liệu âm thầm, không exception nào được throw.**

Đây là loại bug nguy hiểm nhất trong production — ứng dụng không crash, chỉ đơn giản là dữ liệu không đầy đủ, và bạn không biết.

---

### 🟢 V3 — Async + DTO Pattern (An Toàn Production)

**Giải pháp:** Trích xuất `byte[]` từ `MultipartFile` vào một `ImageUploadDto` do ta sở hữu **trước khi** trả HTTP response. Background thread giờ làm việc trên bộ nhớ của chính nó — không mượn state từ vòng đời HTTP nữa.

```java
// Controller trích xuất bytes đồng bộ (vẫn trên Tomcat thread)
List<ImageUploadDto> fileData = files.stream()
    .map(f -> new ImageUploadDto(f.getOriginalFilename(), f.getBytes()))
    .collect(toList());

// Background thread sở hữu dữ liệu — an toàn về lifecycle
imageService.uploadImagesAsync(postId, fileData);

// Trả 202 ngay lập tức — HTTP thread được giải phóng
return ResponseEntity.accepted().build();
```

**Kiến trúc sau khi fix:**

```
[Tomcat Thread]  ──► Tạo Post ──► Trích bytes ──► Trả 202 Accepted
                                        │
                              [ForkJoinPool Thread]
                                        │
                              I/O ảnh song song ──► DB ──► Disk
```

**Kết quả:** Tomcat thread được giải phóng trong vài mili-giây. I/O nặng được tách rời và chạy trong async pool riêng.

---

## 🏗️ Kiến Trúc Hệ Thống

```
┌─────────────────────────────────────────────────────────────────┐
│                        TẦNG CLIENT                              │
│       Trình duyệt / Performance Dashboard / Load Simulator      │
└───────────────────────────┬─────────────────────────────────────┘
                            │ HTTP
┌───────────────────────────▼─────────────────────────────────────┐
│                    SPRING BOOT :8081                            │
│                                                                 │
│  ┌───────────────┐  ┌─────────────────┐  ┌──────────────────┐  │
│  │PostController │  │   PostService   │  │  ImageService    │  │
│  │               │  │                 │  │                  │  │
│  │ /posts        │  │ createPost()    │  │ uploadImages     │  │
│  │ /posts/full   │  │ createPostWith  │  │ Async()  ──────► │  │
│  │ /posts/{id}/  │  │ Images()        │  │  @Async          │  │
│  │   images      │  │ (CompletableFut)│  │  ForkJoinPool    │  │
│  └───────────────┘  └────────┬────────┘  └────────┬─────────┘  │
│                              │                    │             │
└──────────────────────────────┼────────────────────┼─────────────┘
                               │                    │
           ┌───────────────────▼───┐    ┌───────────▼──────────┐
           │  PostgreSQL :5432    │    │  File System         │
           │  (Docker Container)  │    │  ./uploads/          │
           └──────────────────────┘    └──────────────────────┘
```

---

## 📁 Cấu Trúc Dự Án

```
DesOpsDemo/
├── src/main/java/com/vanphuc/
│   ├── controller/
│   │   └── PostController.java        # REST endpoints (cả Sync lẫn Async)
│   ├── service/
│   │   ├── PostService.java           # Business logic + CompletableFuture
│   │   └── ImageService.java          # Async image I/O (lịch sử V1→V3 được giữ)
│   ├── dto/
│   │   ├── PostRequestDto.java
│   │   ├── PostResponseDto.java
│   │   └── ImageUploadDto.java        # DTO an toàn lifecycle — pattern cốt lõi
│   ├── entity/
│   │   ├── Post.java
│   │   └── Image.java
│   ├── config/
│   │   └── CorsConfig.java            # CORS cho dashboard kết nối API
│   └── util/
│       └── LoadTestSimulator.java     # Công cụ load test Java standalone
│
├── src/main/resources/
│   ├── application.properties         # Cấu hình tối ưu cho high-concurrency
│   └── static/
│       └── dashboard.html             # Dashboard monitoring thời gian thực
│
├── src/test/                          # Unit test với JUnit 5 + Mockito
├── docker-compose.yml                 # PostgreSQL trong Docker
├── system_evolution_analysis.md       # Phân tích chuyên sâu từng bottleneck
├── training_sre_performance.md        # Tài liệu luyện tập tư duy SRE
└── intern_training_cpu_management.md  # Lý thuyết thread pool, CPU vs I/O bound
```

---

## 🔑 Các Khái Niệm Kỹ Thuật Được Thực Hành

| Khái niệm | Nơi thực hiện |
|---|---|
| **Thread Pool Exhaustion** | V1 dưới tải — Tomcat bão hòa tại 200 thread |
| **HikariCP Connection Starvation** | V1 — DB pool cạn khi thread giữ connection |
| **HTTP Request Lifecycle** | Bug V2 — MultipartFile bị xóa sau khi response trả về |
| **DTO Pattern for Async Safety** | `ImageUploadDto` — sở hữu byte[], không mượn |
| **Servlet 3.0 Async / CompletableFuture** | `PostService.createPostWithImages()` |
| **Concurrency Batching** | Dashboard — bắn request theo batch kiểm soát |
| **DOM Virtualization** | Dashboard — giới hạn card render tại 200 node để tránh OOM |
| **Rate Limiting (nguyên lý)** | Ô Concurrency — mô phỏng rate limiter production |
| **Observability** | Metrics thời gian thực: latency, p50/p95/p99, success rate |

---

## 🚀 Khởi Động Nhanh

### Yêu Cầu

- Java 17+
- Maven 3.8+
- Docker + Docker Compose

### 1. Khởi động Database

```bash
docker-compose up -d
```

Lệnh này dựng PostgreSQL 15 trên port `5432` với database `social_db`.

### 2. Chạy Ứng Dụng

```bash
mvn spring-boot:run
```

Hoặc mở IntelliJ và chạy `Main.java` trực tiếp.

Server khởi động tại **http://localhost:8081**

### 3. Mở Performance Dashboard

```
http://localhost:8081/dashboard.html
```

Dashboard tự động nhận domain từ `window.location.origin` — không cần cấu hình thủ công, deploy lên server nào thì tự nhận domain server đó.

---

## 📊 Performance Dashboard

Giao diện monitoring thời gian thực tự xây dựng — không dùng framework, thuần HTML/CSS/JavaScript.

**Tính năng:**
- 🔧 **Server URL có thể chỉnh trực tiếp** — Đổi môi trường mà không cần sửa code
- ⚡ **Hai cột song song** — HTTP phản hồi ngay (trái) vs Xử lý ngầm async (phải)
- 🎯 **Trực quan hóa Pipeline** — Trạng thái request: Đang chờ → HTTP → HTTP xong → Background → Hoàn tất
- 📈 **Metrics sống** — Tổng, thành công, thất bại, latency trung bình, p95, throughput (req/s)
- 🛡️ **Kiểm soát Concurrency** — Batch size có thể chỉnh, mô phỏng tải an toàn
- 🧠 **DOM Virtualization** — Giới hạn card render tại 200 node, tránh trình duyệt hết RAM
- 💾 **localStorage** — Ghi nhớ cài đặt sau khi refresh trang

**So sánh ASYNC vs SYNC:**

| Chỉ số | SYNC | ASYNC |
|---|---|---|
| Thời gian phản hồi HTTP | Block đến khi I/O xong | Trả về trong ~10ms |
| Trải nghiệm người dùng | Chậm khi nhiều request | Phản hồi ngay lập tức |
| Throughput | Bị giới hạn bởi I/O + số thread | Tách rời khỏi I/O |
| Trạng thái background | Không có | Hiển thị ở cột phải |

---

## ⚙️ Cấu Hình

### Ứng dụng (`application.properties`)

```properties
# Web Server
server.port=8081
server.tomcat.threads.max=200       # Số thread Tomcat tối đa xử lý đồng thời
server.tomcat.accept-count=100      # Độ sâu queue trước khi từ chối request

# Connection Pool (tối ưu cho high-concurrency)
spring.datasource.hikari.maximum-pool-size=30
spring.datasource.hikari.minimum-idle=10
spring.datasource.hikari.connection-timeout=10000

# Upload File
spring.servlet.multipart.max-file-size=5MB
spring.servlet.multipart.max-request-size=20MB
```

### Tại sao lại chọn những giá trị này?

Cấu hình gốc dùng Hikari pool mặc định là **10 connection**. Với 200 thread đồng thời, mỗi thread giữ một DB connection → 190 thread timeout chờ pool. Tăng lên **30** và điều chỉnh `connection-timeout` giảm đáng kể tỉ lệ lỗi timeout.

---

## 🧪 Chạy Test

```bash
mvn test
```

Bộ test bao gồm:
- `PostServiceTest` — Xác nhận kiểu trả về `CompletableFuture`, hành vi `saveAll()` batch
- `ImageServiceTest` — Xác nhận `ImageUploadDto` là input đúng kiểu lifecycle-safe
- `PostControllerTest` — Xác nhận `@WebMvcTest` slice, response 202, tương tác với service

---

## 🗺️ Lộ Trình Tiếp Theo

Lab này dừng trước "đã giải quyết hoàn toàn" — vì hệ thống production thực sự yêu cầu nhiều tầng hơn nữa:

### Giai đoạn 2 — Độ Tin Cậy & Bền Vững
- [ ] **Message Queue (RabbitMQ/Kafka)** — Thay thế `CompletableFuture` bằng queue bền vững. App crash không mất job đang chờ xử lý
- [ ] **Outbox Pattern** — Đảm bảo tính nguyên tử giữa tạo Post và xử lý ảnh
- [ ] **Dead Letter Queue** — Bắt và replay các job upload ảnh thất bại

### Giai đoạn 3 — Scale Ra Ngoài
- [ ] **Object Storage (MinIO / AWS S3)** — Loại bỏ phụ thuộc disk cục bộ, cho phép scale ngang
- [ ] **Redis Cache** — Cache `PostResponseDto`, giảm áp lực đọc lên PostgreSQL
- [ ] **Dockerize toàn bộ** — `docker-compose` đầy đủ: app + db + cache + queue

### Giai đoạn 4 — Observability Production
- [ ] **Structured Logging** — JSON log với correlation ID để trace phân tán
- [ ] **Prometheus + Grafana** — Export metrics từ Spring Actuator, visualize trên Grafana
- [ ] **k6 Load Test Scripts** — Thay load test bằng trình duyệt bằng CLI chuyên dụng

---

## 📚 Tài Liệu Kèm Theo

| Tài liệu | Mô tả |
|---|---|
| [`system_evolution_analysis.md`](system_evolution_analysis.md) | Phân tích từng bước mỗi bottleneck và cách fix |
| [`training_sre_performance.md`](training_sre_performance.md) | Tư duy SRE: latency, throughput, error budget |
| [`intern_training_cpu_management.md`](intern_training_cpu_management.md) | Lý thuyết thread pool, CPU bound vs I/O bound |

---

## 👨‍💻 Tác Giả

**Văn Phúc** — Backend & DevOps Engineer đang trên lộ trình

Lab này là một phần trong chương trình tự đào tạo chuyên sâu về systems engineering: hiểu *tại sao* hệ thống fail — không chỉ biết *làm thế nào* để build khi mọi thứ đang ổn.