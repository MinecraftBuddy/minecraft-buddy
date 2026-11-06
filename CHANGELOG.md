# Changes

What is new and what changed in each version. Each section is also the text of that version's
[release](https://github.com/MinecraftBuddy/minecraft-buddy/releases).

## 0.5.22

- The panel's colours now stay apart with red/green colour blindness: online, running and done are blue instead of
  green, a warning in the Telegram settings is yellow, and on the map a chest is yellow and a field cyan.

## 0.5.21

- **Walk there** while the buddy sits: the buddy rides on as long as that gets closer, then walks the rest. A boat goes
  over the water to the shore; a horse, a pig with its stick or a happy ghast is steered there; a minecart follows its
  rails. From a cushion or a bed the buddy gets up first.

## 0.5.20

- Browsers and password managers now fill in only the panel's password, no other field.
- When someone who is not in Trusted players writes to the bot in Telegram, the panel's chat says so, with that
  person's Telegram user id. If the bot does not answer you, check your own id there: a password manager may have
  put a login name in it.
- On a computer the chat stays in view while you scroll the page, so typing in it no longer makes the page jump,
  also with a tall menu open.

## 0.5.19

- When the buddy dies, Take control shows the coordinates where the buddy's things are.
- **Deaths and advancements in Telegram**, each with a switch of their own. The game's own lines are known on any server;
  where a plugin writes its own, the server profile's new texts death and advancement match them.
- When the buddy dies, Telegram says where.
- After a kick or a lost connection, Telegram says so with the server's reason, and when the buddy joins again.

## 0.5.18

- **Chat from outside the game in Telegram.** A server profile can now say how lines the server passes on from IRC or
  Discord look (Lines the server sends: `relay`). Those lines then come into Telegram with players' chat, as the server
  shows them, also when other server messages are off.

## 0.5.17

- In Take control, new chat lines now sit at the bottom left, level with the block name at the bottom right.

## 0.5.16

- **No more interruptions while watching.** In Take control the 3D view no longer greys out and rebuilds every few
  seconds. When the world is refreshed while you watch, it happens in place, without the loading bar.
- The 3D view's loading messages are worded more clearly.

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
