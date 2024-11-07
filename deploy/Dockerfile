FROM clojure:temurin-23-tools-deps-noble

WORKDIR /app

COPY deps.edn build.clj   ./

RUN --mount=type=cache,target=/root/.m2 clojure -T:build copy-deps :target-dir '"deps-jars"'

ADD res ./res
ADD src ./src
COPY run.sh ./

CMD ["bash", "/app/run.sh"]

# FROM openjdk:11-jre-slim

# WORKDIR /app

# # COPY --from=0 /app/target/app-standalone.jar ./app.jar
