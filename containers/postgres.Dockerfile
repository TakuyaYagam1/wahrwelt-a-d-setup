FROM postgres:alpine

# su-exec заменяет встроенный gosu, собранный со старой версией Go.
RUN apk upgrade --no-cache \
    && apk add --no-cache su-exec \
    && sed -i 's/exec gosu postgres /exec su-exec postgres /' /usr/local/bin/docker-entrypoint.sh \
    && rm /usr/local/bin/gosu
