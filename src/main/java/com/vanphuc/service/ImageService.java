package com.vanphuc.service;

import com.vanphuc.dto.ImageUploadDto;
import com.vanphuc.entity.Image;
import com.vanphuc.entity.Post;
import com.vanphuc.repository.ImageRepository;
import com.vanphuc.repository.PostRepository;
import com.vanphuc.util.CsvLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImageService {

    private final ImageRepository imageRepository;
    private final PostRepository postRepository;

    @Value("${file.upload-dir:uploads}")
    private String uploadDir;

    // ==========================================
    // 🔴 VERSION 1 (BUG: MẤT ẢNH VÌ VÒNG ĐỜI MULTIPARTFILE)
    // Lí do mất ảnh (3723 Post Async thành công nhưng chỉ lưu được lèo tèo VÀI ẢNH):
    // Đối tượng MultipartFile dính chặt với vòng đời của luồng HTTP Request (Tomcat thread). 
    // Ngay khi Controller trả về "202 Accepted", Tomcat sẽ lập tức XÓA file tạm trong bộ nhớ đệm. 
    // Vài mili-giây sau, khi Background Thread tỉnh dậy cầm MultipartFile đi lưu, cái file đó đã KHÔNG CÒN TỒN TẠI.
    // Kết quả là: Quăng IOException (Stream Closed), Async Thread báo "Lưu file thất bại".
    // ==========================================
    /*
    private String saveFileToDisk(MultipartFile file) {
        try {
            try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            File dir = new File(uploadDir);
            if (!dir.exists()) dir.mkdirs();
            String filename = UUID.randomUUID().toString() + "_" + file.getOriginalFilename();
            File dest = new File(dir.getAbsolutePath() + File.separator + filename);
            file.transferTo(dest);
            return "/uploads/" + filename;
        } catch (IOException e) {
            log.error("Lưu file thất bại: {}", e.getMessage());
            throw new RuntimeException("Lưu file thất bại", e);
        }
    }

    /**
     * ⚠ KHÔNG DÙNG CHO PRODUCTION (NOT PRODUCTION SAFE)
     * Tại sao async gây ra rủi ro không nhất quán dữ liệu (ở bản demo này):
     * - Nếu background thread thất bại (VD: Đầy ổ đĩa ổ, lỗi DB timeout), bài viết đã được tạo ra sẽ không có ảnh.
     * - Rủi ro thắt cổ chai ở Async Thread Pool: Cấu hình giới hạn chỉ 10 thread. Nếu 500 users upload ảnh,
     *   queue capacity đầy, nó sẽ throw TaskRejectedException làm crash tiến trình.
     */
    /*
    @Async("taskExecutor")
    public void uploadImagesAsync(UUID postId, List<MultipartFile> files) {
        String threadName = Thread.currentThread().getName();
        try {
            Post post = postRepository.findById(postId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bài viết: " + postId));
            for (MultipartFile file : files) {
                if (!file.isEmpty()) {
                    String fileUrl = saveFileToDisk(file);
                    Image image = Image.builder().url(fileUrl).post(post).build();
                    imageRepository.save(image);
                }
            }
        } catch (Exception ex) {
            CsvLogger.logError("background", threadName, ex.getClass().getSimpleName(), ex.getMessage());
        }
    }
    */

    private String saveFileToDisk(ImageUploadDto fileDto) {
        try {
            // Giả lập Disk IO chậm.
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            File dir = new File(uploadDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String filename = UUID.randomUUID().toString() + "_" + fileDto.getFilename();
            File dest = new File(dir.getAbsolutePath() + File.separator + filename);
            java.nio.file.Files.write(dest.toPath(), fileDto.getData()); // Ghi mảng byte vật lý xuống ổ đĩa
            return "/uploads/" + filename;
        } catch (IOException e) {
            log.error("Lưu file thất bại: {}", e.getMessage());
            throw new RuntimeException("Lưu file thất bại", e);
        }
    }

    // ==========================================
    // 🟢 VERSION 2 (FIXED): XUẤT MẢNG BYTE[] ĐỂ TÁCH BIỆT KHỎI HTTP REQUEST
    // Kỹ thuật SRE: Ta rút toàn bộ Mảng vật lý (Byte Array) ra khỏi MultipartFile ngay từ trên Controller.
    // Gói nó vào DTO để truyền xuống Async. Dù HTTP Request có chết và Tomcat có dọn dẹp,
    // Background Thread vẫn đang cầm nguyên chuỗi Byte trên tay (RAM) để ghi từ từ!
    // ==========================================
    @Async("taskExecutor")
    public void uploadImagesAsync(UUID postId, List<ImageUploadDto> files) {
        String threadName = Thread.currentThread().getName();
        log.info("START ASYNC endpoint=background thread={} postId={} files={}", threadName, postId, files.size());
        long start = System.currentTimeMillis();

        try {
            Post post = postRepository.findById(postId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bài viết: " + postId));

            for (com.vanphuc.dto.ImageUploadDto fileDto : files) {
                if (fileDto.getData() != null && fileDto.getData().length > 0) {
                    String fileUrl = saveFileToDisk(fileDto);

                    Image image = Image.builder()
                            .url(fileUrl)
                            .post(post)
                            .build();

                    imageRepository.save(image);
                }
            }
            
            long duration = System.currentTimeMillis() - start;
            log.info("END ASYNC endpoint=background thread={} duration={}ms", threadName, duration);
        } catch (Exception ex) {
            log.error("FAIL ASYNC endpoint=background error={}", ex.getMessage());
            CsvLogger.logError("background", threadName, ex.getClass().getSimpleName(), ex.getMessage());
        }
    }
}
