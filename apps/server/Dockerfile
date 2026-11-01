# Build with the same pinned Gradle major/minor as local development and CI.
FROM gradle:9.2.1-jdk25 AS build
WORKDIR /home/gradle/project
COPY --chown=gradle:gradle settings.gradle build.gradle gradle.properties ./
COPY --chown=gradle:gradle src ./src
RUN gradle --no-daemon --console plain bootJar

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 10001 merchivra
COPY --from=build /home/gradle/project/build/libs/merchivra-server.jar /app/app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
