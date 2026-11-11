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

# JVM memory sizing. The container is killed the moment the WHOLE process
# (heap + everything outside it) passes the plan's limit, so the heap must
# leave real room for the rest. Measured with NativeMemoryTracking at 512MB
# (2026-11-11): ~27k classes -> ~135MB metaspace, plus symbols, shared classes,
# JIT code and native libs = ~260MB outside the heap, while the live heap after
# a full GC is only ~110MB. 75% heap (384MB) on Render's 512MB plan got staging
# OOM-killed on 2026-11-10 with one user; even 50% (256MB) was killed at boot.
#   MaxRAMPercentage=40        heap up to 40% of the limit (205MB on 512MB)
#   TieredStopAtLevel=1        C1 JIT only: no C2 compiler memory spikes, and
#                              startup on 0.5 CPU fell 222s -> 66s; resident
#                              memory under load fell ~485MB -> ~432MB. Costs
#                              some peak CPU throughput - fine for a DB-bound
#                              API; drop it via JAVA_OPTS on a big plan.
#   ReservedCodeCacheSize=64m  JIT code cache (default 240MB reservation)
#   MaxDirectMemorySize=32m    NIO buffers (default = heap size)
#   Xss512k                    per-thread stack (default 1MB; Tomcat runs many)
#   UseSerialGC                least GC bookkeeping memory for 1 small CPU
#   ExitOnOutOfMemoryError     restart cleanly instead of limping on
#
# CHANGING THESE PER ENVIRONMENT (no rebuild needed)
# The ENV line below is only the default. To change it for one service, add an
# environment variable on the host. On Render: Dashboard -> the API service ->
# Environment -> Add Environment Variable, key JAVA_OPTS, value from the list
# below -> Save (Render redeploys). A host variable replaces this whole default,
# so always paste the full line, not just the flag you're changing. Not GitHub:
# nothing in the repo sets it, and this file's default stays as it is.
#
#   512MB plan (staging): don't add JAVA_OPTS - the default below fits.
#
#   1GB plan: full-speed JIT back on, bigger heap (C2 needs a larger code cache):
#     -XX:MaxRAMPercentage=55.0 -XX:ReservedCodeCacheSize=128m -XX:MaxDirectMemorySize=64m -Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError
#
#   2GB+ plan (1+ CPU): as 1GB, larger heap, and the JVM's default GC (G1):
#     -XX:MaxRAMPercentage=65.0 -XX:ReservedCodeCacheSize=128m -XX:MaxDirectMemorySize=64m -Xss512k -XX:+ExitOnOutOfMemoryError
#
# The 1GB/2GB values are estimates from the ~260MB measured outside the heap
# (C2 adds roughly 50-100MB on top of that at peaks). After changing them,
# check memory on the host's metrics page during real use - see JVM_MEMORY.md.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=40.0 -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=32m -Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1
