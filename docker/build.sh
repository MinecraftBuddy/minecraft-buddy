#!/bin/bash
# The image on this machine: the Gradle build, its jars and version in build/image/, then docker build.
#   bash docker/build.sh [tag]      (tag: minecraft-buddy:dev if left out)
set -euo pipefail
cd "$(dirname "$0")/.."
TAG=${1:-minecraft-buddy:dev}
MC=${MC:-26.3}
./gradlew --no-daemon -q build -Pmc="$MC"
bash docker/stage.sh "$MC"
docker build -t "$TAG" .
echo "built $TAG ($(cat build/image/VERSION))"
