# Build from repo root: docker build -f prioritarr-kotlin/Dockerfile -t prioritarr-kotlin:test .
FROM gradle:8-jdk21 AS build
WORKDIR /src
COPY openapi.json /src/openapi.json
COPY prioritarr-kotlin/ /src/prioritarr-kotlin/
WORKDIR /src/prioritarr-kotlin
RUN gradle --no-daemon shadowJar

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=build /src/prioritarr-kotlin/app/build/libs/prioritarr.jar /app/prioritarr.jar
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "/app/prioritarr.jar"]
