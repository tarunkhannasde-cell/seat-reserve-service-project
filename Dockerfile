# Same image locally (docker compose) and on Railway.
# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline || true
COPY src ./src
RUN mvn -q -B -DskipTests package && cp target/*.jar /src/app.jar

# ---- run ----
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /src/app.jar /app/app.jar
USER app
# PORT is set by the platform (Railway); 8080 locally.
# JAVA_OPTS: the JVM can see the whole host's RAM/CPUs instead of the container limit (on Railway it
# saw 48 CPUs and sized a ~31 GB heap, then got OOM-killed mid-burst). Cap it explicitly; override
# per environment with the JAVA_OPTS variable.
ENV PORT=8080 \
    JAVA_OPTS="-Xmx400m -Xss512k -XX:MaxMetaspaceSize=192m -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
