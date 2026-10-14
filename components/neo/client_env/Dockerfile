FROM golang:alpine AS build

ENV CGO_ENABLED=0 GOTOOLCHAIN=local GOMAXPROCS=2
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY cmd/client ./cmd/client
COPY internal ./internal
COPY proto/go ./proto/go
COPY pkg ./pkg
RUN go build -p=2 -trimpath -buildvcs=false -ldflags="-s -w" -o /out/neo ./cmd/client

FROM python:alpine

ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HOME=/work

RUN apk --no-cache upgrade \
    && apk --no-cache add bash \
    && addgroup -S -g 10001 neo \
    && adduser -S -D -H -u 10001 -G neo -h /work -s /sbin/nologin neo \
    && install -d -o 10001 -g 10001 -m 0700 /work /work/exploits

COPY client_env/requirements.txt /tmp/requirements.txt
RUN python -m pip install --no-cache-dir --upgrade -r /tmp/requirements.txt \
    && python -m pip check \
    && python -m pip uninstall --yes pip setuptools \
    && rm -f /usr/local/bin/pip /usr/local/bin/pip3 /usr/local/bin/pip3.* \
    && rm -rf /usr/local/lib/python*/ensurepip /usr/local/lib/python*/__pycache__/ensurepip* \
    && rm /tmp/requirements.txt

COPY --from=build /out/neo /usr/local/bin/neo
COPY --chmod=0555 client_env/entrypoint.sh /entrypoint.sh
COPY configs/client/config.yml /etc/neo/client_config.yml

COPY --from=project /LICENSE /usr/share/licenses/wahrwelt/LICENSE

USER 10001:10001
WORKDIR /work
ENTRYPOINT ["/entrypoint.sh"]
CMD ["/usr/local/bin/neo", "--config", "/etc/neo/client_config.yml", "run", "--jobs", "2", "--endless-jobs", "0"]
