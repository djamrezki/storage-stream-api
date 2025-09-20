FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/libs/storage-api-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]