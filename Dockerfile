# syntax=docker/dockerfile:1

# ---- Build stage ----------------------------------------------------------
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app

# Copy only the files needed to resolve dependencies first, so this layer
# (and the downloaded ~/.m2 cache) is reused on rebuilds unless pom.xml or
# the wrapper itself changes.
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN ./mvnw dependency:go-offline -B

# Now bring in the rest of the source and build.
COPY src src
RUN ./mvnw clean package -DskipTests -B

# ---- Runtime stage ----------------------------------------------------------
# JRE-only image: nothing here compiles code, so the JDK's compiler/tooling
# would just be unused weight in the final image.
FROM eclipse-temurin:25-jre-alpine AS runtime
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup \
    && chown appuser:appgroup /app
COPY --from=build --chown=appuser:appgroup /app/target/*.jar app.jar

USER appuser

EXPOSE 8080

# -XX:MaxRAMPercentage=75.0: modern JDKs already read the container's cgroup
# memory limit to size the heap, but they default to 25% of it, which is
# needlessly conservative for a single-process container. 75% leaves enough
# headroom outside the heap for thread stacks, metaspace, and native buffers
# without wasting most of the container's memory budget - useful on small
# instance tiers (e.g. Render's 512MB-1GB plans) where every MB counts.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1
