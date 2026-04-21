package com.vanphuc.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanphuc.service.ImageService;
import com.vanphuc.service.PostService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PostController.class)
class PostControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PostService postService;

    @MockBean
    private ImageService imageService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testUploadImagesAsync_controllerTrigger() throws Exception {
        UUID postId = UUID.randomUUID();
        MockMultipartFile mockFile = new MockMultipartFile(
                "files",
                "test-async.jpg",
                "image/jpeg",
                "test".getBytes());

        mockMvc.perform(multipart(HttpMethod.POST, "/posts/" + postId + "/images")
                .file(mockFile))
                .andExpect(status().isAccepted());

        // Use Mockito verify() to ensure async method is triggered from Controller
        verify(imageService, times(1)).uploadImagesAsync(eq(postId), any());
    }
}
