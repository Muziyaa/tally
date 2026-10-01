#!/bin/sh
# Package this directory into a flashable Magisk module zip.
#
# Magisk requires the module files at the ZIP ROOT (no top-level folder), so we zip
# the contents from inside the module directory.
#
#   ./build.sh          -> tally-a11y-guard-<version>.zip
#
# Install: Magisk app -> Modules -> Install from storage.

set -e
cd "$(dirname "$0")"

VERSION=$(sed -n 's/^version=//p' module.prop)
[ -n "$VERSION" ] || { echo "could not read version from module.prop" >&2; exit 1; }

OUT="tally-a11y-guard-${VERSION}.zip"
rm -f "$OUT"

zip -r -X "$OUT" \
    module.prop \
    service.sh \
    uninstall.sh \
    config.example \
    bin \
    -x '.*' '*/.*' >/dev/null

echo "built: $(pwd)/$OUT"
unzip -l "$OUT"
