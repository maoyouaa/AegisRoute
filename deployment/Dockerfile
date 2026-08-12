FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    chmod +x gradlew && ./gradlew \
    :apps:gateway:bootJar \
    :apps:control:bootJar \
    :apps:worker:bootJar \
    :apps:mock-provider:bootJar \
    --no-daemon --no-configuration-cache --max-workers=2 --console=plain

FROM eclipse-temurin:21-jre-jammy
ARG APP
RUN useradd --system --uid 10001 --create-home aegis
WORKDIR /app
COPY --from=build /workspace/apps/${APP}/build/libs/application.jar /app/application.jar
USER 10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/application.jar"]
