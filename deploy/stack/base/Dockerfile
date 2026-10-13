# 共享 Java 运行时基座：只装一次 curl/tzdata（可选 ffmpeg），避免每个应用镜像都跑 apt
ARG BASE_IMAGE=eclipse-temurin:25-jre-noble
FROM ${BASE_IMAGE}

ARG APT_MIRROR=mirrors.aliyun.com
ARG WITH_FFMPEG=false

RUN set -eux; \
    if [ -n "$APT_MIRROR" ]; then \
        for f in /etc/apt/sources.list /etc/apt/sources.list.d/ubuntu.sources; do \
            [ -f "$f" ] && sed -i "s|archive.ubuntu.com|${APT_MIRROR}|g; s|security.ubuntu.com|${APT_MIRROR}|g" "$f"; \
        done; \
    fi; \
    printf 'Acquire::Retries "6";\nAcquire::http::Timeout "90";\nAcquire::https::Timeout "90";\n' > /etc/apt/apt.conf.d/99retries; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl ca-certificates tzdata; \
    if [ "$(printf '%s' "$WITH_FFMPEG" | tr '[:upper:]' '[:lower:]')" = "true" ]; then \
        apt-get install -y --no-install-recommends ffmpeg; \
    fi; \
    rm -rf /var/lib/apt/lists/*; \
    ln -snf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime

ENV TZ=Asia/Shanghai LANG=C.UTF-8
