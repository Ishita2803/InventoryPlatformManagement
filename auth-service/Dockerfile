# Multi-stage: build with a JDK, ship with a JRE. Same shape as every other service's
# Dockerfile in this project -- see payment-service/Dockerfile for the full reasoning
# behind each choice here.

# ---------------------------------------------------------------------------------------
# Stage 1 — build
# ---------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw

RUN --mount=type=cache,target=/root/.m2/repository,sharing=locked ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2/repository,sharing=locked ./mvnw -B -q clean package -DskipTests

RUN java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted

# ---------------------------------------------------------------------------------------
# Stage 2 — runtime
# ---------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --uid 10001 appuser
USER appuser

COPY --from=build --chown=appuser:appuser /build/extracted/dependencies/ ./
COPY --from=build --chown=appuser:appuser /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=appuser:appuser /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=appuser:appuser /build/extracted/application/ ./

EXPOSE 8085

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
