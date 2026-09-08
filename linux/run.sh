#!/bin/sh
# Resolve relative to this launcher, independent of the caller's working directory.
set -eu
MUSICVAULT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -d "$MUSICVAULT_ROOT/../share/musicvault/musicvault" ]; then
    MUSICVAULT_ROOT=$(CDPATH= cd -- "$MUSICVAULT_ROOT/../share/musicvault" && pwd)
fi
export PYTHONPATH="$MUSICVAULT_ROOT${PYTHONPATH:+:$PYTHONPATH}"
exec "${MUSICVAULT_PYTHON:-/usr/bin/python3}" -m musicvault "$@"
