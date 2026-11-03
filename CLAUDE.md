# Minecraft Buddy — instructions for Claude

A client-side Fabric mod (Minecraft 26.3, Mojang mappings, JDK 25) that plays a second account as a companion, a web
panel and a Telegram bridge around it, and a Docker image that runs them together. The README says what it does;
`docs/` holds the detail per subject, and a change of behaviour goes into the doc of its subject.

@.private/CLAUDE.md

## This repository is public

- **Nothing personal goes into git**: no names of real players or accounts, no addresses, domains or paths of anyone's
  machines, no IP or mail addresses, no logs, chat or screenshots of a real server, no quotes of people. Examples use
  Steve and Alex, `example.net` and the documentation addresses (`203.0.113.x`). This holds for files, commit messages,
  branch names, release notes, pull requests and issues alike.
- **Anything that may be for the maintainer's own use or may hold sensitive data goes into git only after asking the
  maintainer in so many words, with the reason.** The default is no: it goes into `.private/`, which is never committed.
- `python3 tools/check-public.py` before a commit (the hooks in `.githooks/` do it, with the private denylist in
  `.private/denylist.txt` when it exists); the workflow `privacy` runs it without the denylist on every push.
- Never take a commit that only came from GitHub (a pull request, a push by someone else) into a local branch without
  showing it to the maintainer first; `.githooks/reference-transaction` refuses it, `GIT_ACCEPT_REMOTE=1` overrides it.

## Working rules

- English in code, comments, docs and commits. A comment says what the code does and why, not who asked for it.
- Nothing is hardcoded for one player, server or machine: names, servers, coordinates and accounts are settings
  (`buddy.json`, the panel); what only one server has is a module (`modules/`).
- Build and test with `./gradlew build -Pmc=26.3`; see `docs/development.md`. A change that should reach the buddies gets
  a new version in `gradle.properties`: a push to master releases that version and the panel offers it as an update.
  The same commit gives the version its section in `CHANGELOG.md`, the release's text: one short item per thing a user
  notices (new, changed, fixed), in the style below; no file names, no internals.

## Writing texts

What the panel, the Telegram bridge, the docs and the mod's messages say. Agreed with the maintainer on twenty examples
(3 Nov 2026). Keep a text that is already clear; change only what breaks one of these.

- Say what something does, or what to type, once. Short sentences; a list where there are cases. Drop words that add
  nothing ("cleanly", "Saved changes", "as themselves", "If there is one").
- Lead with the action, then the result: "Set the base: with Steve within six blocks, tap a bed with an empty hand. Steve
  walks there and makes it home."
- The buddy has no pronoun: not he, she, they or it. Panel and bridge texts use the buddy's name (the panel's `nm()`, the
  bridge's `named()`); docs and code comments say "the buddy" or are written without a subject. Plural they for players
  or mobs is fine; one player is "the player" or "that player".
- Leave out what a user does not need: file names, mod versions, the machine's name, a detail nobody notices.
- Say behaviour a user would not guess: Leave keeps the buddy off until Join; after a kick or a lost connection the buddy
  joins again automatically.
- Units players know: game time as on a clock (18:31), not ticks; seconds and minutes, not milliseconds.
- Keys as the game's controls screen names them: left click, E inventory, Tab player list.
- Several commands in one line: `![cropfarm|farmmob|farmraid] status`.
