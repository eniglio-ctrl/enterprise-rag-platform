FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY platform-common/pom.xml platform-common/pom.xml
COPY auth-service/pom.xml auth-service/pom.xml
COPY ingestion-service/pom.xml ingestion-service/pom.xml
COPY rag-service/pom.xml rag-service/pom.xml
COPY chat-service/pom.xml chat-service/pom.xml
RUN ./mvnw -q -pl rag-service -am dependency:go-offline

COPY platform-common/src platform-common/src
COPY rag-service/src rag-service/src
RUN ./mvnw -q -pl rag-service -am package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/rag-service/target/rag-service.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
