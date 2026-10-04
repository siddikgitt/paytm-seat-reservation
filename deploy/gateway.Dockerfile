FROM haproxy:3.2-alpine
COPY deploy/haproxy.cfg /usr/local/etc/haproxy/haproxy.cfg
ENV PORT=8080 APP_HOST=seat-reservation APP_PORT=8080 \
    HAPROXY_BUFFER_SIZE=16384 HAPROXY_BUFFER_LIMIT=0
EXPOSE 8080
