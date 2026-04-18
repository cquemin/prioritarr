# Build from repo root: docker build -f prioritarr-app/Dockerfile -t prioritarr-app:test .
FROM gradle:8-jdk21 AS build
WORKDIR /src
COPY openapi.json /src/openapi.json
COPY prioritarr-app/ /src/prioritarr-app/
WORKDIR /src/prioritarr-app
RUN gradle --no-daemon :backend:shadowJar

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=build /src/prioritarr-app/backend/build/libs/prioritarr.jar /app/prioritarr.jar
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "/app/prioritarr.jar"]
