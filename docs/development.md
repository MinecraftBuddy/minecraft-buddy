# Building and testing

Building the mod, trying a new jar, reading the buddy's logs, and contributing.

## Build and test

```bash
./gradlew build -Pmc=26.3    # the mod and its modules, with the tests; the jars land in build/libs and modules/*/build/libs
./gradlew test -Pmc=26.3     # the tests alone
bash docker/build.sh         # the Docker image, as minecraft-buddy:dev
```

JDK 25. A new jar works after a restart of the buddy's game.

### The autopilot

`autopilot/` is the code that plays the game for an order: Baritone's pathfinder and AltoClef's tasks, brought to
26.3 and changed for the buddy. It is LGPL-3.0 (its `NOTICE` says where it comes from), the rest of the
repository AGPL-3.0. It is a Gradle build of its own, with its own wrapper; `./gradlew build` runs it first (the task
`autopilot`) and compiles the mod against its jar, `autopilot/fabric/build/libs/soprano-unoptimized-fabric-1.1.6.jar`.
That jar goes into the mods folder beside the mod's; without it an order uses the buddy's own planner. Its tests:

```bash
cd autopilot && ./gradlew test    # about 2,000 tests, a few minutes
```

### Snapshot regression tests

The autopilot can keep the 30 seconds before a death in the nether, so that every build is tried on the situations that
killed the buddy. With `snapshots=1` in the autopilot's `tuning.properties` (see `Tuning.java`), while an order runs in
the nether, it writes one gzip JSON file per death, and one a few seconds after the hp drops below 6, into the game
folder's `logs/snapshots/` (the system property `buddy.snapshots` names another folder). The folder keeps the newest 200.
A file has a frame every half second and one at every decision: the buddy, what lives near, a box of blocks round the
buddy, what the autopilot decided and the cells it chose (`SnapshotWriter` describes the format). Off, nothing is kept.

`SnapshotReplayTest` rebuilds every frame as cells, runs the planners of the current build on it and checks the
invariants in `SnapshotChecks`, one line per violation, in `autopilot/build/snapshots-report.txt` as well:

```bash
cd autopilot && SNAPSHOTS=<folder> ./gradlew test --tests '*SnapshotReplayTest' --rerun
```

The folder is searched all the way down. Without `SNAPSHOTS` the test is skipped. `SNAPSHOT_BASELINE=<file>` with
`SNAPSHOT_BASELINE_WRITE=1` accepts the violations found now; later runs with the same baseline fail only on new ones.

## Trying a jar before it is released

Put `BUDDY_JARS: folder` in a buddy's environment in `compose.yaml`: the game then uses the jars in that buddy's mods
folder, not the image's. `docker compose exec <name> python3 /opt/buddy/panel/game.py path mods` names the folder; put
the jar there and restart the game with `... game.py restart`. Without `BUDDY_JARS: folder` the image's own jars are
put back at the next start.

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

## Drills on a practice rig

A practice rig (a test server and a trainee buddy that run one scenario over and over) can show its drill block in the
panel. The rig writes `drills.json` into the buddy's exchange folder, beside `state.json`; the panel reads it through
`/api/drills`. While you watch in Take control, the Drills button shows it above the timeline. The button is there while
a block runs and for fifteen minutes after; a file not written for five minutes counts as none.

```json
{"at": 1793810230520, "on": true, "run": 3, "runs": 8, "ended": null, "queued": 1,
 "blocks": [{"name": "blazes-corner", "about": "what this block tests against the one before", "item": "rod",
             "runs": [{"n": 1, "end": "pass", "cause": "", "min": 10.9, "have": 10}]}]}
```

- `at`, `ended`: when the file was written and when the last block ended, in milliseconds.
- `run` of `runs`: the run under way while `on`; `queued`: blocks still waiting.
- `blocks`: the block under way (or the last one) first, then the ones before it.
- A run's `end` is `pass`, `died` (with `cause`), `time`, `stall` or `setup`; `min` is game minutes; `have` counts the
  block's `item`.

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
