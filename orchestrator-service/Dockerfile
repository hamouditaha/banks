FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY common-events/pom.xml common-events/pom.xml
COPY discovery-server/pom.xml discovery-server/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY account-service/pom.xml account-service/pom.xml
COPY fraud-detection-service/pom.xml fraud-detection-service/pom.xml
COPY orchestrator-service/pom.xml orchestrator-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY common-events common-events
COPY orchestrator-service orchestrator-service
RUN mvn -q -pl orchestrator-service -am package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/orchestrator-service/target/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
