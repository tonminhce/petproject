# H36 — non-root USER. eclipse-temurin images run as root by default; add
# an unprivileged user and switch before the entrypoint so a container
# escape gives the attacker no root in the kernel. chown the jar at COPY
# time so the runtime user can read it without tripping the FS perms.
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S appuser && adduser -S appuser -G appuser
COPY --chown=appuser:appuser target/*.jar app.jar
USER appuser
ENTRYPOINT ["java", "-jar", "app.jar"]
