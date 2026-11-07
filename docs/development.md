# Building and testing

Building the mod, trying a new jar, reading the buddy's logs, and contributing.

## Build and test

```bash
./gradlew build -Pmc=26.3    # the mod and its modules, with the tests; the jars land in build/libs and modules/*/build/libs
./gradlew test -Pmc=26.3     # the tests alone
bash docker/build.sh         # the Docker image, as minecraft-buddy:dev
```

JDK 25. A new jar works after a restart of the buddy's game.

## Trying a jar before it is released

Put `BUDDY_JARS: folder` in a buddy's environment in `compose.yaml`: the game then uses the jars in that buddy's mods
folder, not the image's. `docker compose exec <name> python3 /opt/buddy/panel/game.py path mods` names the folder; put
the jar there and restart the game with `... game.py restart`. Without `BUDDY_JARS: folder` the image's own jars are
put back at the next start.

The folder's jars stay only while they are not older than the image's. A newer image, such as after an Update, puts its
own jars back at the game's next start, so an update never leaves the mod behind. A jar built on the image's version
(`0.5.22-obtain.3` beside image 0.5.22) stays.

Try behaviour on a server of your own first (offline mode lets a scripted player join): `game.py server <address>`
takes the buddy there and back.

## Reading what the buddy did

With `keepEvents` on, the mod keeps every session's `events.jsonl` gzipped in `events-archive/` of the exchange folder.
`tools/buddylog.py` reads it all as one timeline:

```bash
python3 tools/buddylog.py show --from 14:00 --to 14:30      # a stretch of time
python3 tools/buddylog.py where --at 14:05                  # one moment
python3 tools/buddylog.py scan --day yesterday              # what went wrong: deaths, damage, getting stuck, the same
                                                            # failure again and again, gates left open, refused beds
```

`--dir` names the exchange folder when it is not the default.

## Releases

A push to `master` builds and tests the mod, puts the jars on the GitHub release of the version in `gradle.properties`
(made when the version is new), and pushes the image to `ghcr.io/minecraftbuddy/minecraft-buddy` as `latest` and as that
version. The panel compares its own version with the newest release and offers the update. So a change that should
reach the buddies gets a new version in `gradle.properties`.

The text of the release is the version's section in [CHANGELOG.md](../CHANGELOG.md), written in the commit that raises
the version: one short item for each thing a user notices. Every build of master sets it again, so a correction reaches
the release with the next build (a push of only `CHANGELOG.md` builds nothing; `gh release edit` sets it at once).

Work goes on in branches beside each other, each in its own worktree:

- **A branch for master** (a panel feature, a fix) leaves `version=` alone. The version goes up, by one patch step,
  in the commit that brings the branch onto master. Two branches done at the same time then take two numbers.
- **A branch with a tester of its own** (`obtain`, built for the practice rig's trainee) never changes `version=`
  either. It counts its builds in `branch.properties`, a file only that branch has: `build=obtain.3` makes
  `0.5.14-obtain.3`, the third build on top of 0.5.14. After a release, merge master into the branch and count from 1
  again. Merging the branch into master removes `branch.properties` and raises `version=`.
- A push of any branch other than master builds no release.

## Contributing

Issues and pull requests are welcome. The code is under the GNU Affero General Public License 3.0 or later
([LICENSE](../LICENSE)): whoever runs a changed version, also as a service, makes the changed source available.

Nothing personal goes into this repository: no names of real players or accounts, no addresses or domains of your own
machines, no IP or mail addresses, no logs or chat of a real server. Use Steve and Alex, `example.net` and the
documentation addresses (`203.0.113.x`). `tools/check-public.py` looks for secrets, mail addresses, public IP addresses
and home folders; the workflow `privacy` runs it on every push and pull request. To run it before every commit, copy the
hooks in `.githooks/` into `.git/hooks/`, with your own list of names to keep out in `.private/denylist.txt` (one
regular expression per line; `.private/` is never committed).

← [Back to the README](../README.md)
