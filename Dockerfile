FROM node:22-bookworm-slim AS modern-client
WORKDIR /src
COPY client/package.json client/package-lock.json ./client/
RUN npm ci --prefix client
COPY client ./client
RUN mkdir -p resources/public/modern \
    && npm run build --prefix client

FROM clojure:temurin-21-lein-2.11.2-bookworm-slim AS application-build
RUN apt-get update \
    && apt-get install -y --no-install-recommends nodejs npm \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY project.clj shadow-cljs.edn ./
RUN lein deps
COPY env ./env
COPY resources ./resources
COPY src ./src
COPY --from=modern-client /src/resources/public/modern ./resources/public/modern
RUN npx shadow-cljs release organism journey journey-bots oroboros eridu future \
    && lein uberjar

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system organism \
    && useradd --system --gid organism --home-dir /app organism
WORKDIR /app
COPY --from=application-build --chown=organism:organism /src/target/uberjar/organism.jar ./organism.jar
USER organism
EXPOSE 11551
ENTRYPOINT ["java", "-jar", "/app/organism.jar"]
