package com.vanphuc.util;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
public class CsvLogger {

    private static final String CSV_FILE = "errors.csv";
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static boolean initialized = false;

    public static synchronized void logError(String endpoint, String threadName, String errorType, String message) {
        try {
            if (!initialized) {
                // Chỉ ghi Header 1 lần nếu file là mới
                // Trên thực tế cần check file có tồn tại không, ở đây dùng tạm cho demo:
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(CSV_FILE, true))) {
                    // Check nhanh, ghi nối file
                    initialized = true;
                }
            }
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(CSV_FILE, true))) {
                String timestamp = LocalDateTime.now().format(formatter);
                // Escape text CSV an toàn
                String safeMessage = message != null ? message.replace("\"", "\"\"").replace("\n", " ") : "null";
                
                String line = String.format("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"\n",
                        timestamp, endpoint, threadName, errorType, safeMessage);
                
                writer.write(line);
                writer.flush();
            }
        } catch (IOException e) {
            log.error("Ghi log CSV thất bại: {}", e.getMessage());
        }
    }
}
