FROM eclipse-temurin:25-jre-noble

ARG MOON_SANDBOX_UID=10001
ARG MOON_SANDBOX_GID=10001
ARG PPT_MASTER_BUNDLE=deploy/skills/ppt-master-skill-v6.7.2.zip

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    TZ=Asia/Shanghai \
    HOME=/tmp \
    TMPDIR=/tmp \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

# PPT Master（ppt-master）核心依赖：SVG → 可编辑 PPTX、模板注册、网页/文档取源。
# PyMuPDF 属于 AGPL，默认不预装；只有需要 PDF 源转 Markdown 时再按需安装。
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
        python-pptx \
        PyYAML \
        XlsxWriter \
        skia-pathops \
        uharfbuzz \
        requests \
        beautifulsoup4 \
        markdownify \
        mammoth \
        ebooklib \
        nbconvert \
        flask \
        edge-tts \
        curl_cffi \
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

# 技能包只常驻在镜像里（只读），不挂进任何会话工作区；
# 只有本轮引用了 ppt-master 时，MoonSandbox 才把它按需复制到 /workspaces/{conversationId}/skills/。
COPY ${PPT_MASTER_BUNDLE} /opt/moon-skills/ppt-master-skill.zip
RUN python3 -m zipfile -e /opt/moon-skills/ppt-master-skill.zip /opt/moon-skills/extract \
    && mv /opt/moon-skills/extract/ppt-master/skills/ppt-master /opt/moon-skills/ppt-master \
    && rm -rf /opt/moon-skills/extract /opt/moon-skills/ppt-master-skill.zip \
    && chmod -R a+rX /opt/moon-skills

USER moon-sandbox
EXPOSE 8090
VOLUME ["/workspaces"]

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
    CMD curl -fsS "http://127.0.0.1:8090/actuator/health" >/dev/null || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+UseG1GC", "-Djava.io.tmpdir=/tmp", "-jar", "/app/moon-sandbox.jar"]
