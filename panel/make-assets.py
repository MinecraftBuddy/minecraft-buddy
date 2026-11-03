#!/usr/bin/env python3
"""The panel's English names and effect icons, from Minecraft's client jar (standard library only).

    make-assets.py <client.jar> <dir>

Writes <dir>/names.json (the game's English names of items, blocks, enchantments, effects and potions, by id) and
<dir>/effect/<id>.png (the effect icons as the game draws them in the inventory). The item icons are made by
make-icons.py, which needs Pillow and numpy. Run both again for a new Minecraft version.
"""
import json, os, sys, zipfile

jar = zipfile.ZipFile(sys.argv[1])
out = sys.argv[2]
lang = json.loads(jar.read("assets/minecraft/lang/en_us.json"))
names = {"item": {}, "ench": {}, "effect": {}, "potion": {}, "level": {}}
for k, v in lang.items():
    parts = k.split(".")
    if len(parts) == 3 and parts[0] in ("block", "item") and parts[1] == "minecraft":
        if parts[0] == "item" or parts[2] not in names["item"]:
            names["item"][parts[2]] = v
    elif len(parts) == 5 and parts[0] == "item" and parts[3] == "effect":
        names["potion"][parts[2] + "." + parts[4]] = v          # potion.swiftness: "Potion of Swiftness"
    elif len(parts) == 3 and parts[0] == "enchantment" and parts[1] == "minecraft":
        names["ench"][parts[2]] = v
    elif len(parts) == 3 and parts[0] == "effect" and parts[1] == "minecraft":
        names["effect"][parts[2]] = v
    elif len(parts) == 3 and k.startswith("enchantment.level."):
        names["level"][parts[2]] = v
os.makedirs(os.path.join(out, "effect"), exist_ok=True)
with open(os.path.join(out, "names.json"), "w", encoding="utf-8") as f:
    json.dump(names, f, ensure_ascii=False, separators=(",", ":"))
n = 0
for p in jar.namelist():
    if p.startswith("assets/minecraft/textures/mob_effect/") and p.endswith(".png"):
        with open(os.path.join(out, "effect", os.path.basename(p)), "wb") as f:
            f.write(jar.read(p))
        n += 1
print(f"{sum(len(v) for v in names.values())} names, {n} effect icons")
