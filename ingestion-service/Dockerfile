FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY ingestion-service/pom.xml ingestion-service/pom.xml
COPY rag-service/pom.xml rag-service/pom.xml
RUN ./mvnw -q -pl ingestion-service -am dependency:go-offline

COPY ingestion-service/src ingestion-service/src
RUN ./mvnw -q -pl ingestion-service -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/ingestion-service/target/ingestion-service.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
