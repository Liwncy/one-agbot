# syntax=docker/dockerfile:1
# 多阶段：一次 Maven 打出 boot + snailai-server 两个 jar
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
COPY one-common one-common
COPY one-kernel one-kernel
COPY one-agent one-agent
COPY one-adapter one-adapter
COPY one-boot one-boot
COPY one-extend one-extend

# /root/.m2 挂 BuildKit 缓存，改代码不会把依赖层一起丢掉
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests package

FROM eclipse-temurin:21-jre AS boot
WORKDIR /app
RUN apt-get update \
  && apt-get install -y --no-install-recommends curl \
  && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/one-boot/target/one-boot.jar /app/app.jar
EXPOSE 8088 38088
ENTRYPOINT ["java", "-Duser.timezone=Asia/Shanghai", "-jar", "/app/app.jar"]

FROM eclipse-temurin:21-jre AS snailai-server
WORKDIR /app
RUN apt-get update \
  && apt-get install -y --no-install-recommends curl \
  && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/one-extend/one-snailai-server/target/one-snailai-server.jar /app/app.jar
EXPOSE 8900 18888
ENTRYPOINT ["java", "-Duser.timezone=Asia/Shanghai", "-jar", "/app/app.jar"]
