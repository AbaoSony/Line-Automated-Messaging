# syntax=docker/dockerfile:1
FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
# 在可访问 Docker Engine 的宿主机先执行 mvn clean test；镜像构建内不运行嵌套容器测试。
COPY docs ./docs
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre-jammy AS runtime
RUN groupadd --gid 10001 line \
    && useradd --uid 10001 --gid line --no-create-home --shell /usr/sbin/nologin line
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/line-messaging-service-*.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
# Secrets are supplied at runtime; never use ARG/ENV to bake credentials into an image.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
