#!/bin/bash
# What the image takes of the Gradle build: the buddy's jars for one Minecraft version (not the -sources jars) and the
# version, in build/image/ (the Dockerfile copies that folder to /opt/buddy). Used by docker/build.sh and the workflow.
set -euo pipefail
cd "$(dirname "$0")/.."
MC=${1:-26.3}
v=$(grep '^version=' gradle.properties | cut -d= -f2)
rm -rf build/image && mkdir -p build/image/jars
for j in build/libs/minecraft-buddy-"$v"+mc"$MC".jar modules/*/build/libs/minecraft-buddy-*-"$v"+mc"$MC".jar; do
  [ -f "$j" ] || { echo "missing $j: build first"; exit 1; }
  cp "$j" build/image/jars/
done
echo "$v" > build/image/VERSION
ls build/image/jars
