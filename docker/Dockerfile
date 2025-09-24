# docker/Dockerfile

FROM eclipse-temurin:21-jre-alpine
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"
WORKDIR /opt/app

# copy the JAR produced by the CI step
COPY build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]
