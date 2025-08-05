# MemQuorum 分布式内存服务 Docker镜像
FROM openjdk:21-jdk-slim

# 设置工作目录
WORKDIR /app

# 安装必要的工具
RUN apt-get update && apt-get install -y \
    curl \
    wget \
    procps \
    && rm -rf /var/lib/apt/lists/*

# 复制Maven包装器和pom.xml
COPY mvnw .
COPY mvnw.cmd .
COPY .mvn .mvn
COPY pom.xml .

# 下载依赖（利用Docker缓存层）
RUN ./mvnw dependency:go-offline -B

# 复制源代码
COPY src src

# 编译项目
RUN ./mvnw clean package -DskipTests

# 创建运行时用户
RUN groupadd -r memquorum && useradd -r -g memquorum memquorum

# 创建日志和数据目录
RUN mkdir -p /app/logs /app/data /home/memquorum/logs/nacos && \
    chown -R memquorum:memquorum /app /home/memquorum

# 设置环境变量
ENV JAVA_OPTS="-Xmx1g -Xms512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
ENV SERVER_PORT=8080
ENV GRPC_PORT=9090
ENV NACOS_SERVER_ADDR=nacos-server:8848
ENV NODE_ID=""
ENV CLUSTER_NODES=""

# 暴露端口
EXPOSE 8080 9090

# 切换到非root用户
USER memquorum

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:${SERVER_PORT}/actuator/health || exit 1

# 启动脚本
COPY --chown=memquorum:memquorum docker-entrypoint.sh /app/
RUN chmod +x /app/docker-entrypoint.sh

ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["java", "-jar", "target/memquorum-1.0-SNAPSHOT.jar"]
