#!/bin/sh
set -eu

raw_allowed="${SANDBOX_ALLOWED_DOMAINS:-}"
allowed="$(printf '%s' "$raw_allowed" | tr ',' ' ' | tr -s '[:space:]' ' ' | sed 's/^ //; s/ $//')"

if [ -z "$allowed" ]; then
    echo "SANDBOX_ALLOWED_DOMAINS must contain at least one domain" >&2
    exit 1
fi

export SANDBOX_ALLOWED_DOMAINS="$allowed"

umask 077
envsubst '${SANDBOX_ALLOWED_DOMAINS}' \
    < /etc/moon-sandbox/squid.conf.template \
    > /tmp/squid.conf

exec /usr/sbin/squid -N -f /tmp/squid.conf
