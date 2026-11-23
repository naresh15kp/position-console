FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21.0.7_6-jre-alpine
WORKDIR /app
COPY --from=build /src/target/position-console-*.jar /app/app.jar
USER 10001:10001
ENTRYPOINT ["java","-XX:MaxRAMPercentage=65","-jar","/app/app.jar"]