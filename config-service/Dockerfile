# Multi-stage: build with a JDK, ship with a JRE.
#
# The point is that the runtime image contains no compiler, no Maven, no source and no build
# cache — only what is needed to run. That is smaller, and it removes a pile of tooling from
# the attack surface of the thing actually exposed to traffic.

# ---------------------------------------------------------------------------------------
# Stage 1 — build
# ---------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# The wrapper and pom go in FIRST, on their own layer. Docker caches per layer, so as long
# as the pom is unchanged the dependency download below is reused — and a source-only change
# rebuilds in seconds instead of re-resolving every dependency.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw

# A BuildKit cache mount for the local Maven repository. Unlike a COPYed layer this is shared
# across builds AND across services, so the seven images in this project resolve Spring Boot
# once rather than seven times. It is not baked into the image.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Tests are deliberately NOT run here. They run in CI against real infrastructure; running
# them during an image build would need a database and a broker inside the builder, and
# would make every image rebuild pay for the whole suite.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q clean package -DskipTests

# Split the fat jar into its layers, so the runtime image can order them by change frequency.
RUN java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted

# ---------------------------------------------------------------------------------------
# Stage 2 — runtime
# ---------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# curl is only here for the container healthcheck; the JRE image ships without it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Never run as root. A container escape from a root process is a root process on the host.
RUN useradd --system --create-home --uid 10001 appuser
USER appuser

# Copied most-stable first: dependencies change rarely, application code changes every build.
# Reversing this order would invalidate the large dependency layer on every source edit.
COPY --from=build --chown=appuser:appuser /build/extracted/dependencies/ ./
COPY --from=build --chown=appuser:appuser /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=appuser:appuser /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=appuser:appuser /build/extracted/application/ ./

EXPOSE 8888

# MaxRAMPercentage, not -Xmx. The JVM's default heap sizing assumes it owns the machine; in a
# container with a memory limit that gets it wrong, and the kernel OOM-kills the process
# rather than the JVM throwing OutOfMemoryError. This makes the heap track the cgroup limit,
# which matters on the small Spot nodes Phase 15 uses.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# JarLauncher, not `-jar app.jar`: `extract --layers --launcher` produces an exploded
# directory rather than a jar, which is the whole point — the layers stay separate so
# Docker can cache them independently.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
