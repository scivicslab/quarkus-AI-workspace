#!/bin/bash
# Download the latest quarkus-AI-workspace JAR (if not already present) and start it.
set -e
cd "$(dirname "$0")"

REPO="scivicslab/quarkus-AI-workspace"
API="https://api.github.com/repos/$REPO/releases/latest"

JAR_URL=$(python3 -c "
import sys, json, urllib.request
d = json.loads(urllib.request.urlopen('$API').read())
print([a['browser_download_url'] for a in d['assets'] if a['name'].endswith('.jar')][0])
")

JAR=$(basename "$JAR_URL")

if [ ! -f "$JAR" ]; then
    echo "Downloading $JAR ..."
    curl -LO "$JAR_URL"
fi

exec java -jar "$JAR"
