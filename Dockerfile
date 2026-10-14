# Multi-stage build (plan.md Milestone 8.2).

# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Dependencies first, cached across builds as long as pom.xml is unchanged.
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src src
RUN mvn -q clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre
# Tesseract's native library (Tess4J/JNA, used for Tier 2 OCR on images >2MiB); the bundled English
# tessdata (src/main/resources/tessdata/eng.traineddata) is already packaged into the jar and extracted
# to a temp dir at runtime (TesseractOcrDocumentParser), so nothing extra is needed for that part.
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-eng \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --shell /usr/sbin/nologin synapse
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
RUN chown synapse:synapse app.jar
USER synapse

EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
