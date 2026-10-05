#!/usr/bin/env bash
# Builds the bot and uploads the jar to the hosting server via SFTP (key-based, no password).
# Usage: ./deploy.sh [--skip-build]
set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -f deploy.env ]]; then
    echo "Missing deploy.env: copy deploy.env.example and fill in the SFTP details from the panel's Settings tab." >&2
    exit 1
fi
source deploy.env
: "${SFTP_HOST:?SFTP_HOST is not set in deploy.env}"
: "${SFTP_PORT:?SFTP_PORT is not set in deploy.env}"
: "${SFTP_USER:?SFTP_USER is not set in deploy.env}"
SFTP_KEY="${SFTP_KEY:-$HOME/.ssh/waifly_ygo_bot}"
JAR=ygo-discord-bot.jar

if [[ "${1:-}" != "--skip-build" ]]; then
    echo "Building..."
    mvn -q clean package
fi

echo "Uploading target/$JAR to $SFTP_USER@$SFTP_HOST:$SFTP_PORT ..."
# Upload under a temporary name first, so the server never sees a half-written jar.
sftp -i "$SFTP_KEY" -P "$SFTP_PORT" \
    -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=accept-new \
    "$SFTP_USER@$SFTP_HOST" <<SFTP
put target/$JAR $JAR.uploading
-rm $JAR
rename $JAR.uploading $JAR
ls -l $JAR
SFTP

echo "Done. Restart the server in the panel to run the new version."
