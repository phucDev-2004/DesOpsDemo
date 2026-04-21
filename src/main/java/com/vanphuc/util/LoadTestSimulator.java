package com.vanphuc.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
public class LoadTestSimulator {

    private static final String BASE_URL = "http://localhost:8081/posts";
    private static final int USERS = 2000;
    private static final int POSTS_PER_USER_MIN = 5;
    private static final int POSTS_PER_USER_MAX = 10;
    
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Random random = new Random();

    public static void main(String[] args) {
        LoadTestSimulator simulator = new LoadTestSimulator();
        log.info("========== BẮT ĐẦU LOAD TEST: SYNC API (BAD PRACTICE) ==========");
        simulator.runTest("SYNC");
        
        try {
            log.info("Đang làm mát hệ thống trong 10 giây...");
            Thread.sleep(10000);
        } catch (InterruptedException e) {}

        log.info("========== BẮT ĐẦU LOAD TEST: ASYNC API (CẢI THIỆN UX) ==========");
        simulator.runTest("ASYNC");
    }

    private void runTest(String mode) {
        ExecutorService executor = Executors.newFixedThreadPool(150); // Giả lập giới hạn lượng request độc lập đồng thời gửi tới
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        ConcurrentLinkedQueue<Long> responseTimes = new ConcurrentLinkedQueue<>();
        
        int totalExpectedRequests = 0;
        List<Runnable> tasks = new ArrayList<>();

        for (int i = 0; i < USERS; i++) {
            int postsCount = random.nextInt(POSTS_PER_USER_MAX - POSTS_PER_USER_MIN + 1) + POSTS_PER_USER_MIN;
            totalExpectedRequests += postsCount;

            for (int j = 0; j < postsCount; j++) {
                tasks.add(() -> {
                    Instant start = Instant.now();
                    boolean success = false;
                    try {
                        // Giả lập độ trễ mạng ngẫu nhiên
                        Thread.sleep(random.nextInt(100));
                        
                        if ("SYNC".equals(mode)) {
                            success = callSyncApi();
                        } else {
                            success = callAsyncApi();
                        }
                    } catch (Exception e) {
                        // Lỗi kỳ vọng do thắt cổ chai hiệu suất (bottleneck)
                    } finally {
                        long duration = Duration.between(start, Instant.now()).toMillis();
                        responseTimes.add(duration);
                        if (success) {
                            successCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                        }
                    }
                });
            }
        }
        
        log.info("Chế độ: {} - Đang bắn {} requests...", mode, tasks.size());
        Instant globalStart = Instant.now();

        tasks.forEach(executor::submit);

        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        long globalDuration = Duration.between(globalStart, Instant.now()).toMillis();
        calculateAndPrintMetrics(mode, tasks.size(), successCount.get(), failCount.get(), responseTimes, globalDuration);
    }

    private boolean callSyncApi() throws Exception {
        // HttpHeaders headers = new HttpHeaders();
        // headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        // MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        // Map<String, String> postJson = Map.of("title", "Sync Post", "content", "Load Test Content");
        // body.add("post", objectMapper.writeValueAsString(postJson));

        // int fileCount = random.nextInt(3) + 1; // 1-3 files
        // for(int i=0; i<fileCount; i++) {
        //     body.add("files", new ByteArrayResource("dummy_image_data".getBytes()) {
        //         @Override
        //         public String getFilename() {
        //             return "file_" + UUID.randomUUID() + ".jpg";
        //         }
        //     });
        // }

        // HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
        // ResponseEntity<String> response = restTemplate.postForEntity(BASE_URL + "/full", requestEntity, String.class);
        
        // return response.getStatusCode().is2xxSuccessful();
        return true;
    }

    private boolean callAsyncApi() throws Exception {
        // STEP 1: POST /posts
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> postJson = Map.of("title", "Async Post", "content", "Load Test Content");
        
        HttpEntity<Map<String, String>> step1Request = new HttpEntity<>(postJson, jsonHeaders);
        ResponseEntity<Map> step1Response = restTemplate.postForEntity(BASE_URL, step1Request, Map.class);
        
        if (!step1Response.getStatusCode().is2xxSuccessful() || step1Response.getBody() == null) {
            return false;
        }
        
        String postId = step1Response.getBody().get("postId").toString();

        // STEP 2: POST /posts/{id}/images
        HttpHeaders multiHeaders = new HttpHeaders();
        multiHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> step2Body = new LinkedMultiValueMap<>();
        
        int fileCount = random.nextInt(3) + 1;
        for(int i=0; i<fileCount; i++) {
            step2Body.add("files", new ByteArrayResource("dummy_image_data".getBytes()) {
                @Override
                public String getFilename() {
                    return "file_" + UUID.randomUUID() + ".jpg";
                }
            });
        }
        
        HttpEntity<MultiValueMap<String, Object>> step2Request = new HttpEntity<>(step2Body, multiHeaders);
        ResponseEntity<String> step2Response = restTemplate.postForEntity(BASE_URL + "/" + postId + "/images", step2Request, String.class);
        
        return step2Response.getStatusCode().is2xxSuccessful();
    }

    private void calculateAndPrintMetrics(String mode, int totalReqs, int success, int fail, ConcurrentLinkedQueue<Long> times, long totalMs) {
        List<Long> sortedTimes = times.stream().sorted().collect(Collectors.toList());

        long sum = sortedTimes.stream().mapToLong(Long::longValue).sum();
        long avg = sortedTimes.isEmpty() ? 0 : sum / sortedTimes.size();
        long p50 = sortedTimes.isEmpty() ? 0 : sortedTimes.get((int) (sortedTimes.size() * 0.50));
        long p95 = sortedTimes.isEmpty() ? 0 : sortedTimes.get((int) (sortedTimes.size() * 0.95));
        long p99 = sortedTimes.isEmpty() ? 0 : sortedTimes.get((int) (sortedTimes.size() * 0.99));

        System.out.println("==================================================");
        System.out.println("BÁO CÁO KẾT QUẢ: " + mode);
        System.out.println("==================================================");
        System.out.println("Tổng Requests:   " + totalReqs);
        System.out.println("T.công (Success):" + success);
        System.out.println("Th.bại (Fail):   " + fail);
        System.out.println("Tổng TG (Global):" + totalMs + " ms");
        System.out.println("TB Latency (Avg):" + avg + " ms");
        System.out.println("p50 Latency:     " + p50 + " ms");
        System.out.println("p95 Latency:     " + p95 + " ms");
        System.out.println("p99 Latency:     " + p99 + " ms");
        System.out.println("==================================================\n");
    }
}
