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
RUN chmod 0755 /usr/local/bin/moon-sandbox-egress-proxy

USER proxy
EXPOSE 3128
ENTRYPOINT ["/usr/local/bin/moon-sandbox-egress-proxy"]
