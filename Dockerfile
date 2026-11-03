# The buddy as one image: the panel, the Telegram bridge and the game, with the buddy's jars of the same
# commit. .github/workflows/build.yml builds it for every push to master (ghcr.io/minecraftbuddy/minecraft-buddy);
# docker/compose.yaml runs every buddy of a machine behind one Caddy, with a Watchtower per buddy for its panel's update button, and
# the image hands that file out itself:
#   docker run --rm ghcr.io/minecraftbuddy/minecraft-buddy compose > compose.yaml
#
# The jars come from the Gradle build beside it: docker/build.sh puts them and the version in build/image/ first (the
# workflow does the same), so the image never builds the mod itself.
FROM eclipse-temurin:25.0.5_8-jre-noble

# Python for the panel and the bridge, Pillow and numpy for the panel's pictures of the game, tini as the container's init
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-pil python3-numpy tini ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && (userdel --remove ubuntu 2>/dev/null || true) \
 && useradd --uid 1000 --user-group --create-home --home-dir /data --shell /bin/bash minecraft-buddy

COPY panel /opt/buddy/panel
COPY bridge /opt/buddy/bridge
COPY setup /opt/buddy/setup
COPY docker /opt/buddy/docker
COPY build/image/ /opt/buddy/

# everything the buddy keeps lies in /data: the game, the account, the settings, the exchange folder, the panel's state
ENV HOME=/data BUDDY_DOCKER=1 BUDDY_IMAGE_DIR=/opt/buddy BUDDY_PANEL_DATA=/data/panel PYTHONDONTWRITEBYTECODE=1 LANG=C.UTF-8
USER minecraft-buddy
WORKDIR /data
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=60s --timeout=10s --start-period=60s \
  CMD python3 -c "import os, urllib.request; urllib.request.urlopen('http://127.0.0.1:%s/api/hello' % os.environ.get('BUDDY_PANEL_PORT', '8080'), timeout=8)"
ENTRYPOINT ["tini", "--", "python3", "/opt/buddy/setup/supervise.py"]
