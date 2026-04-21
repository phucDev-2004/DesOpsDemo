# 🏗️ Phân Tích Toàn Bộ Hệ Thống: Hành Trình Từ Newbie → Senior

> **Mục tiêu bài toán:** Xây dựng API cho phép người dùng Tạo bài đăng + Upload nhiều ảnh cùng lúc,
> đủ sức phục vụ hàng nghìn người dùng đồng thời mà không sập.

---

## 📍 TỔNG QUAN LỘ TRÌNH TIẾN HÓA

```
STAGE 1           STAGE 2              STAGE 3               STAGE 4              STAGE 5
Naive Sync  →  Transactional   →   Async Naive     →   Async + DTO Fix   →   Non-Blocking CF
(Chết ngay)     Bug (Pool cạn)    (Mất ảnh hàng loạt)  (Ảnh đủ, nhưng      (Thật sự thả
                                                         Thread vẫn block)    Tomcat Thread)
```

---

## 🔴 STAGE 1: Bản Naïve Đầu Tiên — "Đơn giản mà chết"

### Code triển khai
```java
// PostController.java - Cách Junior viết
@PostMapping("/full")
public ResponseEntity<PostResponseDto> createPostSync(...) {
    PostResponseDto response = postService.createPostWithImages(postRequest, files);
    return ResponseEntity.status(201).body(response);
}

// PostService.java
@Transactional  // ← Bao bọc TOÀN BỘ hàm
public PostResponseDto createPostWithImages(...) {
    Post post = postRepository.save(post);             // 1. Lưu DB (mở connection)
    for (MultipartFile file : files) {
        Thread.sleep(500);                             // 2. Giả lập Disk I/O chậm (500ms/ảnh)
        file.transferTo(dest);                         // 3. Ghi file xuống ổ cứng
        imageRepository.save(image);                   // 4. Lưu DB
    }                                                  // Connection DB còn ĐANG GIỮ SUỐT VÒng LẶP
    return mapToResponseDto(post);
}
```

### Vấn đề gặp phải

| # | Vấn đề | Lý do kỹ thuật | Hậu quả |
|---|--------|----------------|---------|
| 1 | **Tomcat Thread bị Block** | Mỗi HTTP Request chiếm 1 OS Thread. Thread ngồi chờ Disk I/O (500ms x N ảnh). Max 200 Threads = chỉ đón được 200 người cùng lúc | 201 người trở đi: `Connection Timeout` |
| 2 | **DB Connection Pool cạn kiệt** | `@Transactional` giữ 1 Connection DB từ khi bắt đầu cho đến khi lưu ảnh cuối xong. Mỗi ảnh mất 500ms → Connection bị giam 500ms x N | `HikariPool - Connection is not available` |
| 3 | **Xử lý ảnh tuần tự** | Vòng `for` lưu ảnh lần lượt: ảnh 1 xong → ảnh 2. 3 ảnh = 1.5 giây BLOCK thẳng Thread Tomcat | Latency tuyến tính theo số ảnh |

### Kết quả Load Test
```
SYNC (Naïve):
  Tổng Requests:   3,677
  Thành công:       482   (13%!)
  Thất bại:       3,195   (87%!)
  Avg Latency:   11,172ms (11 giây! User bỏ ra về lâu rồi)
  p99 Latency:   23,820ms
```

### Sơ đồ luồng lỗi
```
User 1 request ──→ [Tomcat Thread-1] → @Transactional → DB_conn_1 LOCK
                        |
                        └─ Thread.sleep(500ms) ← BLOCK ← BLOCK ← BLOCK
                             ↓ (500ms sau)
User 2 request ──→ [Tomcat Thread-2] → @Transactional → DB_conn_2 LOCK
...
User 201 request → ❌ KHÔNG CÒN THREAD → "503 Service Unavailable"
User 35  request → ❌ DB_conn pool max=5 → "Connection timeout"
```

---

## 🟠 STAGE 2: Vá Lỗi DB — "Gỡ @Transactional nhưng Thread vẫn chết"

### Thay đổi
```java
// TRƯỚC: @Transactional bao bọc toàn bộ hàm (sai)
@Transactional
public PostResponseDto createPostWithImages(...) { ... }

// SAU: Bỏ @Transactional ở hàm ngoài, để JPA tự quản lý từng lệnh save() nhỏ
// @Transactional ← GỠ BỎ
public PostResponseDto createPostWithImages(...) {
    postRepository.save(post);       // JPA tự commit và đóng connection ngay sau dòng này
    for (MultipartFile file : files) {
        Thread.sleep(500);           // Disk I/O vẫn BLOCK Thread Tomcat!
        file.transferTo(dest);
        imageRepository.save(image); // JPA tự commit và đóng connection ngay sau dòng này
    }
}
```

### Vấn đề vẫn còn tồn đọng

| # | Vấn đề | Trạng thái |
|---|--------|-----------|
| DB Connection Pool cạn kiệt | ✅ **ĐÃ SỬA** — Connection được đóng ngay sau mỗi `.save()` |
| Tomcat Thread bị Block bởi Disk I/O | ❌ **VẪN CÒN** — Thread vẫn phải chờ 500ms/ảnh |
| Xử lý ảnh tuần tự | ❌ **VẪN CÒN** — Vẫn là vòng `for` lần lượt |

### Bài học
> **Gỡ DB bottleneck chưa đủ. Thread Pool Tomcat vẫn là điểm nghẽn chính.**
> Cần có chiến lược để Thread không phải chờ Disk I/O.

---

## 🟡 STAGE 3: Thêm ASYNC — "Giải phóng Thread nhưng mất ảnh hàng loạt"

### Thay đổi
```java
// PostController.java — BƯỚC 1: Tạo Post (nhẹ, nhanh ~5ms)
@PostMapping
public ResponseEntity<Map<String, UUID>> createPost(@RequestBody PostRequestDto dto) {
    UUID postId = postService.createPost(dto);
    return ResponseEntity.status(201).body(Map.of("postId", postId));
}

// PostController.java — BƯỚC 2: Upload ảnh (controller trả về NGAY)
@PostMapping("/{id}/images")
public ResponseEntity<?> uploadImages(@PathVariable UUID postId, List<MultipartFile> files) {
    imageService.uploadImagesAsync(postId, files); // Giao cho Background
    return ResponseEntity.accepted().body("Đang xử lý..."); // ← Trả về NGAY
}

// ImageService.java
@Async("taskExecutor")
public void uploadImagesAsync(UUID postId, List<MultipartFile> files) {  // ← files vẫn là MultipartFile!
    for (MultipartFile file : files) {
        String url = saveFileToDisk(file); // ← SẼ QUĂNG IOException VÀI MS SAU
    }
}
```

### Bug nghiêm trọng: Mất ảnh hàng loạt

```
VÒNG ĐỜI CỦA MULTIPARTFILE:

T=0ms:    User gửi request lên → Tomcat tạo MultipartFile (lưu tạm vào /tmp)
T=1ms:    Controller giao file cho @Async
T=2ms:    Controller return "202 Accepted" → HTTP Response gửi về Client
T=3ms:    ⚠️ Tomcat KẾT THÚC Request → Tomcat DỌN RÁC /tmp → MultipartFile BỊ XÓA
T=503ms:  Background Thread tỉnh dậy → gọi file.transferTo() → ❌ IOException: Stream Closed!
```

### Kết quả tai hại
```
Load Test:
  3,723 bài viết được tạo = 3,723 request ASYNC "thành công"
  Ảnh lưu được trong DB:  909 bản ghi (chỉ ~24%!)
  
→ 2,814 bài viết mồ côi: Có Post, KHÔNG có ảnh
→ Người dùng thấy "Upload thành công" nhưng ảnh KHÔNG BAO GIỜ xuất hiện
→ ĐÂY LÀ LOẠI BUG NGUY HIỂM NHẤT: Server không báo lỗi, Client không biết!
```

### Bài học
> **MultipartFile là "đồ đi mượn" — phải về trả chủ khi Request kết thúc.**
> Bất kỳ thứ gì cần sống sót qua vòng đời Request phải được sao chép sang bộ nhớ tự sở hữu.

---

## 🔵 STAGE 4: Sao chép Byte[] — "An toàn dữ liệu, nhưng Thread vẫn Block 1 chút"

### Thay đổi (Tạo ImageUploadDto)
```java
// ImageUploadDto.java — DTO tự sở hữu dữ liệu
@Data @Builder @AllArgsConstructor @NoArgsConstructor
public class ImageUploadDto {
    private String filename;
    private byte[] data;  // ← Byte Array thuộc về RAM của ứng dụng, không phải của Tomcat
}

// PostController.java — Trích xuất byte TRƯỚC khi trả Response
@PostMapping("/{id}/images")
public ResponseEntity<?> uploadImages(UUID postId, List<MultipartFile> files) {
    List<ImageUploadDto> fileData = files.stream().map(file -> {
        return new ImageUploadDto(file.getOriginalFilename(), file.getBytes()); // ← Copy byte ra ngay
    }).collect(toList());
    
    imageService.uploadImagesAsync(postId, fileData); // ← Truyền DTO (an toàn)
    return ResponseEntity.accepted().body("Đang xử lý...");
}

// ImageService.java — Nhận DTO thay vì MultipartFile
@Async("taskExecutor")
public void uploadImagesAsync(UUID postId, List<ImageUploadDto> files) { // ← ImageUploadDto!
    for (ImageUploadDto fileDto : files) {
        Files.write(dest.toPath(), fileDto.getData()); // ← Ghi byte[] (luôn tồn tại)
    }
}
```

### Kết quả sau khi fix
```
Bài viết được tạo: 3,756
Ảnh lưu được:     3,756 x (số file mỗi request) ← 100% khớp!
Mất dữ liệu:      0 bản ghi!
```

### Vấn đề còn tồn đọng một chút
Tính năng Sync `/posts/full` vẫn còn: Thread Tomcat chờ đến khi tất cả I/O xong mới về. Chỉ giảm thời gian nhờ song song hoá, nhưng Thread vẫn bị Block trong suốt thời gian đó.

---

## 🟢 STAGE 5 (HIỆN TẠI): CompletableFuture — "Giải phóng Thread hoàn toàn"

### Thay đổi
```java
// PostService.java — VERSION 3: Trả về CompletableFuture thay vì PostResponseDto
public CompletableFuture<PostResponseDto> createPostWithImages(...) {
    Post post = postRepository.save(...);  // Nhanh ~2ms, vẫn chạy trên Tomcat Thread
    final Post finalPost = post;

    // Bắn tất cả file I/O song song — không chờ ai
    List<CompletableFuture<Image>> futures = files.stream()
        .map(file -> CompletableFuture.supplyAsync(() -> {
            String url = saveFileToDisk(file); // ← Chạy trên ForkJoinPool Thread (không phải Tomcat)
            return Image.builder().url(url).post(finalPost).build();
        }))
        .collect(toList());

    // allOf() tạo ra "Hợp đồng tương lai" — KHÔNG .join() ở đây!
    return CompletableFuture.allOf(futures.toArray(...))
        .thenApply(v -> {                    // ← Callback tự động chạy khi I/O xong
            imageRepository.saveAll(...);
            return mapToResponseDto(finalPost);
        });
}

// PostController.java — Trả về CompletableFuture → Spring tự Suspend HTTP Request
public CompletableFuture<ResponseEntity<PostResponseDto>> createPostSync(...) {
    return postService.createPostWithImages(...)
        .thenApply(response -> ResponseEntity.status(201).body(response));
    // ↑ Lúc này Tomcat Thread đã được GIẢI PHÓNG HOÀN TOÀN VỀ POOL!
}
```

### Sơ đồ luồng Non-Blocking

```
T=0ms:   User → HTTP Request → [Tomcat Thread-5]
T=1ms:   Thread-5: lưu Post vào DB (nhanh)
T=2ms:   Thread-5: tạo 3 CompletableFuture, bắn sang ForkJoinPool
T=3ms:   Thread-5: trả CompletableFuture về Spring MVC
T=3ms:   Spring MVC: thấy CF → Suspend HTTP Request → TRẢ THREAD-5 VỀ POOL
T=3ms:   [Tomcat Thread-5] ← ĐÃ TỰ DO → Đón User mới ngay!

         (Trong khi đó, ngầm bên dưới...)
T=3ms~503ms: [ForkJoinPool-worker-1] lưu ảnh 1
T=3ms~503ms: [ForkJoinPool-worker-2] lưu ảnh 2  (Song song!)
T=3ms~503ms: [ForkJoinPool-worker-3] lưu ảnh 3  (Song song!)
T=504ms: allOf() hoàn thành → thenApply() Callback kích hoạt
T=505ms: saveAll() vào DB
T=506ms: Spring MVC đóng gói Response → Gửi "201 Created" về User
```

### So sánh hiệu suất cuối cùng

| Chỉ số | SYNC (Stage 1) | ASYNC Fix (Stage 4-5) |
|--------|---------------|----------------------|
| Tổng Requests | 3,677 | 3,756 |
| Thành công | **482 (13%)** | **3,723 (99%)** |
| Thất bại | 3,195 (87%) | 33 (~1%) |
| Tổng thời gian | 276,052ms | 8,788ms |
| Avg Latency | 11,172ms | 343ms |
| p50 Latency | 10,103ms | 317ms |
| p95 Latency | 21,933ms | 605ms |
| p99 Latency | 23,820ms | 1,150ms |
| Mất ảnh | N/A | **0 bản ghi** |

---

## 🗺️ BẢN ĐỒ TOÀN BỘ CÁC VẤN ĐỀ ĐÃ GIẢI QUYẾT

```
VẤN ĐỀ                  NGUYÊN NHÂN GỐC RỄ           GIẢI PHÁP ÁP DỤNG
─────────────────────────────────────────────────────────────────────────
DB Pool cạn kiệt    ←── @Transactional bao I/O    ──→ Gỡ @Transactional
Thread Pool cạn     ←── Disk I/O Block Thread     ──→ CompletableFuture
Ảnh bị mất hàng loạt←── MultipartFile Lifecycle  ──→ ImageUploadDto (byte[])
Latency cao         ←── Xử lý ảnh tuần tự        ──→ supplyAsync() Song song
```

---

## 🚀 GIỚI HẠN HIỆN TẠI & BƯỚC TIẾP THEO

Dù đã tốt hơn rất nhiều, hệ thống hiện tại vẫn còn 2 điểm yếu chết người ở Production:

### ⚠️ Điểm yếu 1: Không đảm bảo Atomicity (Tính nguyên tử)
```
Tình huống: Post lưu thành công → Background đang lưu ảnh → Server bị cúp điện
Kết quả:    Post tồn tại trong DB nhưng không có ảnh → Dữ liệu không nhất quán
Giải pháp:  Outbox Pattern + Message Queue (RabbitMQ)
```

### ⚠️ Điểm yếu 2: Không Scale được theo chiều ngang (Horizontal)
```
Tình huống: 50,000 người Upload ảnh cùng lúc → ForkJoinPool & 1 Server không gánh nổi
Giải pháp:  Tách "Xử lý ảnh" thành Worker Service riêng
            → Dùng RabbitMQ làm "ống cống phân luồng"
            → Deploy 50 Worker Container song song nhau
```

### Lộ trình tiếp theo
```
Hiện tại (Done)           Bước tiếp theo              Bước xa hơn
──────────────────────────────────────────────────────────────────
Spring Boot Async  →   Redis (Cache hot data)   →  Kubernetes (K8s)
CompletableFuture  →   RabbitMQ (Message Queue) →  Service Mesh
HikariCP tuned     →   Docker Compose           →  Cloud S3 Storage
                   →   Outbox Pattern           →  CDN (Ảnh tĩnh)
```

---

> 📌 **Kết luận cho Intern:** Mỗi vấn đề bạn gặp trong hành trình này không phải là sai lầm, mà là một cấp độ hiểu biết bạn vừa mở khóa. Senior không phải người không mắc lỗi — Senior là người nhận ra lỗi sớm hơn và biết chính xác cần đổi cái gì để sửa.
