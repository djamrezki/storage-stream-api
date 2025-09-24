# ===== Build stage =====
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradlew gradle ./
COPY build.gradle settings.gradle ./
COPY src ./src
RUN ./gradlew clean bootJar -x test

# ===== Run stage =====
FROM eclipse-temurin:21-jre-alpine
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"
WORKDIR /opt/app
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]