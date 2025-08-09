# Multi-stage build for MemQuorum API Gateway
FROM openjdk:21-jdk-slim AS builder

# 设置工作目录
WORKDIR /app

# 安装必要的工具
RUN apt-get update && apt-get install -y \
    curl \
    wget \
    && rm -rf /var/lib/apt/lists/*

# 复制Maven包装器和父pom.xml
COPY mvnw .
COPY mvnw.cmd .
COPY .mvn .mvn
COPY pom.xml .

# 复制所有模块
COPY api/ api/
COPY engine/ engine/

# 创建.m2目录用于挂载
RUN mkdir -p /root/.m2

# 下载依赖并编译 (使用--mount缓存.m2仓库)
RUN --mount=type=cache,target=/root/.m2 ./mvnw dependency:go-offline -B
RUN --mount=type=cache,target=/root/.m2 ./mvnw clean package -DskipTests

# Runtime stage
FROM openjdk:21-jdk-slim

# 安装curl for health checks
RUN apt-get update && apt-get install -y \
    curl \
    && rm -rf /var/lib/apt/lists/*

# 创建运行时用户
RUN groupadd -r memquorum && useradd -r -g memquorum memquorum

# 创建日志目录
RUN mkdir -p /app/logs && \
    chown -R memquorum:memquorum /app

# 复制构建的jar文件
COPY --from=builder --chown=memquorum:memquorum /app/api/target/api-0.0.1-SNAPSHOT.jar /app/app.jar

# 设置环境变量
ENV JAVA_OPTS="-Xmx256m -Xms128m -XX:+UseG1GC"
ENV SERVER_PORT=8080
ENV NACOS_SERVER_ADDR=nacos-server:8848
ENV MEMQUORUM_ENGINE_TOTAL_SHARDS=16

# 暴露端口
EXPOSE 8080

# 切换到非root用户
USER memquorum
WORKDIR /app

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:${SERVER_PORT}/api/v1/memory/health || exit 1

# 启动命令
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]