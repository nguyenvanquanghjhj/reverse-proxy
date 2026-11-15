FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
# Image builds also run the complete Java TestSuite before packaging runtime artifacts.
RUN mvn -B --no-transfer-progress -Pshop package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/classes ./classes
COPY --from=build /build/target/lib ./lib
COPY db/migrations ./db/migrations
COPY deploy/compose/proxy.properties ./deploy/compose/proxy.properties
ENV CLASSPATH=/app/classes:/app/lib/*
# Main class and ports are configured by Compose, no fallback database credentials.
CMD ["java", "com.example.proxy.Main", "deploy/compose/proxy.properties"]
