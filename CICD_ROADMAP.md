# 🚀 Lộ Trình CI/CD Thực Chiến — DesOpsDemo

> **Mục tiêu:** Từ một Spring Boot app chạy tay → tự động hóa hoàn toàn: test, build, đóng gói Docker, deploy lên server — theo đúng chuẩn DevOps thực tế doanh nghiệp.

---

## 📌 Toàn Cảnh Lộ Trình

```
┌─────────────────────────────────────────────────────────────────────┐
│                      CI/CD ROADMAP OVERVIEW                         │
├──────────────┬──────────────────┬──────────────────┬────────────────┤
│  Giai đoạn 1 │   Giai đoạn 2    │   Giai đoạn 3    │  Giai đoạn 4  │
│  Dockerize   │  GitHub Actions  │    Jenkins       │   Sự cố thực  │
│              │      CI/CD       │   Self-hosted    │   tế & Fix    │
├──────────────┼──────────────────┼──────────────────┼────────────────┤
│ • Dockerfile │ • CI: test+build │ • Dựng Jenkins   │ • 5 sự cố thật│
│ • Multi-stage│ • CD: push image │ • Jenkinsfile    │ • Cách tái hiện│
│ • Local test │ • Secrets mgmt   │ • Credentials    │ • Cách fix     │
│ • Compose v2 │ • SSH deploy     │ • Webhook        │ • Bài học rút  │
└──────────────┴──────────────────┴──────────────────┴────────────────┘
```

**Thứ tự học:** Giai đoạn 1 → 2 → 3 → 4. Không bỏ bước nào.

---

## ✅ CHECKLIST TỔNG (Đánh dấu khi hoàn thành)

```
GIAI ĐOẠN 1 — DOCKERIZE
[ ] 1.1  Viết Dockerfile (multi-stage build)
[ ] 1.2  Build image local: docker build -t desops-demo .
[ ] 1.3  Test chạy image: docker run --env-file .env desops-demo
[ ] 1.4  Viết docker-compose.yml v2 (có cả app + postgres)
[ ] 1.5  Test full stack: docker-compose up -d → gọi API được

GIAI ĐOẠN 2 — GITHUB ACTIONS
[ ] 2.1  Tạo .github/workflows/ci.yml (test + build WAR)
[ ] 2.2  Push code → pipeline chạy xanh lần đầu
[ ] 2.3  Thêm job build Docker image trong pipeline
[ ] 2.4  Tạo Docker Hub account + tạo Access Token
[ ] 2.5  Thêm Secrets vào GitHub repo (DOCKERHUB_USERNAME, TOKEN)
[ ] 2.6  Tạo .github/workflows/cd.yml (push image lên Hub)
[ ] 2.7  Pipeline hoàn chỉnh: CI → CD → Deploy

GIAI ĐOẠN 3 — JENKINS
[ ] 3.1  Dựng Jenkins bằng Docker local
[ ] 3.2  Cài plugin cần thiết (Git, Docker, Pipeline, SSH)
[ ] 3.3  Viết Jenkinsfile trong repo
[ ] 3.4  Tạo Credentials trong Jenkins
[ ] 3.5  Kết nối repo GitHub với Jenkins (Webhook)
[ ] 3.6  Chạy pipeline Jenkins lần đầu thành công
[ ] 3.7  So sánh GitHub Actions vs Jenkins: ưu/nhược

GIAI ĐOẠN 4 — SỰ CỐ THỰC TẾ
[ ] 4.1  Tái hiện và fix: Deploy lỗi do thiếu biến env
[ ] 4.2  Tái hiện và fix: Server chạy image cũ do Docker cache
[ ] 4.3  Tái hiện và fix: Database schema conflict
[ ] 4.4  Tái hiện và fix: Secret lộ trong log
[ ] 4.5  Tái hiện và fix: Pipeline bị treo (hung build)
[ ] 4.6  Thêm Health Check + Rollback tự động vào pipeline
```

---

## 📦 GIAI ĐOẠN 1 — DOCKERIZE ỨNG DỤNG

### 1.1 — Viết Dockerfile (Multi-stage Build)

> **Tại sao multi-stage?** Stage 1 cần JDK + Maven để compile (~500MB). Stage 2 chỉ cần JRE để chạy (~180MB). Tách ra → image production nhỏ hơn 3 lần.

**Tạo file `Dockerfile` trong thư mục gốc:**

```dockerfile
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
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Tạo user non-root (bảo mật: không chạy app với quyền root)
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Copy chỉ file WAR từ stage builder
COPY --from=builder /app/target/*.war app.war

EXPOSE 8081

# Tối ưu JVM cho container (không dùng heap của cả server)
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.war"]
```

**Test build local:**
```bash
# Build image
docker build -t desops-demo:local .

# Chạy thử (dùng file .env đã có)
docker run --rm \
  --env-file .env \
  -p 8081:8081 \
  desops-demo:local

# Kiểm tra: http://localhost:8081/dashboard.html
```

---

### 1.2 — Cập nhật docker-compose.yml (Thêm service app)

```yaml
version: '3.8'

services:

  # ==============================
  # DATABASE
  # ==============================
  postgres:
    image: postgres:15-alpine
    container_name: postgres_db_devops
    ports:
      - "5432:5432"
    environment:
      POSTGRES_USER: ${DB_USERNAME:-postgres}
      POSTGRES_PASSWORD: ${DB_PASSWORD:-postgres}
      POSTGRES_DB: social_db
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USERNAME:-postgres}"]
      interval: 10s
      timeout: 5s
      retries: 5
    restart: unless-stopped

  # ==============================
  # APPLICATION
  # ==============================
  app:
    image: ${DOCKER_IMAGE:-desops-demo:local}
    container_name: desops_app
    ports:
      - "${SERVER_PORT:-8081}:8081"
    env_file:
      - .env
    environment:
      # Override DB_URL để app kết nối tới container postgres (không phải localhost)
      DB_URL: jdbc:postgresql://postgres:5432/social_db
    depends_on:
      postgres:
        condition: service_healthy
    restart: unless-stopped
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8081/actuator/health || exit 1"]
      interval: 30s
      timeout: 10s
      retries: 3

volumes:
  postgres_data:
```

**Test full stack:**
```bash
docker-compose up -d
docker-compose logs -f app
curl http://localhost:8081/actuator/health
```

---

## ⚙️ GIAI ĐOẠN 2 — GITHUB ACTIONS CI/CD

### 2.1 — Pipeline CI: Test + Build

**Tạo file `.github/workflows/ci.yml`:**

```yaml
name: 🧪 CI — Test & Build

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main ]

jobs:
  test-and-build:
    name: Test + Build WAR
    runs-on: ubuntu-latest
    timeout-minutes: 20

    services:
      postgres:
        image: postgres:15-alpine
        env:
          POSTGRES_USER: postgres
          POSTGRES_PASSWORD: postgres
          POSTGRES_DB: social_db
        ports:
          - 5432:5432
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5

    steps:
      - name: 📥 Checkout code
        uses: actions/checkout@v4

      - name: ☕ Cài Java 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: maven

      - name: 🧪 Chạy Unit Tests
        env:
          DB_URL: jdbc:postgresql://localhost:5432/social_db
          DB_USERNAME: postgres
          DB_PASSWORD: postgres
        run: mvn test

      - name: 📊 Upload Test Report
        uses: actions/upload-artifact@v4
        if: always()
        with:
          name: test-report
          path: target/surefire-reports/

      - name: 🏗️ Build WAR
        run: mvn package -DskipTests

      - name: 📦 Lưu WAR artifact
        uses: actions/upload-artifact@v4
        with:
          name: app-war
          path: target/*.war
          retention-days: 7
```

---

### 2.2 — Pipeline CD: Build & Push Docker Image

**Chuẩn bị:**
1. Tạo tài khoản [hub.docker.com](https://hub.docker.com)
2. Tạo Access Token: **Account Settings → Security → New Access Token**
3. Thêm Secrets vào GitHub: `Settings → Secrets → Actions`:
   - `DOCKERHUB_USERNAME`
   - `DOCKERHUB_TOKEN`

**Tạo file `.github/workflows/cd.yml`:**

```yaml
name: 🐳 CD — Build & Push Docker Image

on:
  push:
    branches: [ main ]

env:
  IMAGE_NAME: ${{ secrets.DOCKERHUB_USERNAME }}/desops-demo

jobs:
  docker-build-push:
    name: Build & Push to Docker Hub
    runs-on: ubuntu-latest
    timeout-minutes: 30

    steps:
      - uses: actions/checkout@v4

      - uses: docker/login-action@v3
        with:
          username: ${{ secrets.DOCKERHUB_USERNAME }}
          password: ${{ secrets.DOCKERHUB_TOKEN }}

      - name: 🏷️ Tạo tags
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ${{ env.IMAGE_NAME }}
          tags: |
            type=sha,prefix=,format=short
            type=raw,value=latest,enable=${{ github.ref == 'refs/heads/main' }}

      - uses: docker/build-push-action@v5
        with:
          context: .
          push: true
          tags: ${{ steps.meta.outputs.tags }}
          cache-from: type=registry,ref=${{ env.IMAGE_NAME }}:latest
          cache-to: type=inline
```

---

### 2.3 — Pipeline Hoàn Chỉnh (CI → CD → Deploy)

**Tạo file `.github/workflows/pipeline.yml`:**

```yaml
name: 🚀 Full Pipeline — Test → Build → Deploy

on:
  push:
    branches: [ main ]

env:
  IMAGE_NAME: ${{ secrets.DOCKERHUB_USERNAME }}/desops-demo

jobs:
  test:
    name: 🧪 Unit Tests
    runs-on: ubuntu-latest
    timeout-minutes: 15
    services:
      postgres:
        image: postgres:15-alpine
        env: { POSTGRES_USER: postgres, POSTGRES_PASSWORD: postgres, POSTGRES_DB: social_db }
        ports: [ "5432:5432" ]
        options: --health-cmd pg_isready --health-interval 10s --health-retries 5
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '17', distribution: 'temurin', cache: maven }
      - run: mvn test
        env:
          DB_URL: jdbc:postgresql://localhost:5432/social_db
          DB_USERNAME: postgres
          DB_PASSWORD: postgres

  build-push:
    name: 🐳 Docker Build & Push
    needs: test
    runs-on: ubuntu-latest
    timeout-minutes: 20
    outputs:
      image-tag: ${{ steps.tag.outputs.sha }}
    steps:
      - uses: actions/checkout@v4
      - name: Lấy short SHA
        id: tag
        run: echo "sha=$(echo $GITHUB_SHA | cut -c1-7)" >> $GITHUB_OUTPUT
      - uses: docker/login-action@v3
        with:
          username: ${{ secrets.DOCKERHUB_USERNAME }}
          password: ${{ secrets.DOCKERHUB_TOKEN }}
      - uses: docker/build-push-action@v5
        with:
          push: true
          tags: |
            ${{ env.IMAGE_NAME }}:${{ steps.tag.outputs.sha }}
            ${{ env.IMAGE_NAME }}:latest
          cache-from: type=registry,ref=${{ env.IMAGE_NAME }}:latest
          cache-to: type=inline

  deploy:
    name: 🚀 Deploy to Server
    needs: build-push
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - uses: appleboy/ssh-action@v1
        with:
          host: ${{ secrets.SERVER_HOST }}
          username: ${{ secrets.SERVER_USER }}
          key: ${{ secrets.SERVER_SSH_KEY }}
          script: |
            set -e
            CURRENT_IMAGE=$(docker inspect desops_app --format='{{.Config.Image}}' 2>/dev/null || echo "none")

            docker pull ${{ env.IMAGE_NAME }}:${{ needs.build-push.outputs.image-tag }}
            export DOCKER_IMAGE=${{ env.IMAGE_NAME }}:${{ needs.build-push.outputs.image-tag }}
            docker-compose down && docker-compose up -d

            sleep 20
            if ! curl --fail --silent http://localhost:8081/actuator/health; then
              echo "❌ Health check failed! Rolling back to $CURRENT_IMAGE..."
              export DOCKER_IMAGE=$CURRENT_IMAGE
              docker-compose up -d
              exit 1
            fi

            docker image prune -f
            echo "✅ Deploy ${{ needs.build-push.outputs.image-tag }} thành công!"
```

**Secrets cần thêm cho deploy:**
```
SERVER_HOST    = IP hoặc domain server
SERVER_USER    = username SSH (ubuntu, ec2-user...)
SERVER_SSH_KEY = Nội dung Private Key (~/.ssh/id_rsa)
```

---

## 🔧 GIAI ĐOẠN 3 — JENKINS SELF-HOSTED

### 3.1 — Dựng Jenkins bằng Docker

```bash
docker volume create jenkins_home

docker run -d \
  --name jenkins \
  --restart unless-stopped \
  -p 8080:8080 \
  -p 50000:50000 \
  -v jenkins_home:/var/jenkins_home \
  -v /var/run/docker.sock:/var/run/docker.sock \
  jenkins/jenkins:lts-jdk17

# Lấy password khởi đầu
docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

Mở `http://localhost:8080` → Điền password → Cài "Install suggested plugins".

**Cài thêm plugin:**
```
Manage Jenkins → Plugins → Available:
  ✓ Docker Pipeline
  ✓ SSH Agent
  ✓ Blue Ocean
```

---

### 3.2 — Viết Jenkinsfile

**Tạo file `Jenkinsfile` trong thư mục gốc:**

```groovy
pipeline {
    agent any

    environment {
        IMAGE_NAME = "vanphuc/desops-demo"
        IMAGE_TAG  = "${env.BUILD_NUMBER}-${env.GIT_COMMIT.take(7)}"
    }

    options {
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10'))
    }

    stages {

        stage('🔍 Checkout') {
            steps {
                checkout scm
                echo "Branch: ${env.BRANCH_NAME} | Build #${env.BUILD_NUMBER}"
            }
        }

        stage('🧪 Unit Tests') {
            steps {
                withCredentials([string(credentialsId: 'db-password', variable: 'DB_PASS')]) {
                    sh '''
                        export DB_URL=jdbc:postgresql://localhost:5432/social_db
                        export DB_USERNAME=postgres
                        export DB_PASSWORD=$DB_PASS
                        mvn test -B
                    '''
                }
            }
            post {
                always { junit 'target/surefire-reports/*.xml' }
                failure { echo '❌ Tests thất bại — pipeline dừng!' }
            }
        }

        stage('🏗️ Build') {
            steps {
                sh 'mvn package -DskipTests -B'
                archiveArtifacts artifacts: 'target/*.war', fingerprint: true
            }
        }

        stage('🐳 Docker Build & Push') {
            when { branch 'main' }
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'dockerhub-creds',
                    usernameVariable: 'DOCKER_USER',
                    passwordVariable: 'DOCKER_PASS'
                )]) {
                    sh '''
                        docker build -t ${IMAGE_NAME}:${IMAGE_TAG} .
                        docker tag ${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_NAME}:latest
                        echo $DOCKER_PASS | docker login -u $DOCKER_USER --password-stdin
                        docker push ${IMAGE_NAME}:${IMAGE_TAG}
                        docker push ${IMAGE_NAME}:latest
                        docker rmi ${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_NAME}:latest || true
                    '''
                }
            }
        }

        stage('🚀 Deploy') {
            when { branch 'main' }
            steps {
                sshagent(credentials: ['server-ssh-key']) {
                    sh '''
                        ssh -o StrictHostKeyChecking=no ${SERVER_USER}@${SERVER_HOST} "
                            set -e
                            docker pull ${IMAGE_NAME}:${IMAGE_TAG}
                            export DOCKER_IMAGE=${IMAGE_NAME}:${IMAGE_TAG}
                            docker-compose down && docker-compose up -d
                            sleep 20
                            curl --fail http://localhost:8081/actuator/health
                            docker image prune -f
                        "
                    '''
                }
            }
        }
    }

    post {
        success { echo "✅ Build #${env.BUILD_NUMBER} deploy thành công!" }
        failure { echo "❌ Build #${env.BUILD_NUMBER} thất bại!" }
        always  { cleanWs() }
    }
}
```

---

### 3.3 — Cấu hình Jenkins UI

**Thêm Credentials:**
```
Jenkins → Manage Jenkins → Credentials → Global → Add:

ID: dockerhub-creds  | Type: Username with password
ID: db-password      | Type: Secret text
ID: server-ssh-key   | Type: SSH Username with private key
```

**Tạo Pipeline Job:**
```
New Item → Pipeline
  → Pipeline script from SCM
  → SCM: Git (URL repo)
  → Script Path: Jenkinsfile
```

**Kết nối GitHub Webhook:**
```
GitHub repo → Settings → Webhooks → Add:
  URL: http://<jenkins-host>:8080/github-webhook/
  Content: application/json
  Events: push
```

---

## 🔥 GIAI ĐOẠN 4 — MÔ PHỎNG SỰ CỐ THỰC TẾ

---

### 🔴 Sự Cố 1: Deploy Lỗi Do Thiếu Biến Env

**Tái hiện:**
```bash
rm .env && docker-compose restart app
docker logs desops_app --tail 50
```

**Log thấy:**
```
HikariPool-1 - Failed to initialize pool: Connection refused
```

**Fix:**
```bash
docker exec desops_app env | grep DB_
cp .env.example .env && nano .env
docker-compose up -d
```

**Phòng ngừa pipeline:**
```yaml
- name: Health Check sau deploy
  run: |
    sleep 20
    curl --fail --retry 3 --retry-delay 5 \
      http://$SERVER_HOST:8081/actuator/health || exit 1
```

---

### 🔴 Sự Cố 2: Server Chạy Image Cũ

**Tái hiện:**
```bash
docker images | grep desops-demo   # Thấy nhiều image latest khác nhau
```

**Nguyên nhân:** `docker-compose up -d` không pull nếu tag `latest` đã có local.

**Fix:**
```bash
# Luôn dùng SHA tag, không dùng latest để deploy
docker pull desops-demo:abc1234
DOCKER_IMAGE=desops-demo:abc1234 docker-compose up -d

# Hoặc ép pull:
docker-compose pull && docker-compose up -d
```

---

### 🔴 Sự Cố 3: Database Schema Conflict

**Tái hiện:**
```java
@Column(nullable = false)   // Thêm cột NOT NULL vào entity — không có default
private String priority;
```

**Log:**
```
SchemaManagementException: column "priority" contains null values
```

**Fix ngay (rollback):**
```bash
PREVIOUS_SHA=$(docker inspect desops_app --format='{{.Config.Image}}')
docker-compose down
DOCKER_IMAGE=$PREVIOUS_SHA docker-compose up -d
```

**Fix đúng:**
```java
@Column(nullable = false, columnDefinition = "VARCHAR(50) DEFAULT 'NORMAL'")
private String priority;
```

---

### 🔴 Sự Cố 4: Secret Lộ Trong Log

**Tái hiện:**
```java
log.debug("Config: {}", env.getProperty("spring.datasource.password"));
// → In ra password trong docker logs
```

**Fix ngay:**
```bash
# Rotate credentials NGAY (đổi password DB)
# Update .env trên server và GitHub Secrets
# Redeploy
```

**Phòng ngừa:**
```yaml
- name: 🔍 Secret Scan
  uses: trufflesecurity/trufflehog@main
  with: { path: ./, base: main }
```

---

### 🔴 Sự Cố 5: Pipeline Bị Treo

**Fix:**
```yaml
# GitHub Actions
jobs:
  test:
    timeout-minutes: 15
```

```groovy
// Jenkinsfile
options { timeout(time: 30, unit: 'MINUTES') }
```

---

## 📚 Kiến Thức Cần Nắm Song Song

| Chủ đề | Tại Sao Cần |
|---|---|
| **SSH Key** | Deploy lên server qua pipeline |
| **Docker multi-stage** | Image nhỏ, build nhanh |
| **GitHub Secrets** | Không hardcode credentials |
| **Health check** | Biết chắc app sống sau deploy |
| **Rollback strategy** | Khi vỡ thì xử lý ngay |
| **Semantic versioning** | Tag image đúng cách |
| **Flyway** | Schema migration an toàn cho production |
| **Webhook** | Trigger pipeline tự động khi push |

---

## 🗓️ Lịch Trình Thực Hành Đề Xuất

```
Tuần 1 → Giai đoạn 1: docker-compose up -d thành công
Tuần 2 → Giai đoạn 2 (CI): Pipeline xanh khi push code
Tuần 3 → Giai đoạn 2 (CD): Image lên Docker Hub + auto deploy
Tuần 4 → Giai đoạn 3: Jenkins chạy pipeline tương đương
Tuần 5 → Giai đoạn 4: Tái hiện từng sự cố → fix → hardening
```

---

*Văn Phúc — DevOps Lab Roadmap | 2026-04*
