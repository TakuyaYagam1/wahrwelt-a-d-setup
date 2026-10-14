FROM node:slim AS frontend
WORKDIR /workspace/frontend
COPY frontend/package.json ./
RUN npm install --global npm \
    && npm install --no-audit --no-fund --package-lock=false
COPY frontend/ ./
RUN npm test && npm run build

FROM gradle:latest AS gradle-dist

FROM eclipse-temurin:27-jdk AS app-build
COPY --from=gradle-dist /opt/gradle /opt/gradle
ENV GRADLE_HOME=/opt/gradle \
    PATH=/opt/gradle/bin:$PATH \
    JAVA_TOOL_OPTIONS=-Xmx2g
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts ./
COPY src/ ./src/
COPY --from=frontend /workspace/frontend/dist/ ./src/main/resources/static/
RUN gradle --no-daemon --max-workers=2 test bootJar

FROM eclipse-temurin:27-jre
RUN apt-get update \
    && apt-get upgrade -y --no-install-recommends \
    && apt-get install --no-install-recommends -y curl libpcap0.8 \
    && rm -f /usr/bin/pebble \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app \
    && chown 10001:10001 /app
WORKDIR /app
COPY --from=app-build --chown=10001:10001 /workspace/build/libs/packmate-1.0.0.jar /app/packmate.jar
COPY --from=project /LICENSE /usr/share/licenses/wahrwelt/LICENSE
USER 10001:10001
EXPOSE 65000
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD curl --max-time 3 --fail --silent --output /dev/null "http://127.0.0.1:${SERVER_PORT:-65000}/healthz"
ENTRYPOINT ["java", "-Djna.tmpdir=/jna-tmp", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/packmate.jar"]
