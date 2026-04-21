# Ngữ Cảnh Dự Án (Project Context)

## Thông tin chung
Dự án này (`DesOpsDemo`) là hệ thống Spring Boot mô phỏng theo mô hình bài viết (post system) giống Facebook thu nhỏ.

## Cấu trúc Database (PostgreSQL)
Cơ sở dữ liệu tập trung vào 2 entities chính:
- **`posts`**: Bảng chứa thông tin bài viết gốc (id: UUID - Khóa chính, title, content, created_at).
- **`images`**: Bảng chứa thông tin hình ảnh đi kèm bài viết (id: UUID - Khóa chính, url, post_id khóa ngoại).
- **Quan hệ**: 1 Post có thể chứa Nhiều Image (`@OneToMany`). 1 Image nằm trong 1 Post (`@ManyToOne`).

## Thiết kế API
Dự án cung cấp 2 phương thức (kịch bản) tạo bài viết đính kèm hình ảnh:

1. **CASE 1 (Xử lý đồng bộ - Sync):** 
   - Endpoint: `POST /posts/full` (multipart/form-data)
   - Lưu trữ post, lưu ảnh xuống storage cục bộ (thư mục `/uploads`), cập nhật ảnh vào DB. Mọi thứ trong cùng 1 request-response lifecycle. Người dùng phải đợi đến khi mọi công đoạn upload hoàn tất.

2. **CASE 2 (Xử lý bất đồng bộ - Async chạy ngầm):** 
   - Trải nghiệm thực tế (dành cho UX tốt hơn), chia làm 2 bước:
   - **Bước 1**: `POST /posts` nhận nội dung text bài viết, lưu DB và trả về `postId` lập tức.
   - **Bước 2**: `POST /posts/{id}/images` (multipart/form-data) để gửi ảnh lên. Phương thức này không khóa luồng. Thread Pool Executor (`AsyncConfig`) sẽ xử lý các ảnh này ở background thông qua `ImageService.uploadImagesAsync`. Controller sẽ response ngay báo hiệu tiến trình đang diễn ra ngầm `202 ACCEPTED`.
   - *Lưu ý*: Simple Async Demo chưa xử lý giao dịch ngoại lai (như upload fail giữa đoạn) vì chưa có Message Queue/ Dead Letter.

## Testing Stack
- Packages: `src/test/java/com/vanphuc/`
- Công nghệ: `JUnit 5`, `Mockito` (Mock `PostRepository` và `ImageRepository`)
- Mục đích: Cover service tests bao gồm việc giả lập Post creation logic và kiểm thử việc kích hoạt hàm (method trigger event) của `@Async`.
