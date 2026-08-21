# ==============================================================================
# STAGE 1: Build Application (Secure Maven Build)
# ==============================================================================
FROM eclipse-temurin:21-jdk-jammy AS app-builder

WORKDIR /app

# Copy Maven wrapper first
COPY .mvn/ .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw

# Settings XML generation
ARG GITHUB_ACTOR
RUN echo "<settings><servers>" > settings.xml && \
    echo "  <server><id>github-merkletrie</id><username>${GITHUB_ACTOR}</username><password>\${env.GITHUB_TOKEN}</password></server>" >> settings.xml && \
    echo "  <server><id>github-rlp</id><username>${GITHUB_ACTOR}</username><password>\${env.GITHUB_TOKEN}</password></server>" >> settings.xml && \
    echo "  <server><id>github-cryptoj</id><username>${GITHUB_ACTOR}</username><password>\${env.GITHUB_TOKEN}</password></server>" >> settings.xml && \
    echo "  <server><id>github</id><username>${GITHUB_ACTOR}</username><password>\${env.GITHUB_TOKEN}</password></server>" >> settings.xml && \
    echo "</servers></settings>" >> settings.xml

# Download Dependencies (Permanent Layer)
RUN --mount=type=secret,id=github_token,required=true \
    export GITHUB_TOKEN="$(cat /run/secrets/github_token)" && \
    ./mvnw -B -ntp -Prelease-artifact \
      dependency:resolve dependency:resolve-plugins \
      -s settings.xml

# Build Application
COPY src ./src

ARG BUILD_GIT_COMMIT
ARG CRYPTOJ_SHA256
ARG RANDOMX_SOURCE_COMMIT

RUN --mount=type=secret,id=github_token,required=true \
    export GITHUB_TOKEN="$(cat /run/secrets/github_token)" && \
    printf '%s' "${BUILD_GIT_COMMIT}" | grep -Eq '^[0-9a-f]{40,64}$' && \
    printf '%s' "${CRYPTOJ_SHA256}" | grep -Eq '^[0-9a-f]{64}$' && \
    CRYPTOJ_VERSION=$(./mvnw help:evaluate -Dexpression=goldenera-cryptoj.version -q -DforceStdout -s settings.xml) && \
    PINNED_RANDOMX_SOURCE_COMMIT=$(./mvnw help:evaluate -Dexpression=goldenera-randomx.source.commit -q -DforceStdout -s settings.xml) && \
    CRYPTOJ_JAR="${HOME}/.m2/repository/global/goldenera/cryptoj/goldenera-cryptoj/${CRYPTOJ_VERSION}/goldenera-cryptoj-${CRYPTOJ_VERSION}.jar" && \
    test -f "${CRYPTOJ_JAR}" && \
    test "$(sha256sum "${CRYPTOJ_JAR}" | cut -d ' ' -f 1)" = "${CRYPTOJ_SHA256}" && \
    test "${PINNED_RANDOMX_SOURCE_COMMIT}" = "${RANDOMX_SOURCE_COMMIT}" && \
    ./mvnw clean package -Prelease-artifact -DskipTests \
      -Dgoldenera.git.commit="${BUILD_GIT_COMMIT}" \
      -Dgoldenera.cryptoj.sha256="${CRYPTOJ_SHA256}" \
      -s settings.xml

# ==============================================================================
# STAGE 2: Production Runtime (Ubuntu 24.04 + RandomX JIT)
# ==============================================================================
FROM ubuntu:24.04 AS runtime-base

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
ARG RANDOMX_SOURCE_COMMIT
WORKDIR /usr/src
RUN printf '%s' "${RANDOMX_SOURCE_COMMIT}" | grep -Eq '^[0-9a-f]{40}$' \
    && git init goldenera-randomx \
    && cd goldenera-randomx \
    && git remote add origin https://github.com/GoldenEraGlobal/goldenera-randomx.git \
    && git fetch --depth 1 origin "${RANDOMX_SOURCE_COMMIT}" \
    && git checkout --detach FETCH_HEAD \
    && git submodule update --init --recursive

# 4. User Setup
RUN groupadd -r blockchain && useradd -r -g blockchain -d ${APP_HOME} -s /sbin/nologin blockchain

WORKDIR ${APP_HOME}

# 5. Copy Runtime Entrypoint
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

# Compile the CPU-native RandomX library once in a cacheable local-sandbox layer.
# The production target intentionally does not consume this stage: production
# nodes continue to compile on first boot for the CPU on which they actually run.
FROM runtime-base AS sandbox-randomx-builder
RUN mkdir -p /tmp/randomx-build \
    && cd /tmp/randomx-build \
    && cmake /usr/src/goldenera-randomx/RandomX \
      -DCMAKE_BUILD_TYPE=Release \
      -DARCH=native \
      -DBUILD_SHARED_LIBS=ON \
      -DCMAKE_C_FLAGS="-fPIC" \
      -DCMAKE_SHARED_LINKER_FLAGS="-z noexecstack" > /dev/null \
    && make -j"$(nproc)" > /dev/null \
    && mkdir -p /sandbox-native \
    && case "$(uname -m)" in \
         x86_64) target=librandomx_linux_x86_64.so ;; \
         aarch64) target=librandomx_linux_aarch64.so ;; \
         *) echo "Unsupported sandbox architecture: $(uname -m)" >&2; exit 1 ;; \
       esac \
    && cp librandomx.so "/sandbox-native/${target}"

# Local sandbox builds consume the exact release-metadata JAR built through the
# host mise toolchain. This keeps unpublished local Maven artifacts out of the
# Docker build graph while preserving the same runtime image.
FROM runtime-base AS sandbox-local-runtime
ARG BUILD_GIT_COMMIT
ARG CRYPTOJ_SHA256
ARG RANDOMX_SOURCE_COMMIT
LABEL org.opencontainers.image.revision="${BUILD_GIT_COMMIT}" \
      global.goldenera.cryptoj.sha256="${CRYPTOJ_SHA256}" \
      global.goldenera.randomx.source.commit="${RANDOMX_SOURCE_COMMIT}"
COPY --chown=blockchain:blockchain target/goldenera-node-*.jar ${APP_HOME}/app.jar
COPY --from=sandbox-randomx-builder --chown=blockchain:blockchain \
    /sandbox-native/ ${APP_HOME}/overrides/native/

# Published/release builds remain self-contained and build the JAR in Docker.
FROM runtime-base AS release-runtime
ARG BUILD_GIT_COMMIT
ARG CRYPTOJ_SHA256
ARG RANDOMX_SOURCE_COMMIT
LABEL org.opencontainers.image.revision="${BUILD_GIT_COMMIT}" \
      global.goldenera.cryptoj.sha256="${CRYPTOJ_SHA256}" \
      global.goldenera.randomx.source.commit="${RANDOMX_SOURCE_COMMIT}"
COPY --from=app-builder --chown=blockchain:blockchain /app/target/*.jar ${APP_HOME}/app.jar
