FROM nginx:alpine
COPY index.html style.css app.js config.js.template /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh
EXPOSE 80
# 127.0.0.1, not localhost: busybox wget on Alpine resolves "localhost" to the
# IPv6 loopback (::1) first, and nginx here only binds IPv4 - "localhost" gets
# a connection-refused before wget ever tries the IPv4 address, marking the
# container unhealthy even though it serves every real request fine.
HEALTHCHECK --interval=10s --timeout=5s --retries=10 --start-period=10s \
    CMD wget -qO- http://127.0.0.1/ || exit 1
ENTRYPOINT ["/docker-entrypoint.sh"]
