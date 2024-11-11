# Stage 1 - collect deps

FROM clojure:temurin-23-tools-deps-noble

WORKDIR /app

COPY deps.edn build.clj   ./

RUN --mount=type=cache,target=/root/.m2 clojure -T:build copy-deps :target-dir '"deps-jars"'

# Stage 2

FROM eclipse-temurin:23-jdk-noble

ARG BUILD_VERSION
ARG GIT_SHA

WORKDIR /app

COPY --from=0 /app/deps-jars ./deps-jars

ADD res ./res
ADD src ./src
COPY run.sh ./

RUN echo $BUILD_VERSION > VERSION && echo $GIT_SHA > GIT_SHA

CMD ["bash", "/app/run.sh"]
