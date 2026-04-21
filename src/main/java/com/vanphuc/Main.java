package com.vanphuc;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

@SpringBootApplication
public class Main extends SpringBootServletInitializer {

    // Thêm đoạn này vào
    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(Main.class);
    }

    public static void main(String[] args) {
        try{
            Dotenv dotenv = Dotenv.configure()
                    .ignoreIfMissing()
                    .systemProperties()
                    .load();
        } catch (Exception e) {
            System.err.println("Cảnh báo: không thể tải file .env: " + e.getMessage());
        }
        SpringApplication.run(Main.class, args);
    }
}