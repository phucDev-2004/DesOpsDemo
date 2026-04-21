package com.vanphuc.service;

import com.vanphuc.dto.PostRequestDto;
import com.vanphuc.dto.PostResponseDto;
import com.vanphuc.entity.Image;
import com.vanphuc.entity.Post;
import com.vanphuc.repository.ImageRepository;
import com.vanphuc.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PostService {

    private final PostRepository postRepository;
    private final ImageRepository imageRepository;
    
    @Value("${file.upload-dir:uploads}")
    private String uploadDir;

    private String saveFileToDisk(MultipartFile file) {
        try {
            // ⚠ NÚT THẮT CỔ CHAI HIỆU SUẤT (PERFORMANCE BOTTLENECK) Ở ĐÂY
            // Giả lập Disk IO chậm (vd: xử lý ảnh dung lượng lớn).
            // Trong quá trình Đồng bộ (Sync API), bước này sẽ BLOCK hoàn toàn Request Thread của Tomcat.
            // Khi hệ thống chịu tải cao, điều này sẽ làm cạn kiệt Thread Pool của Tomcat một cách nhanh chóng
            // bởi vì từng thread đều phải đứng chờ "disk IO" hoàn tất mà không làm gì khác.
            try {
                Thread.sleep(500); 
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            File dir = new File(uploadDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String filename = UUID.randomUUID().toString() + "_" + file.getOriginalFilename();
            File dest = new File(dir.getAbsolutePath() + File.separator + filename);
            file.transferTo(dest);
            return "/uploads/" + filename;
        } catch (IOException e) {
            log.error("Lưu file thất bại: {}", e.getMessage());
            throw new RuntimeException("Lưu file thất bại", e);
        }
    }

    @Transactional
    public UUID createPost(PostRequestDto requestDto) {
        Post post = Post.builder()
                .title(requestDto.getTitle())
                .content(requestDto.getContent())
                .build();
        
        post = postRepository.save(post);
        return post.getId();
    }

    // ==========================================
    // 🔴 VERSION 1 (OLD - KÉM TỐI ƯU): Xử lý Tuần tự (Sequential Disk I/O)
    // Vấn đề: Vòng lặp gọi saveFileToDisk() tuần tự. Gửi 3 file thì tốn 3x500ms = 1.5s BLOCK thẳng Request Thread Tomcat.
    // ==========================================
    /*
    public PostResponseDto createPostWithImages(PostRequestDto requestDto, List<MultipartFile> files) {
        Post post = postRepository.save(Post.builder().title(requestDto.getTitle()).content(requestDto.getContent()).build());
        if (files != null && !files.isEmpty()) {
            for (MultipartFile file : files) {
                if (!file.isEmpty()) {
                    String fileUrl = saveFileToDisk(file);
                    Image image = Image.builder().url(fileUrl).post(post).build();
                    imageRepository.save(image);
                    post.addImage(image);
                }
            }
        }
        return mapToResponseDto(post);
    }
    */

    // ==========================================
    // 🟡 VERSION 2 (FIXED - SONG SONG): Parallel Disk I/O bằng CompletableFuture
    // Giải quyết: Bắn N luồng phụ lưu đĩa ảo cùng lúc. Nhưng lệnh .join() ở cuối hàm chờ đợi luồng phụ
    // tóm lại vẫn BLOCK luồng khởi tạo (Tomcat Request) mất 500ms chờ các luồng kia hoàn tất!
    // ==========================================
    /*
    public PostResponseDto createPostWithImages(PostRequestDto requestDto, List<MultipartFile> files) {
        Post post = postRepository.save(Post.builder().title(requestDto.getTitle()).content(requestDto.getContent()).build());
        final Post finalPost = post;
        List<CompletableFuture<Image>> futures = files.stream()
            .map(file -> CompletableFuture.supplyAsync(() -> Image.builder().url(saveFileToDisk(file)).post(finalPost).build()))
            .collect(Collectors.toList());
        List<Image> savedImages = futures.stream().map(CompletableFuture::join).collect(Collectors.toList());
        imageRepository.saveAll(savedImages);
        savedImages.forEach(post::addImage);
        return mapToResponseDto(post);
    }
    */

    // ==========================================
    // 🟢 VERSION 3 (TRUE NON-BLOCKING - LEVEL SENIOR PRO): Servlet 3.0 Async Processing
    // Vấn đề bóc trần: Vẫn còn gọi .join() ở VERSION 2 nghĩa là Tomcat Thread vẫn phải đứng chơi chờ 500ms.
    // Hướng giải quyết: Trả hẳn CompletableFuture về cho Controller. Spring MVC sẽ tự động Tạm Đình Chỉ (Suspend) 
    // HTTP Request và trả tự do cho Tomcat Thread để đi phục vụ user khác. Khi I/O tải xong, nó tự bắn Reponse về Client!
    // ==========================================
    public CompletableFuture<PostResponseDto> createPostWithImages(PostRequestDto requestDto, List<MultipartFile> files) {
        // 1. Lưu Text Post chạy trên Tomcat Thread một cách cực kì lẹ (Microsecond)
        Post post = Post.builder()
                .title(requestDto.getTitle())
                .content(requestDto.getContent())
                .build();
        post = postRepository.save(post);
        final Post finalPost = post;

        if (files == null || files.isEmpty()) {
            return CompletableFuture.completedFuture(mapToResponseDto(finalPost));
        }

        // 2. Chuyển quyền xử lý I/O nặng nề xuống nhánh Worker (ForkJoinPool)
        List<CompletableFuture<Image>> futures = files.stream()
                .filter(file -> !file.isEmpty())
                .map(file -> CompletableFuture.supplyAsync(() -> {
                    String fileUrl = saveFileToDisk(file);
                    return Image.builder()
                            .url(fileUrl)
                            .post(finalPost)
                            .build();
                }))
                .collect(Collectors.toList());

        // 3. Ráp nối (Chaining) các tác vụ. Trả về HỘP THÔNG ĐIỆP TƯƠNG LAI ngay lập tức! (Không có lệnh .join() nào chặn Tomcat Thread)
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
        );

        return allFutures.thenApply(v -> {
            // Khi toàn bộ worker lưu xong Disk IO tự động gọi đoạn Callback này
            List<Image> savedImages = futures.stream()
                    .map(CompletableFuture::join) // Lúc này join() vô hại vì các I/O đã chạy xong
                    .collect(Collectors.toList());

            imageRepository.saveAll(savedImages);
            savedImages.forEach(finalPost::addImage);
            return mapToResponseDto(finalPost);
        });
    }
    
    public PostResponseDto getPost(UUID id) {
        Post post = postRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bài viết"));
        return mapToResponseDto(post);
    }

    private PostResponseDto mapToResponseDto(Post post) {
        List<String> imageUrls = post.getImages().stream()
                .map(Image::getUrl)
                .collect(Collectors.toList());

        return PostResponseDto.builder()
                .id(post.getId())
                .title(post.getTitle())
                .content(post.getContent())
                .createdAt(post.getCreatedAt())
                .imageUrls(imageUrls)
                .build();
    }
}
