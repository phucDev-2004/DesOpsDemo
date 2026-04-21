package com.vanphuc.service;

import com.vanphuc.dto.ImageUploadDto;
import com.vanphuc.entity.Image;
import com.vanphuc.entity.Post;
import com.vanphuc.repository.ImageRepository;
import com.vanphuc.repository.PostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageServiceTest {

    @Mock
    private ImageRepository imageRepository;

    @Mock
    private PostRepository postRepository;

    @InjectMocks
    private ImageService imageService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(imageService, "uploadDir", "test-uploads");
    }

    @Test
    void testAsyncUploadTriggered() {
        // Arrange
        UUID postId = UUID.randomUUID();
        Post mockPost = Post.builder().id(postId).build();

        when(postRepository.findById(postId)).thenReturn(Optional.of(mockPost));

        // ✅ ĐÚNG KIỂU: Dùng ImageUploadDto thay vì MultipartFile
        // Lý do: ImageService.uploadImagesAsync đã được refactor để nhận byte[] an toàn
        // thay vì MultipartFile (sẽ bị Tomcat xóa khi HTTP Request kết thúc).
        ImageUploadDto dto = new ImageUploadDto("test-async.jpg", "test".getBytes());
        List<ImageUploadDto> files = List.of(dto);

        // Act
        imageService.uploadImagesAsync(postId, files);

        // Assert
        verify(postRepository, times(1)).findById(postId);
        verify(imageRepository, times(1)).save(any(Image.class));
    }
}

