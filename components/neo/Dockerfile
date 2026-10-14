FROM golang:alpine AS build

ENV CGO_ENABLED=0 GOTOOLCHAIN=local GOMAXPROCS=2
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY cmd/server ./cmd/server
COPY internal ./internal
COPY proto/go ./proto/go
COPY pkg ./pkg
RUN go build -p=2 -trimpath -buildvcs=false -ldflags="-s -w" -o /out/neo-server ./cmd/server

FROM alpine:latest

RUN apk upgrade --no-cache \
    && apk add --no-cache ca-certificates \
    && addgroup -S -g 10001 neo \
    && adduser -S -D -H -u 10001 -G neo neo \
    && install -d -o 10001 -g 10001 -m 0700 /data
COPY --from=build --chown=10001:10001 /out/neo-server /usr/local/bin/neo-server
COPY configs/server/config.yml /etc/neo/server_config.yml

COPY --from=project /LICENSE /usr/share/licenses/wahrwelt/LICENSE

USER 10001:10001
WORKDIR /data
EXPOSE 5005
ENTRYPOINT ["/usr/local/bin/neo-server"]
CMD ["--config", "/etc/neo/server_config.yml"]
