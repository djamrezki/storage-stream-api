FROM eclipse-temurin:21-jre-alpine AS prep
WORKDIR /app
COPY build/libs/storage-api-*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# order matters for cache effectiveness:
COPY --from=prep dependencies/ ./dependencies/
COPY --from=prep snapshot-dependencies/ ./snapshot-dependencies/
COPY --from=prep spring-boot-loader/ ./spring-boot-loader/
COPY --from=prep application/ ./application/
EXPOSE 8080
ENTRYPOINT ["java","org.springframework.boot.loader.launch.JarLauncher"]
