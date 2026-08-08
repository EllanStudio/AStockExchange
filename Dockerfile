FROM gradle:9.6.1-jdk25 AS build
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY astock-domain ./astock-domain
COPY astock-service ./astock-service
COPY astock-paper/build.gradle.kts ./astock-paper/build.gradle.kts
RUN gradle --no-daemon --no-configuration-cache :astock-service:bootJar

FROM eclipse-temurin:25.0.3_9-jre-ubi10-minimal
WORKDIR /opt/astock
RUN microdnf install -y curl shadow-utils \
    && microdnf clean all \
    && useradd --system --uid 10001 --home-dir /opt/astock astock
COPY --from=build --chown=astock:astock /workspace/astock-service/build/libs/astock-service.jar ./astock-service.jar
USER astock
EXPOSE 8787
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Dfile.encoding=UTF-8", "-jar", "/opt/astock/astock-service.jar"]
