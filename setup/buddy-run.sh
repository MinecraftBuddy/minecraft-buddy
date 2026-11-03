#!/bin/bash
# The buddy's game without a window: what supervise.py runs in the container. Reads ~/buddy-game.json (the panel's
# Settings, Game, writes it; panel/game.py has the defaults): HeadlessMC in game_dir launches Minecraft with its loader,
# straight onto the server. HeadlessMC stays in the foreground for as long as the game runs, so the supervisor sees it.
set -euo pipefail
eval "$(python3 - <<'PY'
import json, os, shlex
d = {"game_dir": "~/game", "minecraft": "26.3", "loader": "fabric", "server": "", "memory": "2G", "offline": False}
try:
    d.update(json.load(open(os.path.expanduser("~/buddy-game.json"))))
except (OSError, ValueError):
    pass
for k in ("game_dir", "minecraft", "loader", "server", "memory", "offline"):
    v = os.path.abspath(os.path.expanduser(d[k])) if k == "game_dir" else ("--offline" if d[k] else "") if k == "offline" else str(d[k])
    print("%s=%s" % (k.upper(), shlex.quote(v)))
PY
)"
[ -f "$GAME_DIR/headlessmc.jar" ] || { echo "no game in $GAME_DIR: install it from the panel (Settings, Game)"; exit 1; }
[ -n "$SERVER" ] || { echo "no server: fill it in in the panel (Settings, Game)"; exit 1; }
export XDG_CONFIG_HOME=$GAME_DIR/config XDG_DATA_HOME=$GAME_DIR/data XDG_CACHE_HOME=$GAME_DIR/cache XDG_STATE_HOME=$GAME_DIR/state
cd "$GAME_DIR"
# OFFLINE is --offline on a test machine without the buddy's account (an offline-mode server only), empty otherwise
# the game's own user.home is $HOME (the mod's exchange folder lies under it): in a container that runs as a uid without
# an entry in its passwd (a home of its own, user: in compose.yaml), Java would not know a home at all
exec java -Duser.home="$GAME_DIR/home" -jar headlessmc.jar launch -lwjgl -eula $OFFLINE -j "-Xmx$MEMORY -Duser.home=$HOME" --server "$SERVER" "$LOADER" "$MINECRAFT"
