# Changes

What is new and what changed, per version. The section of a version is the text of its
[release](https://github.com/MinecraftBuddy/minecraft-buddy/releases).

## 0.5.14

- **One Watchtower per buddy.** An Update in a panel replaces only that buddy; the other buddies on the machine keep
  running on their version. A `compose.yaml` from before 0.5.14 needs one change:
  [Updates](https://github.com/MinecraftBuddy/minecraft-buddy/blob/master/docs/install.md#updates).

## 0.5.4

- **Plainer texts.** The panel, the Telegram bridge, the mod and the docs say each thing once and shorter. The panel
  and the bridge call the buddy by name.
- **Sleep from** in a server profile shows a time on a clock (18:32) instead of ticks.
- **Leave and Join** say what they do: Leave keeps the buddy off until Join. After a kick or a lost connection the
  buddy joins again in about half a minute.
- The mod's error messages name what failed instead of a file.

## 0.5.3

- **Inventory while watching.** Take control has an Inventory button while you watch (before Play, and in Around and
  Fly): the inventory with the armour, in a card at the top right. Click it again to close it. While playing, E
  inventory works as before.
- **Faster flying** in the 3D view: 20 blocks a second, 60 with Ctrl.
- The version stands under the header's buttons.

## 0.5.2

- **The version in the header.** The panel shows the buddy's version at all times. When a newer release is out, the
  version turns copper with an arrow: click it to update, after a question. Otherwise a click opens Settings, Status.

## 0.5.1

- **A new login after an update.** When Minecraft refuses the stored login after an update, the buddy fetches a new
  one, at most once an hour. Before, the buddy stayed offline until the login was reset by hand.
- When Minecraft asks the buddy to slow down at a start, the next start waits five minutes instead of twenty seconds.

## 0.5.0

- The first public release.
