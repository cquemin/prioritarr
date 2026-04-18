# Build from repo root: docker build -f prioritarr-app/Dockerfile -t prioritarr-app:test .

# Stage 1: build the React UI → dist/
FROM node:22-alpine AS ui-build
WORKDIR /ui
COPY openapi.json /openapi.json
COPY prioritarr-app/frontend/package.json prioritarr-app/frontend/package-lock.json ./
RUN npm ci --legacy-peer-deps
COPY prioritarr-app/frontend/ ./
# The codegen script expects openapi.json at ../../openapi.json; symlink
# it into place so `npm run gen:api` works from this working dir.
RUN mkdir -p ../.. && ln -sf /openapi.json ../../openapi.json \
 && npm run gen:api \
 && npm run build

# Stage 2: gradle build with the UI static assets baked into resources
FROM gradle:8-jdk21 AS build
WORKDIR /src
COPY openapi.json /src/openapi.json
COPY prioritarr-app/ /src/prioritarr-app/
COPY --from=ui-build /ui/dist/ /src/prioritarr-app/backend/src/main/resources/static/
WORKDIR /src/prioritarr-app
RUN gradle --no-daemon :backend:shadowJar

# Stage 3: runtime — JRE-only, fat jar carries the UI inside.
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=build /src/prioritarr-app/backend/build/libs/prioritarr.jar /app/prioritarr.jar
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "/app/prioritarr.jar"]
