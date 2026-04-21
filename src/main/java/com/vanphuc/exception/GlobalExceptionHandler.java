package com.vanphuc.exception;

import com.vanphuc.util.CsvLogger;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleAllExceptions(Exception ex, HttpServletRequest request) {
        String endpoint = request.getRequestURI();
        String threadName = Thread.currentThread().getName();
        
        log.error("FAIL endpoint={} error={}", endpoint, ex.getMessage());
        
        // Lưu vào CSV
        CsvLogger.logError(endpoint, threadName, ex.getClass().getSimpleName(), ex.getMessage());
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Lỗi Server Nội Bộ: " + ex.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> handleDbExceptions(DataAccessException ex, HttpServletRequest request) {
        String endpoint = request.getRequestURI();
        String threadName = Thread.currentThread().getName();

        log.error("FAIL endpoint={} error=DB_EXCEPTION message={}", endpoint, ex.getMessage());
        
        // Lưu vào CSV
        CsvLogger.logError(endpoint, threadName, "DataAccessException", "Timeout DB hoặc cạn kiệt Connection Pool: " + ex.getMessage());
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Đã xảy ra lỗi Cơ sở dữ liệu"));
    }
}
