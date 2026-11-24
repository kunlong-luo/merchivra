# The checked-in, SHA-256-verified Gradle Wrapper pins Gradle 9.2.1.
# JDK 25 builds with the same tasks as developer machines and CI.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY settings.gradle build.gradle gradle.properties gradlew ./
COPY gradle ./gradle
RUN chmod +x ./gradlew
COPY src ./src
# Cold disposable CI runners can receive Maven Central HTTP 429 while
# resolving official Gradle dependencies. Retry ONLY a documented transient
# HTTP response, never a compilation/test/Flyway failure. Keep the original
# failing output visible and cap the wait; do not skip build or pin a mirror.
RUN set -eu; \
    attempt=1; \
    while [ "$attempt" -le 3 ]; do \
      if ./gradlew --no-daemon --console plain bootJar > /tmp/bootjar-build.log 2>&1; then \
        cat /tmp/bootjar-build.log; \
        rm /tmp/bootjar-build.log; \
        exit 0; \
      fi; \
      cat /tmp/bootjar-build.log; \
      if [ "$attempt" -ge 3 ] || \
         ! grep -Eiq '(Received status code (429|502|503|504)|Too Many Requests|HTTP (429|502|503|504))' /tmp/bootjar-build.log; then \
        exit 1; \
      fi; \
      echo "Transient artifact repository response; retrying bounded Gradle bootJar attempt $((attempt + 1))/3"; \
      sleep $((attempt * 12)); \
      attempt=$((attempt + 1)); \
    done; \
    exit 1

FROM eclipse-temurin:25-jre
# V160: operator-supplied source claim, never a signature or registry digest.
ARG MERCHIVRA_BUILD_REVISION=""
LABEL org.opencontainers.image.source="https://github.com/kunlong-luo/merchivra" \
      org.opencontainers.image.revision="${MERCHIVRA_BUILD_REVISION}"
WORKDIR /app
RUN useradd --system --uid 10001 merchivra
COPY --from=build /build/build/libs/merchivra-server.jar /app/app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
