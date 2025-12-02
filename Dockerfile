# ==============================================================================
# STAGE 1: Build Application (Secure Maven Build)
# ==============================================================================
FROM eclipse-temurin:21-jdk-jammy AS app-builder

ARG GITHUB_ACTOR

WORKDIR /app

COPY .mvn/ .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw

# Settings XML generation
RUN echo "<settings><servers>" > settings.xml && \
    echo "  <server><id>github-merkletrie</id><username>${GITHUB_ACTOR}</username><password>\${env.GITHUB_TOKEN}</password></server>" >> settings.xml && \
    echo "  <server><id>github-rlp</id><username>${GITHUB_ACTOR}</username><password>\${env.GITHUB_TOKEN}</password></server>" >> settings.xml && \
    echo "  <server><id>github-cryptoj</id><username>${GITHUB_ACTOR}</username><password>\${env.GITHUB_TOKEN}</password></server>" >> settings.xml && \
    echo "  <server><id>github</id><username>${GITHUB_ACTOR}</username><password>\${env.GITHUB_TOKEN}</password></server>" >> settings.xml && \
    echo "</servers></settings>" >> settings.xml

# Resolve dependencies
RUN --mount=type=secret,id=github_token \
    --mount=type=cache,target=/root/.m2 \
    export GITHUB_TOKEN=$(cat /run/secrets/github_token) && \
    ./mvnw dependency:go-offline -s settings.xml || true

# Build Package
COPY src ./src

RUN --mount=type=secret,id=github_token \
    --mount=type=cache,target=/root/.m2 \
    export GITHUB_TOKEN=$(cat /run/secrets/github_token) && \
    ./mvnw clean package -DskipTests -s settings.xml

# ==============================================================================
# STAGE 2: Production Runtime (Ubuntu + RandomX JIT)
# ==============================================================================
FROM ubuntu:22.04

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
ENV DEBIAN_FRONTEND=noninteractive
ENV APP_HOME=/app
ENV APP_DATA_DIR=/app/node_data

# 1. Install Dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential cmake git ca-certificates curl wget libstdc++6 \
    && rm -rf /var/lib/apt/lists/*

# 2. Install JDK 21
RUN wget -O - https://packages.adoptium.net/artifactory/api/gpg/key/public | tee /etc/apt/keyrings/adoptium.asc \
    && echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" | tee /etc/apt/sources.list.d/adoptium.list \
    && apt-get update && apt-get install -y temurin-21-jdk && rm -rf /var/lib/apt/lists/*

# 3. Clone RandomX
WORKDIR /usr/src
RUN git clone https://github.com/GoldenEraGlobal/goldenera-randomx.git \
    && cd goldenera-randomx \
    && git submodule update --init --recursive

# 4. User Setup
RUN groupadd -r blockchain && useradd -r -g blockchain -d ${APP_HOME} -s /sbin/nologin blockchain

WORKDIR ${APP_HOME}

# 5. Copy Artifacts
COPY --from=app-builder /app/target/*.jar ${APP_HOME}/app.jar
COPY scripts/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# 6. Structure & Permissions (Initial setup)
RUN mkdir -p ${APP_HOME}/overrides/native \
    && mkdir -p ${APP_HOME}/node_logs \
    && mkdir -p ${APP_HOME}/node_data \
    && chown -R blockchain:blockchain ${APP_HOME}

EXPOSE 8080 9000 443 80
VOLUME ["/app/node_data", "/app/node_logs"]

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]