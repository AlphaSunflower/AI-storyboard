# 单 JVM 全栈应用镜像：把 6 个 Spring Boot 服务装进同一个 JVM
#
# 构建上下文 = deploy/allinone/build/allinone（由 scripts/build-apps.ps1 暂存，只有 6 个 jar + 配置 + 启动器）：
#   appjars/<name>.jar                       6 个服务的 fat jar（构建期解包）
#   config/<name>.json                       每个服务的 spring.application.json 覆盖
#   all-in-one-launcher.jar                  启动器 + 解包器（javac 编出来的两个类）
#
# 解包放在镜像构建期（moon.allinone.Unzip），目标机不需要 unzip/jar/Maven，只需要 docker load。
#
# 默认基座用带 ffmpeg 的那份（moon-agent 长视频拼接要 ffmpeg/ffprobe）。
# 想换更小的镜像：--build-arg BASE_IMAGE=aistoryboard/java-base:<tag>（同时不要再拼视频）。
ARG BASE_IMAGE=aistoryboard/java-base-ffmpeg:canvas-mailbox-20261013

# ── 阶段 1：解包 6 个 fat jar ────────────────────────────────────────────
FROM ${BASE_IMAGE} AS unpack
WORKDIR /unpack
COPY all-in-one-launcher.jar /unpack/all-in-one-launcher.jar
COPY appjars/ /unpack/appjars/
RUN java -cp /unpack/all-in-one-launcher.jar moon.allinone.Unzip /unpack/appjars /unpack/services \
    && rm -rf /unpack/appjars

# ── 阶段 2：运行镜像 ────────────────────────────────────────────────────
FROM ${BASE_IMAGE}

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    HOME=/home/app \
    APP_HOME=/opt/allinone

RUN set -eux; \
    groupadd -g 1001 app 2>/dev/null || true; \
    useradd -u 1001 -g app -m -d /home/app -s /usr/sbin/nologin app 2>/dev/null || true; \
    mkdir -p /home/app/logs/nacos; \
    chown -R app:app /home/app

WORKDIR /opt/allinone
# 注意：这里绝对不能对 /opt/allinone 做 chown -R —— 那会给整个 services 树再压一层
# 700MB+ 的 copy-on-write 层（镜像直接从 1.8G 涨到 2.6G）。服务树只读，root 属主即可。
COPY --from=unpack /unpack/services/ /opt/allinone/services/
COPY config/ /opt/allinone/config/
COPY all-in-one-launcher.jar /opt/allinone/all-in-one-launcher.jar

# /data/uploads 是主后端 / 画布 / moon-agent 共用的上传根目录（同一个容器，天然共享）。
# logs/ 要可写：llm-gateway 与主后端的 logback-spring.xml 里 LOG_DIR=logs（相对 CWD=/opt/allinone），
# 不给写权限的话启动会刷 RollingFileAppender 建目录失败（不影响功能但很吵，且丢文件日志）。
RUN mkdir -p /data/uploads /data/agent-workspace /opt/allinone/logs \
    && chown -R app:app /data /opt/allinone/logs

USER app

# 6 个服务各自的端口都在同一个容器里
EXPOSE 8080 8082 8083 8084 8085 9090

# 探活：入口网关(8080) + 模型网关(8083) 都已在监听。
# start-period 给足：2 核机器上 6 个 Spring 上下文串行启动需要 1~3 分钟。
HEALTHCHECK --interval=15s --timeout=5s --start-period=240s --retries=20 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && exec 4<>/dev/tcp/127.0.0.1/8083' || exit 1

# JVM 参数走 JAVA_TOOL_OPTIONS（compose 注入，便于不改镜像调内存）
ENTRYPOINT ["java", "-cp", "/opt/allinone/all-in-one-launcher.jar", "moon.allinone.AllInOneLauncher"]
