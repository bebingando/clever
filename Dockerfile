# ── Stage 1: build ───────────────────────────────────────────────────────────
FROM --platform=linux/amd64 sbtscala/scala-sbt:eclipse-temurin-jammy-21.0.2_13_1.10.0_3.4.1 AS builder

WORKDIR /build

# Copy dependency descriptors first so Docker can cache the layer.
COPY build.sbt .
COPY project/  project/
RUN sbt update

# Copy the full source and assemble the fat JAR.
COPY src/ src/
RUN sbt assembly

# ── Stage 2: runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Copy only the fat JAR from the builder stage.
COPY --from=builder /build/target/scala-3.4.2/clever-photos-api-assembly-0.1.0.jar app.jar

# The photos.csv is bundled inside the JAR (src/main/resources/photos.csv),
# so no separate COPY is needed.

EXPOSE 8080

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser
USER appuser

ENTRYPOINT ["java", "-Xmx512m", "-jar", "app.jar"]
