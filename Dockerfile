# =============================================================
# AgentKey 镜像构建
# 说明：项目本地没有 mvn / mvnw，因此构建阶段直接用官方 Maven 镜像，
#       不依赖宿主机 Maven 环境。
# =============================================================

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只拷 pom 并预热依赖，源码改动时可复用这一层缓存
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 非 root 运行：容器逃逸时降低影响面
RUN addgroup -S app && adduser -S -G app app

COPY --from=build /build/target/*.jar /app/app.jar

# 文档与聊天附件落在 ${user.dir}/uploads 下，由 compose 挂卷持久化
RUN mkdir -p /app/uploads && chown -R app:app /app
USER app

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Duser.timezone=Asia/Shanghai"
ENV SPRING_PROFILES_ACTIVE=prod

# 依赖 actuator/health（Spring Security 已放行 /actuator/health）
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
