FROM eclipse-temurin:25-jre-noble

ARG MOON_SANDBOX_UID=10001
ARG MOON_SANDBOX_GID=10001

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    TZ=Asia/Shanghai \
    HOME=/tmp \
    TMPDIR=/tmp \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        fontconfig \
        fonts-dejavu-core \
        fonts-noto-cjk \
        libfreetype6 \
        libgomp1 \
        libjpeg-turbo8 \
        libopenblas0 \
        libpng16-16 \
        python3.12 \
        python3-pip \
        tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && python3 -m pip install --no-cache-dir --break-system-packages \
        python-docx \
        matplotlib \
        pandas \
        openpyxl \
        Pillow \
    && groupadd --system --gid "${MOON_SANDBOX_GID}" moon-sandbox \
    && useradd --system \
        --uid "${MOON_SANDBOX_UID}" \
        --gid moon-sandbox \
        --home-dir /home/moon-sandbox \
        --create-home \
        --shell /usr/sbin/nologin \
        moon-sandbox \
    && mkdir -p /app /workspaces \
    && chown -R moon-sandbox:moon-sandbox /workspaces /home/moon-sandbox

WORKDIR /app
COPY MoonSandbox/target/moon-sandbox.jar /app/moon-sandbox.jar

USER moon-sandbox
EXPOSE 8090
VOLUME ["/workspaces"]

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
    CMD curl -fsS "http://127.0.0.1:8090/actuator/health" >/dev/null || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+UseG1GC", "-Djava.io.tmpdir=/tmp", "-jar", "/app/moon-sandbox.jar"]
