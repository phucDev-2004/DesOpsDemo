package com.vanphuc.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanphuc.dto.PostRequestDto;
import com.vanphuc.dto.PostResponseDto;
import com.vanphuc.service.ImageService;
import com.vanphuc.service.PostService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import java.util.UUID;

@RestController
@RequestMapping("/posts")
@RequiredArgsConstructor
@Slf4j
public class PostController {

    private final PostService postService;
    private final ImageService imageService;
    private final ObjectMapper objectMapper;

    /**
     * CASE 1 (ĐỒNG BỘ - SYNC - Phiên bản đơn giản nhưng BAD PRACTICE)
     * POST /posts/full
     */
    @PostMapping(value = "/full", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public java.util.concurrent.CompletableFuture<ResponseEntity<PostResponseDto>> createPostSync(
            @RequestPart("post") String postJson,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) throws JsonProcessingException {
        
        long start = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();
        int fileCount = files != null ? files.size() : 0;
        
        log.info("START endpoint=/posts/full (Servlet Async) Tomcat_thread={} files={}", threadName, fileCount);
        
        PostRequestDto postRequest = objectMapper.readValue(postJson, PostRequestDto.class);
        
        // 🚀 BÍ MẬT SCALING NẰM Ở ĐÂY: Trả về CompletableFuture giúp Spring giải phóng hoàn toàn Tomcat_thread.
        // Thread Http lập tức quay về Pool để đón 20k khác. I/O lúc này được ngầm xử lý ở ThreadPool phụ.
        return postService.createPostWithImages(postRequest, files)
                .thenApply(response -> {
                    // Khi I/O tải xong, đoạn code Callback này sẽ báo Spring đẩy response về Client.
                    long duration = System.currentTimeMillis() - start;
                    log.info("END endpoint=/posts/full duration={}ms callback_thread={}", duration, Thread.currentThread().getName());
                    return ResponseEntity.status(HttpStatus.CREATED).body(response);
                });
    }

    /**
     * CASE 2 (BẤT ĐỒNG BỘ - ASYNC - Cách làm thực tế để cải thiện UX)
     * BƯỚC 1: POST /posts
     */
    @PostMapping
    public ResponseEntity<Map<String, UUID>> createPostAsyncStep1(@RequestBody PostRequestDto requestDto) {
        long start = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();
        log.info("START endpoint=/posts thread={}", threadName);
        
        UUID postId = postService.createPost(requestDto);
        
        long duration = System.currentTimeMillis() - start;
        log.info("END endpoint=/posts duration={}ms thread={}", duration, threadName);
        
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("postId", postId));
    }

    /**
     * CASE 2 (BẤT ĐỒNG BỘ - ASYNC - Cách làm thực tế để cải thiện UX)
     * BƯỚC 2: POST /posts/{id}/images
     */
    @PostMapping(value = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> uploadImagesAsyncStep2(
            @PathVariable("id") UUID postId,
            @RequestPart("files") List<MultipartFile> files) {
        
        long start = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();
        int fileCount = files != null ? files.size() : 0;
        log.info("START endpoint=/posts/{}/images thread={} files={}", postId, threadName, fileCount);
            
        // ==========================================
        // KHAI THÁC LỖI TẠI ĐÂY: Trích xuất Byte Array khỏi MultipartFile trước khi trả về Response.
        // MultipartFile là "đồ đi mượn" của luồng HTTP (Tomcat HTTP Thread). Vừa trả mượn phát là nó bị thu hồi.
        // Vì vậy ta ép dữ liệu thật sang DTO do ta tự sở hữu (RAM) và ném cục DTO này cho Async.
        // ==========================================
        List<com.vanphuc.dto.ImageUploadDto> fileData = null;
        if (files != null) {
            fileData = files.stream().map(file -> {
                try {
                    return new com.vanphuc.dto.ImageUploadDto(file.getOriginalFilename(), file.getBytes());
                } catch (java.io.IOException e) {
                    throw new RuntimeException("Lỗi đọc byte file", e);
                }
            }).collect(java.util.stream.Collectors.toList());
        }

        // Kích hoạt tiến trình chạy ngầm (Async background task) với DTO an toàn
        if (fileData != null && !fileData.isEmpty()) {
            imageService.uploadImagesAsync(postId, fileData);
        }
        
        long duration = System.currentTimeMillis() - start;
        log.info("END endpoint=/posts/{}/images duration={}ms thread={} (Controller Trả về ngay lập tức!)", postId, duration, threadName);
        
        return ResponseEntity.accepted().body(Map.of("message", "Tiến trình upload ảnh đang chạy ngầm an toàn"));
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<PostResponseDto> getPost(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(postService.getPost(id));
    }
}
