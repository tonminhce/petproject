FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S appuser && adduser -S appuser -G appuser
COPY --chown=appuser:appuser target/*.jar app.jar
USER appuser
ENTRYPOINT ["java", "-jar", "app.jar"]
