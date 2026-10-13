FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        gettext-base \
        netcat-openbsd \
        squid \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /etc/moon-sandbox \
    && chown proxy:proxy /etc/moon-sandbox

COPY squid.conf.template /etc/moon-sandbox/squid.conf.template
COPY entrypoint.sh /usr/local/bin/moon-sandbox-egress-proxy
# Windows 检出可能带 CRLF；去掉 \r，否则 shebang 变成 "#!/bin/sh\r" 导致 exec 失败
RUN sed -i 's/\r$//' /usr/local/bin/moon-sandbox-egress-proxy \
    && chmod 0755 /usr/local/bin/moon-sandbox-egress-proxy

USER proxy
EXPOSE 3128
ENTRYPOINT ["/usr/local/bin/moon-sandbox-egress-proxy"]
