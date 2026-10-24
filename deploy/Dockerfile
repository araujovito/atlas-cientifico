# Compile primeiro em api/: ./mvnw -DskipTests package
FROM eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99
RUN apt-get update && apt-get install -y --no-install-recommends curl fontconfig \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 atlas && useradd --uid 10001 --gid atlas --no-create-home atlas \
    && mkdir -p /app /data/documentos && chown -R atlas:atlas /app /data
WORKDIR /app
COPY --chown=atlas:atlas api/target/atlas-0.0.1-SNAPSHOT.jar /app/atlas.jar
USER 10001:10001
ENV ATLAS_DOCUMENTOS_DIR=/data/documentos
ENTRYPOINT ["java","-Xmx384m","-jar","/app/atlas.jar"]
