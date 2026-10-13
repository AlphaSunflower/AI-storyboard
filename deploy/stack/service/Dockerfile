# 通用应用运行时镜像：构建上下文内只需一个 app.jar（由 scripts/build-apps 暂存）
# 基座镜像由 scripts/build-base.ps1 生成（含 curl/tzdata，moon-agent 版含 ffmpeg）
ARG BASE_IMAGE=aistoryboard/java-base:local
FROM ${BASE_IMAGE}

ARG APP_PORT=8080

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    APP_PORT=${APP_PORT}

RUN set -eux; \
    groupadd -g 1001 app 2>/dev/null || true; \
    useradd -u 1001 -g app -m -d /home/app -s /usr/sbin/nologin app 2>/dev/null || true; \
    mkdir -p /home/app/logs/nacos; \
    chown -R app:app /home/app

# nacos-client 的 logback 会往 $HOME/logs/nacos 写日志，HOME 指到已存在的目录可避免启动报错
ENV HOME=/home/app

WORKDIR /app
COPY app.jar /app/app.jar
RUN mkdir -p /data/uploads /app/logs && chown -R app:app /app /data

USER app
EXPOSE ${APP_PORT}

# 探活用 TCP 连接检查：api-gateway 未暴露 actuator、canvas 的 actuator 需要鉴权，
# 统一以「端口已在监听」为健康判据，避免误判重试。
HEALTHCHECK --interval=10s --timeout=5s --start-period=45s --retries=12 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${APP_PORT}' || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-Duser.timezone=Asia/Shanghai", "-jar", "/app/app.jar"]
