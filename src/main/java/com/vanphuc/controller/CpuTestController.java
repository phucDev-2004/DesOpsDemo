package com.vanphuc.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/cpu")
@Slf4j
public class CpuTestController {

    /**
     * 🧨 API ĐỘC HẠI MÔ PHỎNG CPU LÊN 90%
     * API này thực hiện băm Hash (SHA-256) liên tục hàng triệu lần. 
     * Đây là tác vụ ngốn CPU (CPU-bound) kinh điển.
     */
    @GetMapping("/spike")
    public ResponseEntity<String> spikeCpu(@RequestParam(defaultValue = "5000000") int iterations) {
        log.warn("BẮT ĐẦU ĐỐT CPU với {} vòng lặp Hash...", iterations);
        long start = System.currentTimeMillis();
        
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String data = UUID.randomUUID().toString();
            
            // Ép CPU Core chạy toàn lực tính toán thuật toán mã hóa
            for (int i = 0; i < iterations; i++) {
                byte[] hash = digest.digest(data.getBytes());
                data = new String(hash); // Tạo rác nhẹ nhưng chủ yếu tốn CPU tính Hash
            }
        } catch (NoSuchAlgorithmException e) {
            return ResponseEntity.internalServerError().body("Lỗi Hash");
        }

        long duration = System.currentTimeMillis() - start;
        log.warn("KẾT THÚC ĐỐT CPU, mất {} ms", duration);
        return ResponseEntity.ok("Cày nát CPU xong trong " + duration + " ms");
    }

    // Biến volatile này ngặn chặn (Prevent) trình biên dịch JIT của Java tự động xóa bỏ vòng lặp (Dead Code Elimination)
    public static volatile double blackhole = 0;

    /**
     * 💣 API MULTI-THREAD ĐỐT SẠCH MỌI CORE CỦA CPU LÊN 100%
     */
    @GetMapping("/spike-all-cores")
    public ResponseEntity<String> spikeAllCores() {
        int cores = Runtime.getRuntime().availableProcessors();
        log.error("🔥 BẮT ĐẦU ĐỐT TOÀN BỘ {} CORES CPU Tới 100% !!!", cores);
        
        ExecutorService executor = Executors.newFixedThreadPool(cores);
        
        for (int i = 0; i < cores; i++) {
            executor.submit(() -> {
                double result = 1.1;
                // Vòng lặp tính toán khổng lồ không bị Lock Contention như Math.random()
                while (true) {
                    for (int j = 0; j < 10000; j++) {
                        result = Math.pow(result + java.util.concurrent.ThreadLocalRandom.current().nextDouble(), 1.0000001);
                    }
                    // Gán vào biến volatile để lừa JIT Compiler không được xóa vòng lặp vô nghĩa này
                    blackhole = result; 
                }
            });
        }
        
        return ResponseEntity.ok("Đã đánh sập tất cả " + cores + " Cores.");
    }
}
