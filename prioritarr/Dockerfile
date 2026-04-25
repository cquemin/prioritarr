# Build from repo root: docker build -f prioritarr/Dockerfile -t prioritarr:test .

# Stage 1: build the React UI → dist/
FROM node:22-alpine AS ui-build
WORKDIR /ui
COPY openapi.json /openapi.json
COPY prioritarr/frontend/package.json prioritarr/frontend/package-lock.json ./
RUN npm ci --legacy-peer-deps
COPY prioritarr/frontend/ ./
# The gen:api script resolves `../../openapi.json` relative to /ui, which
# is /openapi.json — already present from the COPY above. No symlink.
RUN npm run gen:api && npm run build

# Stage 2: gradle build with the UI static assets baked into resources
FROM gradle:8-jdk21 AS build
WORKDIR /src
COPY openapi.json /src/openapi.json
COPY prioritarr/ /src/prioritarr/
COPY --from=ui-build /ui/dist/ /src/prioritarr/backend/src/main/resources/static/
WORKDIR /src/prioritarr
RUN gradle --no-daemon :backend:shadowJar

# Stage 3: runtime — JRE-only, fat jar carries the UI inside.
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=build /src/prioritarr/backend/build/libs/prioritarr.jar /app/prioritarr.jar
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "/app/prioritarr.jar"]
