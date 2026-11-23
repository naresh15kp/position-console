FROM haproxy:3.0.10-alpine
USER root
RUN apk add --no-cache socat
COPY haproxy.cfg /usr/local/etc/haproxy/haproxy.cfg
COPY route /usr/local/bin/route
RUN sed -i 's/\r$//' /usr/local/bin/route && chmod 755 /usr/local/bin/route
USER haproxy