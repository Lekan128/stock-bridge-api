# Checking and reducing the API's memory use

How to find out where a Java app's memory goes, and what to change to bring it
down. The numbers in this guide were measured on stock-bridge-api on
2026-11-11, under the same limits as staging on Render (512MB, 0.5 CPU).

## 1. Measure the right number

The container is killed when the **whole process** goes over the limit, not
just the heap. Watch the process's resident memory (RSS):

```sh
docker stats --no-stream <container>
docker exec <container> grep -E "VmRSS|RssAnon|Threads" /proc/1/status
docker exec <container> cat /sys/fs/cgroup/memory.current   # what the limit is checked against
```

Always test with the same limits as the host, or the numbers mean nothing:

```sh
docker run --memory=512m --memory-swap=512m --cpus=0.5 ... <image>
```

Use a throwaway database (`CREATE DATABASE sb_memtest`) so test runs don't
migrate a real one.

## 2. See where the memory goes

Start the JVM with Native Memory Tracking (it adds about 10MB, so give the test
container some headroom, e.g. `--memory=1g`):

```sh
java -XX:NativeMemoryTracking=summary ... -jar app.jar
jcmd 1 VM.native_memory summary scale=MB
```

`jcmd` ships with the JDK, not the JRE. The runtime image is a JRE, so run the
same jar on `eclipse-temurin:25-jdk-alpine` for this:

```sh
docker cp <container>:/app/app.jar ./app.jar
docker run ... -v "$PWD/app.jar:/app.jar:ro" eclipse-temurin:25-jdk-alpine \
  java -XX:NativeMemoryTracking=summary <same flags> -jar /app.jar
```

What the main parts mean, and what we measured:

| Part | What it holds | Measured |
|---|---|---|
| Java Heap | Your objects | 256MB committed (live data only ~110MB) |
| Metaspace + Class | Class definitions (~27,000 classes) | ~135MB |
| Symbol | Class, method and string names | ~27MB |
| Shared class space | JDK classes shared from the CDS archive | ~14MB |
| Code | Machine code from the JIT compiler | ~38MB |
| Compiler | JIT working memory (spikes while compiling) | 0 at rest, tens of MB at peaks |
| Thread | Thread stacks | ~3MB used for 39 threads |
| GC | Garbage collector bookkeeping | ~1MB with Serial GC |
| Not tracked | Native libraries, malloc | ~30MB (RSS minus NMT total) |

**Everything outside the heap came to about 260MB**, and it doesn't shrink
when the heap does.

## 3. Find what the heap really needs

```sh
jcmd 1 GC.run         # force a full collection
jcmd 1 GC.heap_info   # "used" in Tenured afterwards = live data
```

The used amount right after a full GC is the live data set (~110MB for us).
Give the heap about 1.5–2× that. A bigger heap mostly just lets garbage pile
up before it's collected.

**The sizing rule:** heap cap + non-heap + ~50MB margin < container limit.
For 512MB: 205MB + ~260MB + margin, which is why the heap is set to 40%.

## 4. What to change, biggest payoff first

### JVM flags (Dockerfile `JAVA_OPTS`; override with Render env vars)

| Flag | Effect |
|---|---|
| `-XX:MaxRAMPercentage=40.0` | Heap cap as a share of the container limit (the old 75% caused the OOM) |
| `-XX:TieredStopAtLevel=1` | C1 JIT only. No C2 memory spikes, much faster startup on small CPUs, somewhat slower steady-state code |
| `-XX:ReservedCodeCacheSize=64m` | Ceiling for compiled code (default 240MB) |
| `-XX:MaxDirectMemorySize=32m` | Ceiling for off-heap NIO buffers (default = heap size) |
| `-Xss512k` | Stack per thread (default 1MB) |
| `-XX:+UseSerialGC` | Smallest GC overhead; best for 1 CPU and small heaps |
| `-XX:+ExitOnOutOfMemoryError` | Exit and restart cleanly instead of limping on |

Don't cap Metaspace (`MaxMetaspaceSize`) below what the app needs. It doesn't
save memory; the app just crashes when it hits the cap.

### Load fewer classes (the biggest cost outside the heap)

- `./mvnw dependency:tree` and remove starters and libraries you don't use.
- Use the specific AWS SDK modules you need (s3, sts, ...), not the whole SDK.
- Watch for two libraries doing the same job (JSON, HTTP clients, logging).
- Check the class count after a change: the `classes #` line in the NMT
  "Class" section.

### Run fewer threads

- `server.tomcat.threads.max` (default 200). Each thread costs a stack.
- Hikari `maximum-pool-size` (default 10) and the async executor pool sizes.
- Or `spring.threads.virtual.enabled=true`: virtual threads don't each need a
  full stack.

### Keep the heap's live data small

- No unbounded caches; give every cache a size limit.
- Page through large tables instead of loading them whole (imports, exports,
  reports).
- Stream big files (Excel, PDF) instead of holding them fully in memory.

## 5. If memory grows over time (a leak)

```sh
jcmd 1 GC.heap_dump /tmp/heap.hprof
docker cp <container>:/tmp/heap.hprof .
```

Open the dump in Eclipse MAT ("Leak Suspects" report), or in VisualVM. Take
two dumps some time apart and compare which object types grew.

## 6. Bigger structural options

- **GraalVM native image (Spring AOT):** compiles the app ahead of time into a
  single binary. Usually around 100–150MB and starts in under a second. The
  cost is slow builds and extra setup for reflection-heavy libraries.
- **JDK 25 AOT cache (Project Leyden):** faster startup with no code changes.
  Use `-XX:AOTCacheOutput` on a training run, then `-XX:AOTCache` in
  production.
- **More memory:** a 1GB+ plan for production gives real headroom. On 512MB
  there's only about 80MB to spare.

## Results (2026-11-11, 512MB / 0.5 CPU, 120 rounds of concurrent requests)

| Setup | Outcome |
|---|---|
| 75% heap (old Dockerfile) | OOM-killed on staging 2026-11-10 |
| 50% heap | OOM-killed during startup |
| 40% heap | Survived; ~485MB used, startup 222s |
| 40% heap + C1 only (current) | Survived; ~432MB used, startup 66s |
