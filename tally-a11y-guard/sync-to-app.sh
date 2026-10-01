#!/bin/sh
# Copy the canonical module files into the app's assets, so the app can install the
# guard itself without the user flashing anything.
#
# The module directory is the single source of truth. Never edit the copies under
# app/src/main/assets/keepalive/ by hand - run this instead.
#
#   ./sync-to-app.sh

set -e
cd "$(dirname "$0")"

DEST="../app/src/main/assets/keepalive"

rm -rf "$DEST"
mkdir -p "$DEST"

cp module.prop "$DEST/module.prop"
cp service.sh  "$DEST/service.sh"
cp bin/guard.sh    "$DEST/guard.sh"
cp bin/watchdog.sh "$DEST/watchdog.sh"

chmod 644 "$DEST/module.prop"
chmod 755 "$DEST/service.sh" "$DEST/guard.sh" "$DEST/watchdog.sh"

echo "synced into $DEST:"
ls -l "$DEST"
