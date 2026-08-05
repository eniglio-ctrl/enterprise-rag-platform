FROM eclipse-temurin:24-jdk AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY platform-common/pom.xml platform-common/pom.xml
COPY auth-service/pom.xml auth-service/pom.xml
COPY ingestion-service/pom.xml ingestion-service/pom.xml
COPY rag-service/pom.xml rag-service/pom.xml
COPY chat-service/pom.xml chat-service/pom.xml
RUN ./mvnw -q -pl ingestion-service -am dependency:go-offline

COPY platform-common/src platform-common/src
COPY ingestion-service/src ingestion-service/src
RUN ./mvnw -q -pl ingestion-service -am package -DskipTests

FROM eclipse-temurin:24-jre
WORKDIR /app
COPY --from=build /workspace/ingestion-service/target/ingestion-service.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
