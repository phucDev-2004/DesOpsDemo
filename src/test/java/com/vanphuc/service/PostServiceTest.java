package com.vanphuc.service;

import com.vanphuc.dto.PostRequestDto;
import com.vanphuc.dto.PostResponseDto;
import com.vanphuc.entity.Post;
import com.vanphuc.repository.ImageRepository;
import com.vanphuc.repository.PostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostServiceTest {

        @Mock
        private PostRepository postRepository;

        @Mock
        private ImageRepository imageRepository;

        @InjectMocks
        private PostService postService;

        @BeforeEach
        void setUp() {
                ReflectionTestUtils.setField(postService, "uploadDir", "test-uploads");
        }

        @Test
        void testCreatePost_success() {
                // Arrange
                PostRequestDto request = PostRequestDto.builder()
                                .title("Hello")
                                .content("World")
                                .build();

                Post savedPost = Post.builder()
                                .id(UUID.randomUUID())
                                .title("Hello")
                                .content("World")
                                .build();

                when(postRepository.save(any(Post.class))).thenReturn(savedPost);

                // Act
                UUID resultId = postService.createPost(request);

                // Assert
                assertNotNull(resultId);
                assertEquals(savedPost.getId(), resultId);
                verify(postRepository, times(1)).save(any(Post.class));
        }

        @Test
        void testCreatePostWithImages_success() throws Exception {
                // Arrange
                PostRequestDto request = PostRequestDto.builder()
                                .title("Title")
                                .content("Content")
                                .build();

                Post savedPost = Post.builder()
                                .id(UUID.randomUUID())
                                .title("Title")
                                .content("Content")
                                .build();

                when(postRepository.save(any(Post.class))).thenReturn(savedPost);

                // ✅ saveAll() vì PostService V3 (CompletableFuture) dùng
                // imageRepository.saveAll() chứ không dùng save() lẻ
                when(imageRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

                MockMultipartFile mockFile = new MockMultipartFile(
                                "file",
                                "test.jpg",
                                "image/jpeg",
                                "test image content".getBytes());
                List<MultipartFile> files = List.of(mockFile);

                // Act
                // ✅ createPostWithImages() trả về CompletableFuture<PostResponseDto> sau khi
                // refactor sang Non-Blocking.
                // Dùng .get() để chờ kết quả hoàn thành (trong test context không có HTTP
                // thread pool nên block là chấp nhận được)
                CompletableFuture<PostResponseDto> future = postService.createPostWithImages(request, files);
                PostResponseDto response = future.get(); // Chờ I/O async hoàn thành rồi lấy giá trị ra

                // Assert
                assertNotNull(response);
                assertEquals(savedPost.getId(), response.getId());
                assertEquals("Title", response.getTitle());

                verify(postRepository, times(1)).save(any(Post.class));
                verify(imageRepository, times(1)).saveAll(any());
        }
}
