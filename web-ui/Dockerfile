FROM nginx:alpine
COPY index.html style.css app.js config.js.template /usr/share/nginx/html/
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=5s --retries=10 --start-period=10s \
    CMD wget -qO- http://localhost/ || exit 1
ENTRYPOINT ["/docker-entrypoint.sh"]
