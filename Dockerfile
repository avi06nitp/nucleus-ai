FROM eclipse-temurin:17-jre-alpine
COPY target/nucleus-ai-*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
