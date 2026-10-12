#!/bin/sh
# Starts the droid_codology server (Linux, macOS, Termux). Usage: sh start-server.sh
# Settings come from environment variables, see README.md; for example:
#   DC_PORT=8080 DC_PUBLIC_URL=https://dc.example.com sh start-server.sh
cd "$(dirname "$0")" || exit 1
# The first Python that is new enough (3.9+), not just the first one found.
for py in python3 python3.13 python3.12 python3.11 python3.10 python3.9 python; do
    if command -v "$py" >/dev/null 2>&1 && "$py" -c 'import sys; sys.exit(sys.version_info < (3, 9))' 2>/dev/null; then
        exec "$py" droidcodology_server.py "$@"
    fi
done
echo "Python 3.9 or newer is not installed. Install it:" >&2
echo "  Termux (Android):  pkg install python" >&2
echo "  Debian/Ubuntu:     sudo apt install python3" >&2
echo "  macOS:             https://www.python.org/downloads/" >&2
exit 1
