
# ================================================
# STAGE 1: Build — cần Maven + JDK để compile
# ================================================
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

# Copy pom.xml trước để cache dependency (quan trọng cho tốc độ build)
# Chỉ khi pom.xml thay đổi mới phải download dependency lại
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code sau (thay đổi thường xuyên → để cuối để tận dụng cache)
COPY src ./src
RUN mvn package -DskipTests

# ================================================
# STAGE 2: Run — chỉ cần JRE, không cần Maven
# ================================================
FROM tomcat:10-jdk17

RUN rm -rf /usr/local/tomcat/webapps/*

# Copy chỉ file WAR từ stage builder
COPY --from=builder /app/target/*.war /usr/local/tomcat/webapps/app.war

EXPOSE 8080
