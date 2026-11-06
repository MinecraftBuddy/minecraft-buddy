package adris.altoclef.ui;

import adris.altoclef.TaskCatalogue;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import baritone.api.utils.Dimension;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// words for the task hud. the game's own localized names for items, blocks and mobs (so they match what the player
// sees in their language), plain group names for the long alternative lists ("logs", not forty ids), and a humanized
// class name for tasks that never bothered with a label. everything here is allowed to be called from the render
// thread once per tick, so nothing scans a registry
public final class HudText {

    private HudText() {
    }

    // ---- tasks

    // MineAndCollectTask -> "Mine and collect", MLGBucketTask -> "MLG bucket", SCP173Task -> "SCP 173". proguard keeps
    // the adris.altoclef names (-keepnames), so this is stable in release jars too
    public static String humanizeClassName(Class<?> cls) {
        Class<?> c = cls;
        String name = "";
        // anonymous classes have no simple name, the nearest named ancestor is the best we can do
        while (c != null && (name = c.getSimpleName()).isEmpty()) {
            c = c.getSuperclass();
        }
        return humanizeClassName(name);
    }

    public static String humanizeClassName(String simpleName) {
        if (simpleName == null || simpleName.isEmpty()) {
            return "Working";
        }
        String s = simpleName;
        if (s.endsWith("Task") && s.length() > 4) {
            s = s.substring(0, s.length() - 4);
        }
        StringBuilder out = new StringBuilder(s.length() + 8);
        char[] ch = s.toCharArray();
        for (int i = 0; i < ch.length; i++) {
            char c = ch[i];
            if (i > 0) {
                char prev = ch[i - 1];
                boolean upperStart = Character.isUpperCase(c) && (Character.isLowerCase(prev) || Character.isDigit(prev)
                        // the end of an acronym: "MLGBucket" splits between G and B
                        || (i + 1 < ch.length && Character.isLowerCase(ch[i + 1]) && Character.isUpperCase(prev)));
                boolean digitStart = Character.isDigit(c) && !Character.isDigit(prev);
                if (upperStart || digitStart) {
                    out.append(' ');
                }
            }
            out.append(c);
        }
        // words stay lowercase except the first one and acronyms ("MLG bucket", not "Mlg Bucket")
        String[] words = out.toString().split(" ");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w.isEmpty()) continue;
            boolean acronym = w.length() > 1 && w.chars().allMatch(x -> Character.isUpperCase(x) || Character.isDigit(x));
            if (!acronym) {
                w = i == 0 ? Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase(Locale.ROOT) : w.toLowerCase(Locale.ROOT);
            }
            if (result.length() > 0) result.append(' ');
            result.append(w);
        }
        return result.toString();
    }

    // ---- names

    public static String item(Item item) {
        if (item == null) return "something";
        try {
            return item.getDefaultInstance().getItemName().getString();
        } catch (Throwable t) {
            return ItemHelper.stripItemName(item).replace('_', ' ');
        }
    }

    public static String item(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "something";
        try {
            return stack.getHoverName().getString();
        } catch (Throwable t) {
            return item(stack.getItem());
        }
    }

    public static String block(Block block) {
        if (block == null) return "a block";
        try {
            return block.getName().getString();
        } catch (Throwable t) {
            return humanizeClassName(block.getClass());
        }
    }

    // "Zombie", or for a dropped item the item's own name ("Oak Log")
    public static String entity(Entity entity) {
        if (entity == null) return "something";
        if (entity instanceof ItemEntity dropped) {
            return item(dropped.getItem());
        }
        try {
            if (entity.hasCustomName()) {
                return entity.getCustomName().getString();
            }
            return entityType(entity.getType());
        } catch (Throwable t) {
            return humanizeClassName(entity.getClass());
        }
    }

    public static String entityType(EntityType<?> type) {
        if (type == null) return "something";
        try {
            return type.getDescription().getString();
        } catch (Throwable t) {
            return type.toShortString().replace('_', ' ');
        }
    }

    // KillEntitiesTask and friends carry entity classes, not types. "ZombieEntity" is not a thing in mojmap so the
    // class name is already the mob name, apart from the odd "AbstractSkeleton"
    public static String entityClass(Class<?> cls) {
        if (cls == null) return "something";
        String s = humanizeClassName(cls);
        if (s.startsWith("Abstract ")) {
            s = s.substring("Abstract ".length());
            s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
        return s;
    }

    public static String pos(Vec3i pos) {
        if (pos == null) return "somewhere";
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    public static String pos(BlockPos pos) {
        return pos((Vec3i) pos);
    }

    public static String dimension(Dimension dimension) {
        if (dimension == null) return "another dimension";
        return switch (dimension) {
            case OVERWORLD -> "the Overworld";
            case NETHER -> "the Nether";
            case END -> "the End";
        };
    }

    // ---- counts and plurals

    // only english gets articles and plurals, nobody wants "ein Eichenholzstamms"
    static boolean english() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.options == null || mc.options.languageCode == null) return true;
            return mc.options.languageCode.toLowerCase(Locale.ROOT).startsWith("en");
        } catch (Throwable t) {
            return true;
        }
    }

    // the last word of names we're happy to stick an s on. anything else ("Coal", "Raw Iron", "Cobblestone") reads
    // fine as "3 Coal" and reads wrong as "3 Coals", so that's the default
    private static final Set<String> COUNTABLE = new HashSet<>(Arrays.asList(
            "ingot", "nugget", "log", "stick", "block", "bucket", "pickaxe", "axe", "sword", "shovel", "hoe", "helmet",
            "chestplate", "table", "furnace", "chest", "torch", "bed", "boat", "door", "sign", "pearl", "eye", "rod",
            "shard", "diamond", "emerald", "apple", "potato", "carrot", "egg", "feather", "bone", "arrow", "bow",
            "shield", "bottle", "seed", "sapling", "ladder", "rail", "slab", "fence", "gate", "wall", "bowl", "item",
            "ore", "crystal", "ball", "skull", "head", "book", "compass", "clock", "map", "cake", "cookie", "pie",
            "stew", "porkchop", "steak", "template", "anvil", "cauldron", "hopper", "piston", "lever", "button",
            "plate", "lantern", "campfire", "barrel", "smoker", "dispenser", "dropper", "observer", "trapdoor",
            "carpet", "banner", "bell", "loom", "flower", "mushroom", "melon", "pumpkin", "stem", "cream", "tear",
            "membrane", "wart", "lily", "frame", "minecart", "saddle", "lead", "tag", "powder", "dust", "brick",
            "tile", "blade", "jar", "box", "star", "fruit", "berry", "kelp", "cactus", "vine", "root", "bale", "pane",
            "tool", "steel", "charge", "fuse", "core", "key", "card", "coin", "gem", "stair"));

    // "Oak Planks" already ends in s, "Berry" wants "Berries", "Box" wants "Boxes". everything else gets an s
    public static String plural(String name) {
        if (name == null || name.isEmpty()) return name;
        String last = name.substring(name.lastIndexOf(' ') + 1).toLowerCase(Locale.ROOT);
        if (last.endsWith("s") || !COUNTABLE.contains(last)) {
            return name;
        }
        if (last.endsWith("y") && last.length() > 1 && "aeiou".indexOf(last.charAt(last.length() - 2)) < 0) {
            return name.substring(0, name.length() - 1) + "ies";
        }
        if (last.endsWith("x") || last.endsWith("sh") || last.endsWith("ch")) {
            return name + "es";
        }
        return name + "s";
    }

    // mobs are all countable, so this skips the list: "Zombies", "Endermen", "Wolves", "Witches", "Sheep"
    public static String pluralMob(String name) {
        if (name == null || name.isEmpty()) return name;
        String last = name.substring(name.lastIndexOf(' ') + 1).toLowerCase(Locale.ROOT);
        if (last.endsWith("s") || last.endsWith("sheep") || last.endsWith("fish")) return name;
        if (last.endsWith("man")) return name.substring(0, name.length() - 2) + "en";
        if (last.endsWith("f")) return name.substring(0, name.length() - 1) + "ves";
        if (last.endsWith("y") && last.length() > 1 && "aeiou".indexOf(last.charAt(last.length() - 2)) < 0) {
            return name.substring(0, name.length() - 1) + "ies";
        }
        if (last.endsWith("x") || last.endsWith("sh") || last.endsWith("ch")) return name + "es";
        return name + "s";
    }

    // "a Crafting Table", "an Iron Ingot", but just "Coal" and "Oak Planks" for the uncountable
    public static String one(String name) {
        if (name == null || name.isEmpty()) return "something";
        if (!english()) return name;
        String last = name.substring(name.lastIndexOf(' ') + 1).toLowerCase(Locale.ROOT);
        if (last.endsWith("s") || !COUNTABLE.contains(last)) {
            return name;
        }
        char c = Character.toLowerCase(name.charAt(0));
        return ("aeiou".indexOf(c) >= 0 ? "an " : "a ") + name;
    }

    // "4 Oak Planks", "3 Iron Ingots", "a Crafting Table", "Coal"
    public static String count(int n, String name) {
        if (n <= 1) {
            return one(name);
        }
        return n + " " + (english() ? plural(name) : name);
    }

    // ---- groups

    private record Group(Set<Item> items, String singular, String plural) {
    }

    private static List<Group> GROUPS;

    // built on first use, not in a static init: tests and the client both want the registries up first
    private static synchronized List<Group> groups() {
        if (GROUPS != null) return GROUPS;
        List<Group> list = new ArrayList<>();
        // the hand picked ones first, their names read better than the catalogue keys
        group(list, ItemHelper.LOG, "a log", "logs");
        group(list, ItemHelper.STRIPPED_LOGS, "a stripped log", "stripped logs");
        group(list, ItemHelper.STRIPPABLE_LOGS, "a log", "logs");
        group(list, ItemHelper.WOOD, "wood", "wood");
        group(list, ItemHelper.PLANKS, "planks", "planks");
        group(list, ItemHelper.WOOL, "wool", "wool");
        group(list, ItemHelper.BED, "a bed", "beds");
        group(list, ItemHelper.SAPLINGS, "a sapling", "saplings");
        group(list, ItemHelper.LEAVES, "leaves", "leaves");
        group(list, ItemHelper.DIRTS, "dirt", "dirt");
        group(list, ItemHelper.DYE, "dye", "dye");
        group(list, ItemHelper.CARPET, "a carpet", "carpets");
        group(list, ItemHelper.FLOWER, "a flower", "flowers");
        group(list, ItemHelper.SHULKER_BOXES, "a shulker box", "shulker boxes");
        group(list, ItemHelper.WOOD_DOOR, "a wooden door", "wooden doors");
        group(list, ItemHelper.WOOD_TRAPDOOR, "a wooden trapdoor", "wooden trapdoors");
        group(list, ItemHelper.WOOD_FENCE, "a fence", "fences");
        group(list, ItemHelper.WOOD_FENCE_GATE, "a fence gate", "fence gates");
        group(list, ItemHelper.WOOD_SIGN, "a sign", "signs");
        group(list, ItemHelper.WOOD_HANGING_SIGN, "a hanging sign", "hanging signs");
        group(list, ItemHelper.WOOD_BUTTON, "a wooden button", "wooden buttons");
        group(list, ItemHelper.WOOD_PRESSURE_PLATE, "a wooden pressure plate", "wooden pressure plates");
        group(list, ItemHelper.WOOD_SLAB, "a wooden slab", "wooden slabs");
        group(list, ItemHelper.WOOD_STAIRS, "wooden stairs", "wooden stairs");
        group(list, ItemHelper.WOOD_BOAT, "a boat", "boats");
        group(list, ItemHelper.HOSTILE_MOB_DROPS, "mob drops", "mob drops");
        group(list, ItemHelper.COPPER_BLOCKS, "a copper block", "copper blocks");
        group(list, ItemHelper.LEATHER_ARMORS, "leather armor", "leather armor");
        group(list, ItemHelper.GOLDEN_ARMORS, "golden armor", "golden armor");
        group(list, ItemHelper.IRON_ARMORS, "iron armor", "iron armor");
        group(list, ItemHelper.DIAMOND_ARMORS, "diamond armor", "diamond armor");
        group(list, ItemHelper.NETHERITE_ARMORS, "netherite armor", "netherite armor");
        group(list, ItemHelper.WOODEN_TOOLS, "wooden tools", "wooden tools");
        group(list, ItemHelper.STONE_TOOLS, "stone tools", "stone tools");
        group(list, ItemHelper.IRON_TOOLS, "iron tools", "iron tools");
        group(list, ItemHelper.GOLDEN_TOOLS, "golden tools", "golden tools");
        group(list, ItemHelper.DIAMOND_TOOLS, "diamond tools", "diamond tools");
        group(list, ItemHelper.NETHERITE_TOOLS, "netherite tools", "netherite tools");
        // then every catalogue entry that is a group, with its key turned into words
        try {
            for (String name : TaskCatalogue.resourceNames()) {
                // "door" and "wooden_door" are the same group, and hash order picked which one won. the real name always does now
                if (TaskCatalogue.isAlias(name)) continue;
                Item[] matches = TaskCatalogue.getItemMatches(name);
                if (matches == null || matches.length < 2) continue;
                String words = name.replace('_', ' ');
                group(list, matches, one(words), english() ? plural(words) : words);
            }
        } catch (Throwable t) {
            // the catalogue not being up yet just means fewer names, not no hud
        }
        GROUPS = list;
        return list;
    }

    private static void group(List<Group> list, Item[] items, String singular, String plural) {
        if (items == null || items.length < 2) return;
        Set<Item> set = new HashSet<>(Arrays.asList(items));
        for (Group g : list) {
            if (g.items.equals(set)) return;
        }
        list.add(new Group(set, singular, plural));
    }

    private static Group find(Item[] matches) {
        Set<Item> set = new HashSet<>(Arrays.asList(matches));
        Group best = null;
        for (Group g : groups()) {
            if (g.items.equals(set)) {
                return g;
            }
            if (g.items.containsAll(set) && (best == null || g.items.size() < best.items.size())) {
                best = g;
            }
        }
        return best;
    }

    private static String others(Item[] matches) {
        return item(matches[0]) + " or " + (matches.length - 1) + " other" + (matches.length == 2 ? "" : "s");
    }

    // a plain name for a set of alternatives: the group that is exactly this set, else the smallest group that holds
    // all of it, else "Oak Log or 5 others". count 1 gets the article form, 0 means "don't say how many"
    public static String items(Item[] matches, int count) {
        if (matches == null || matches.length == 0) return "something";
        if (count <= 0) return some(matches);
        if (matches.length == 1) {
            return count(count, item(matches[0]));
        }
        Group best = find(matches);
        if (best != null) {
            // "4 planks" but not "4 a bed"
            return count == 1 ? best.singular : count + " " + best.plural;
        }
        return count == 1 ? others(matches) : count + " " + others(matches);
    }

    // the no-number form: "logs", "Coal", "Iron Ingots". for labels where the count is noise ("Mining logs")
    public static String some(Item[] matches) {
        if (matches == null || matches.length == 0) return "something";
        if (matches.length == 1) {
            String name = item(matches[0]);
            return english() ? plural(name) : name;
        }
        Group best = find(matches);
        return best != null ? best.plural : others(matches);
    }

    public static String items(ItemTarget target) {
        if (target == null || target.isEmpty()) return "something";
        int count = target.getTargetCount();
        // ItemTarget.infinite() reports a 99999999 count, which is "all of it", not a number anyone wants
        if (count > 9999) count = 0;
        if (count > 0 && target.isCatalogueItem() && target.getMatches().length > 1) {
            String words = target.getCatalogueName().replace('_', ' ');
            return count == 1 ? one(words) : count + " " + (english() ? plural(words) : words);
        }
        return items(target.getMatches(), count);
    }

    // the no-number form of a target unless it asks for more than one: "logs", "Coal", "16 logs", "3 Raw Iron".
    // for verbs where one is not interesting ("Mining logs", "Picking up Cobblestone")
    public static String some(ItemTarget target) {
        if (target == null || target.isEmpty()) return "something";
        int count = target.getTargetCount();
        return count > 1 && count <= 9999 ? items(target) : some(target.getMatches());
    }

    public static String some(ItemTarget... targets) {
        if (targets == null || targets.length == 0) return "something";
        List<String> parts = new ArrayList<>(targets.length);
        for (ItemTarget t : targets) {
            if (t == null || t.isEmpty()) continue;
            parts.add(some(t));
        }
        return list(parts);
    }

    // "a Crafting Table", "3 Iron Ingots and a Stone Sword", "logs, planks and 2 Sticks"
    public static String items(ItemTarget... targets) {
        if (targets == null || targets.length == 0) return "something";
        List<String> parts = new ArrayList<>(targets.length);
        for (ItemTarget t : targets) {
            if (t == null || t.isEmpty()) continue;
            parts.add(items(t));
        }
        return list(parts);
    }

    public static String list(List<String> parts) {
        if (parts.isEmpty()) return "something";
        if (parts.size() == 1) return parts.get(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(i == parts.size() - 1 ? " and " : ", ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    // for the blocks a mining task tracks: "logs" if they are logs, else the first block's name. an ore and its
    // deepslate twin are one thing to a player, so the pair reads as the plain ore ("Diamond Ore", not "Diamond Ore
    // or 1 other", which looks an awful lot like it's ignoring the other one)
    public static String blocks(Block[] blocks) {
        if (blocks == null || blocks.length == 0) return "blocks";
        blocks = withoutDeepslateTwins(blocks);
        if (blocks.length == 1) return block(blocks[0]);
        Item[] items = new Item[blocks.length];
        for (int i = 0; i < blocks.length; i++) {
            items[i] = blocks[i].asItem();
        }
        return items(items, 0);
    }

    // drops every deepslate_x whose plain x is also in the list
    static Block[] withoutDeepslateTwins(Block[] blocks) {
        Set<Block> all = new HashSet<>(Arrays.asList(blocks));
        List<Block> out = new ArrayList<>(blocks.length);
        for (Block b : blocks) {
            if (b == null) continue;
            String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
            if (path.startsWith("deepslate_")) {
                Block plain = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(path.substring("deepslate_".length())));
                if (plain != b && all.contains(plain)) continue;
            }
            out.add(b);
        }
        return out.toArray(Block[]::new);
    }

    // test hook, groups depend on the catalogue being loaded
    static void resetGroups() {
        synchronized (HudText.class) {
            GROUPS = null;
        }
    }
}
