# syntax=docker/dockerfile:1
#
# 全部服务都跑容器：应用本身用多阶段构建，运行镜像不带 Maven、不带源码。
#   docker compose up -d --build      # 或 bash scripts/stack.sh up
#
# 构建产物固定叫 /src/app.jar，避免 target/ 里残留多个版本的 jar 让 COPY 撞车。

# ---------- 构建 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# ~/.m2 用 BuildKit 缓存：改源码不会重新下载依赖
COPY backend/ ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests package \
 && cp psych-app/target/psych-app-*.jar /src/app.jar

# ---------- 运行 ----------
FROM eclipse-temurin:21-jre-alpine AS runtime

# curl 只给 HEALTHCHECK 用；tzdata 让日志时间与后台页面都是本地时间
RUN apk add --no-cache curl tzdata \
 && addgroup -S -g 10001 app \
 && adduser -S -D -H -u 10001 -G app app

WORKDIR /app

# 后台可改的规则/话术/流程参数随镜像分发，首次启动即有一份可用配置
COPY --from=build /src/app.jar /app/app.jar
COPY backend/config /app/config

# 配置文件在运行时会被后台改写，目录必须归运行用户所有
RUN mkdir -p /app/config/history && chown -R app:app /app

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

USER app
EXPOSE 8080

# 应用启动要等数据库、要灌示例语料，给足 start-period
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=8 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
