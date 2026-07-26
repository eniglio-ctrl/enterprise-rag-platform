FROM nginx:alpine
COPY index.html style.css app.js /usr/share/nginx/html/
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=5s --retries=10 --start-period=10s \
    CMD wget -qO- http://localhost/ || exit 1
