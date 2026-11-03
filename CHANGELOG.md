# Changes

What is new and what changed in each version. Each section is also the text of that version's
[release](https://github.com/MinecraftBuddy/minecraft-buddy/releases).

## 0.5.15

- **A loading bar in the 3D view.** Take control and the 3D map show how far loading has got, from the click until
  the world is in view.
- **Coming back to the page** after a while (another tab or app in front, the computer asleep): the old 3D view is
  greyed out under the bar until it has caught up. The rest of the page reloads straight away.
- The 3D view opens faster: it builds the current world once, instead of an older copy first.
- When there is no world to show yet (the buddy is offline), the 3D view keeps trying on its own.

## 0.5.14

- **One Watchtower per buddy.** Updating from a panel now replaces only that buddy; the other buddies on the machine
  keep their version. A `compose.yaml` from before 0.5.14 needs one change:
  [Updates](https://github.com/MinecraftBuddy/minecraft-buddy/blob/master/docs/install.md#updates).

## 0.5.4

- **Plainer texts.** The panel, the Telegram bridge, the mod and the docs are shorter and say each thing once. The
  panel and the bridge call the buddy by name.
- **Sleep from** in a server profile is shown as a clock time (18:32) instead of ticks.
- **Leave and Join** explain what they do: Leave keeps the buddy offline until Join. After a kick or a lost
  connection, the buddy rejoins automatically within about half a minute.
- The mod's error messages say what failed instead of naming a file.

## 0.5.3

- **Inventory while watching.** Take control has an Inventory button while you watch (before Play, and in Around and
  Fly). It shows the inventory and armour in a card at the top right; click it again to close it. While playing, E
  inventory opens it as before.
- **Faster flying** in the 3D view: 20 blocks a second, 60 with Ctrl.
- The version has moved below the buttons in the header.

## 0.5.2

- **Version in the header.** The panel always shows the buddy's version. When a newer release is out, it turns
  copper with an arrow: click it to update (the panel asks first). Otherwise, clicking it opens Settings, Status.

## 0.5.1

- **New login after an update.** If Minecraft rejects the saved login after an update, the buddy gets a new one
  automatically, at most once an hour. Previously the buddy stayed offline until the login was reset by hand.
- If Minecraft asks the buddy to slow down when starting, the next attempt waits five minutes instead of twenty
  seconds.

## 0.5.0

- The first public release.
