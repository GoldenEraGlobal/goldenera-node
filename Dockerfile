# ==============================================================================
# STAGE 1: Build Application (Secure Maven Build)
# ==============================================================================
FROM eclipse-temurin:21-jdk-jammy AS app-builder

ARG GITHUB_ACTOR
ARG BUILD_GIT_COMMIT
ARG CRYPTOJ_SHA256

WORKDIR /app

# Copy Maven wrapper first
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

# Download Dependencies (Permanent Layer)
RUN --mount=type=secret,id=github_token \
    export GITHUB_TOKEN=$(cat /run/secrets/github_token) && \
    ./mvnw dependency:resolve dependency:resolve-plugins -s settings.xml || true

# Build Application
COPY src ./src

RUN --mount=type=secret,id=github_token \
    export GITHUB_TOKEN=$(cat /run/secrets/github_token) && \
    printf '%s' "${BUILD_GIT_COMMIT}" | grep -Eq '^[0-9a-f]{40,64}$' && \
    printf '%s' "${CRYPTOJ_SHA256}" | grep -Eq '^[0-9a-f]{64}$' && \
    CRYPTOJ_VERSION=$(./mvnw help:evaluate -Dexpression=goldenera-cryptoj.version -q -DforceStdout -s settings.xml) && \
    CRYPTOJ_JAR="${HOME}/.m2/repository/global/goldenera/cryptoj/goldenera-cryptoj/${CRYPTOJ_VERSION}/goldenera-cryptoj-${CRYPTOJ_VERSION}.jar" && \
    test -f "${CRYPTOJ_JAR}" && \
    test "$(sha256sum "${CRYPTOJ_JAR}" | cut -d ' ' -f 1)" = "${CRYPTOJ_SHA256}" && \
    ./mvnw clean package -Prelease-artifact -DskipTests \
      -Dgoldenera.git.commit="${BUILD_GIT_COMMIT}" \
      -Dgoldenera.cryptoj.sha256="${CRYPTOJ_SHA256}" \
      -s settings.xml

# ==============================================================================
# STAGE 2: Production Runtime (Ubuntu 24.04 + RandomX JIT)
# ==============================================================================
FROM ubuntu:24.04

ARG BUILD_GIT_COMMIT
ARG CRYPTOJ_SHA256

LABEL org.opencontainers.image.revision="${BUILD_GIT_COMMIT}" \
      global.goldenera.cryptoj.sha256="${CRYPTOJ_SHA256}"

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

# 3. Clone RandomX Fork
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

# 6. Structure & Permissions
RUN mkdir -p ${APP_HOME}/overrides/native \
    && mkdir -p ${APP_HOME}/node_logs \
    && mkdir -p ${APP_HOME}/node_data \
    && chown -R blockchain:blockchain ${APP_HOME}

EXPOSE 8080 9000 80 443
VOLUME ["/app/node_data", "/app/node_logs"]

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
