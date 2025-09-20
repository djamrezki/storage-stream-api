FROM eclipse-temurin:21-jre-alpine AS prep
WORKDIR /app
COPY build/libs/storage-api-*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract && \
    mkdir -p /app/snapshot-dependencies

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# order matters for cache effectiveness:
COPY --from=prep /app/dependencies/           ./dependencies/
COPY --from=prep /app/snapshot-dependencies/  ./snapshot-dependencies/
COPY --from=prep /app/spring-boot-loader/     ./spring-boot-loader/
COPY --from=prep /app/application/            ./application/
EXPOSE 8080
ENTRYPOINT ["java","org.springframework.boot.loader.launch.JarLauncher"]
