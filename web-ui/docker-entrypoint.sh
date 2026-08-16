#!/bin/sh
set -e

: "${DEMO_MODE:=false}"
: "${RAG_BASE_URL:=http://localhost:8082}"
export DEMO_MODE RAG_BASE_URL

envsubst '${DEMO_MODE} ${RAG_BASE_URL}' \
    < /usr/share/nginx/html/config.js.template \
    > /usr/share/nginx/html/config.js

exec nginx -g "daemon off;"
