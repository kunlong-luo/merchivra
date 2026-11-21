# The checked-in, SHA-256-verified Gradle Wrapper pins Gradle 9.2.1.
# JDK 25 builds with the same tasks as developer machines and CI.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY settings.gradle build.gradle gradle.properties gradlew ./
COPY gradle ./gradle
RUN chmod +x ./gradlew
COPY src ./src
RUN ./gradlew --no-daemon --console plain bootJar

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
