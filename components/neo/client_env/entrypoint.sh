#!/bin/sh
set -eu

if [ -z "${NEO_CLIENT_ID:-}" ]; then
    NEO_CLIENT_ID="$(hostname)"
    export NEO_CLIENT_ID
fi

exec "$@"
