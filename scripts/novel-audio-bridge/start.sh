#!/bin/sh
set -eu
bridge_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec python3 "$bridge_dir/server.py" "$@"
