package adris.altoclef.ui.gui.preview;

import adris.altoclef.ui.gui.facade.BotFacade;
import adris.altoclef.ui.gui.facade.CommandsAccess;
import adris.altoclef.ui.gui.facade.ItemsAccess;
import adris.altoclef.ui.gui.facade.KeyNames;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.SettingsAccess;
import adris.altoclef.ui.gui.facade.StatusAccess;
import adris.altoclef.ui.gui.facade.StatsAccess;
import adris.altoclef.ui.gui.facade.WorldAccess;
import adris.altoclef.ui.gui.kit.Clipboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// the game, faked with enough real looking data that a preview png is worth looking at. every action records
// itself in `actions` so a tab test can check what would have run
public final class FakeFacade implements BotFacade {

    public final List<String> actions = new ArrayList<>();
    public final FakeSettings settings = new FakeSettings();
    public final FakeCommands commands = new FakeCommands();
    public final FakeItems items = new FakeItems();
    public final FakeWorld world = new FakeWorld();
    public final FakeStatus status = new FakeStatus();
    public StatsAccess stats = StatsAccess.NONE;
    public final FakeSchematics schematics = new FakeSchematics(actions);
    public final Clipboard clipboard = Clipboard.inMemory();

    @Override
    public SettingsAccess settings() {
        return settings;
    }

    @Override
    public CommandsAccess commands() {
        return commands;
    }

    @Override
    public ItemsAccess items() {
        return items;
    }

    @Override
    public WorldAccess world() {
        return world;
    }

    @Override
    public StatusAccess status() {
        return status;
    }

    @Override
    public StatsAccess stats() {
        return stats;
    }

    @Override
    public adris.altoclef.ui.gui.facade.SchematicsAccess schematics() {
        return schematics;
    }

    @Override
    public KeyNames keyNames() {
        return key -> switch (key) {
            case -1 -> "Unbound";
            case 344 -> "Right Shift";
            case 345 -> "Right Ctrl";
            case 340 -> "Left Shift";
            case 256 -> "Escape";
            default -> key >= 65 && key <= 90 ? String.valueOf((char) key) : "Key " + key;
        };
    }

    @Override
    public Clipboard clipboard() {
        return clipboard;
    }

    // ----- settings

    public final class FakeSettings implements SettingsAccess {
        public final Map<String, SettingView> map = new LinkedHashMap<>();
        public final Map<String, String> descriptions = new LinkedHashMap<>();

        FakeSettings() {
            bool("allowBreak", true, true, "Allow Baritone to break blocks.");
            bool("allowPlace", true, true, "Allow Baritone to place blocks.");
            bool("allowSprint", true, true, "Allow Baritone to sprint.");
            bool("allowParkour", false, false, "Allow descending diagonally and parkour jumps over gaps.");
            bool("allowBoats", false, false, "Use boats to cross water. Picks the boat up after landing.");
            bool("fastMode", false, false, "Favor speed when planning and moving along a path.");
            bool("autoTool", true, true, "Choose the best available tool for the block being mined.");
            bool("allowInventory", false, false, "Move useful items from inventory into the hotbar.");
            bool("allowWaterBucketFall", true, true, "Use a water bucket to catch a long fall.");
            bool("allowLadderClutch", false, false, "Place a ladder to catch a fall against a wall.");
            bool("smoothLook", false, false, "Smooth camera movement while following the path.");
            bool("allowParkourPlace", false, true, "Allow parkour placing blocks mid air. Needs allowParkour.");
            num("blockPlacementPenalty", Kind.DOUBLE, "Double", "20.0", "20.0", "Extra cost for placing a block, in ticks.");
            num("blockBreakAdditionalPenalty", Kind.DOUBLE, "Double", "2.0", "2.0", "Extra cost for breaking a block.");
            num("primaryTimeoutMS", Kind.LONG, "Long", "500", "500", "Pathing time budget before a path is used as is.");
            num("costHeuristic", Kind.DOUBLE, "Double", "3.563", "3.563", "The A* heuristic weight. Higher is faster and worse.");
            num("maxFallHeightNoWater", Kind.INTEGER, "Integer", "3", "3", "Fall height Baritone will take without water.");
            num("pathingMaxChunkBorderFetch", Kind.INTEGER, "Integer", "50", "50", "How many chunks past the border it will fetch.");
            text("prefix", "#", "#", "The command prefix.");
            text("altoIdleCommand", "", "follow Jacob", "Command run when the bot goes idle.");
            color("colorCurrentPath", "255,0,0", "255,0,0", "Colour of the path being executed.");
            color("colorNextPath", "0,255,0", "0,255,0", "Colour of the next planned path segment.");
            enumv("mirror", "NONE", "NONE", List.of("NONE", "LEFT_RIGHT", "FRONT_BACK"), "Mirror schematics.");
            key("guiKey", "344", "344", "GLFW key code that opens the click gui. -1 unbinds it.");
            bool("guiKeyEnabled", true, true, "Whether the gui key opens the click gui.");
            other("blocksToAvoid", "List<Block>", "", "", "Blocks the pathfinder avoids walking through.");
            other("buildSubstitutes", "Map<Block, List<Block>>", "", "", "Blocks to substitute when building.");
            other("blocksToAvoidBreaking", "List<Block>", "minecraft:torch,minecraft:chest", "", "Blocks never broken.");
            bool("altoCommitCombat", true, true, "Commit to fights the way the combat machine wants.");
            bool("altoAsyncSmelting", false, false, "Walk away from a loaded furnace and come back.");
            num("altoFoodMinimum", Kind.INTEGER, "Integer", "70", "24", "Food units below which a top up is due.");
            bool("renderPath", true, true, "Render the path.");
            bool("renderGoal", true, true, "Render the goal.");
            bool("chatDebug", false, false, "Print debug chat.");
            bool("legitMine", false, false, "Only mine ore you can see, for servers with anti xray.");
            bool("elytraAutoJump", false, false, "Jump off ledges automatically in elytra mode.");
            num("elytraFireworkSpeed", Kind.DOUBLE, "Double", "1.2", "1.2", "Target speed for fireworks.");
            bool("exploreForBlocks", true, true, "Explore when no blocks are known.");
            num("followRadius", Kind.INTEGER, "Integer", "3", "3", "Distance to keep from the followed entity.");
            bool("cachedChunksOpacity", false, false, "Opacity for cached chunks rendering.");
            bool("mineScanDroppedItems", true, true, "Scan for dropped items while mining.");
        }

        private void put(String name, Kind kind, String type, String value, String def, List<String> options, String desc) {
            map.put(name, new SettingView(name, kind, type, value, def, !value.equals(def), options));
            descriptions.put(name, desc);
        }

        private void bool(String name, boolean value, boolean def, String desc) {
            put(name, Kind.BOOLEAN, "Boolean", String.valueOf(value), String.valueOf(def), List.of(), desc);
        }

        private void num(String name, Kind kind, String type, String value, String def, String desc) {
            put(name, kind, type, value, def, List.of(), desc);
        }

        private void text(String name, String value, String def, String desc) {
            put(name, Kind.STRING, "String", value, def, List.of(), desc);
        }

        private void color(String name, String value, String def, String desc) {
            put(name, Kind.COLOR, "Color", value, def, List.of(), desc);
        }

        private void enumv(String name, String value, String def, List<String> options, String desc) {
            put(name, Kind.ENUM, "Mirror", value, def, options, desc);
        }

        private void key(String name, String value, String def, String desc) {
            put(name, Kind.KEY, "Integer", value, def, List.of(), desc);
        }

        private void other(String name, String type, String value, String def, String desc) {
            put(name, Kind.OTHER, type, value, def, List.of(), desc);
        }

        @Override
        public List<SettingView> all() {
            List<SettingView> out = new ArrayList<>(map.values());
            out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
            return out;
        }

        @Override
        public SettingView get(String name) {
            return map.get(name);
        }

        @Override
        public Result set(String name, String text) {
            SettingView v = map.get(name);
            if (v == null) {
                return Result.fail("No setting called " + name);
            }
            if (v.kind() == Kind.INTEGER || v.kind() == Kind.LONG) {
                try {
                    Long.parseLong(text.trim());
                } catch (NumberFormatException e) {
                    return Result.fail("Not a whole number: " + text, "set " + name + " " + text);
                }
            }
            if (v.kind() == Kind.DOUBLE || v.kind() == Kind.FLOAT) {
                try {
                    Double.parseDouble(text.trim());
                } catch (NumberFormatException e) {
                    return Result.fail("Not a number: " + text, "set " + name + " " + text);
                }
            }
            if (v.kind() == Kind.COLOR && !text.matches("\\s*\\d+\\s*,\\s*\\d+\\s*,\\s*\\d+\\s*")) {
                return Result.fail("A colour is r,g,b like 255,0,0", "set " + name + " " + text);
            }
            if (v.kind() == Kind.OTHER && text.contains("!")) {
                return Result.fail("Unknown block \"" + text + "\"", "set " + name + " " + text);
            }
            map.put(name, new SettingView(name, v.kind(), v.typeName(), text, v.defaultValue(), !text.equals(v.defaultValue()), v.options()));
            actions.add("set " + name + " " + text);
            return Result.ok("Set " + name + " to " + text, "set " + name + " " + text);
        }

        @Override
        public Result reset(String name) {
            SettingView v = map.get(name);
            if (v == null) {
                return Result.fail("No setting called " + name);
            }
            map.put(name, new SettingView(name, v.kind(), v.typeName(), v.defaultValue(), v.defaultValue(), false, v.options()));
            actions.add("set reset " + name);
            return Result.ok("Reset " + name + " to " + v.defaultValue(), "set reset " + name);
        }

        @Override
        public Result toggle(String name) {
            SettingView v = map.get(name);
            if (v == null || v.kind() != Kind.BOOLEAN) {
                return Result.fail("Not a toggle: " + name);
            }
            return set(name, String.valueOf(!Boolean.parseBoolean(v.value())));
        }

        @Override
        public String description(String name) {
            return descriptions.getOrDefault(name, "");
        }
    }

    // ----- commands

    public final class FakeCommands implements CommandsAccess {
        public final List<CommandInfo> list = new ArrayList<>();
        public String lastRun;
        public boolean failNext;

        FakeCommands() {
            list.add(new CommandInfo(List.of("goto", "go", "path"), "Go to a coordinate or block",
                    List.of("Tell Baritone to path to a position or a block.", "", "Usage:", "> goto <y>", "> goto <x> <z>", "> goto <x> <y> <z>", "> goto <block>")));
            list.add(new CommandInfo(List.of("mine", "m"), "Mine some blocks",
                    List.of("The mine command allows you to tell Baritone to search for and mine individual blocks.", "", "Usage:", "> mine diamond_ore", "> mine 64 oak_log", "> mine iron_ore coal_ore")));
            list.add(new CommandInfo(List.of("follow", "f"), "Follow entity things",
                    List.of("Follow players, entities, or entity types.", "", "Usage:", "> follow entities", "> follow entity zombie", "> follow players", "> follow player <name>")));
            list.add(new CommandInfo(List.of("get"), "Get items the long way round",
                    List.of("Crafts, smelts, mines or collects its way to the items.", "", "Usage:", "> get oak_log 64", "> get iron_ingot 3 stick 4")));
            list.add(new CommandInfo(List.of("stop", "cancel", "s"), "Stop everything",
                    List.of("Cancels the current task and path.", "", "Usage:", "> stop")));
            list.add(new CommandInfo(List.of("set", "setting", "settings"), "View or change settings",
                    List.of("Using the set command, you can manage all of Baritone's settings.", "", "Usage:", "> set list", "> set <setting> <value>", "> set reset <setting>")));
            list.add(new CommandInfo(List.of("wp", "waypoint", "waypoints"), "Manage waypoints",
                    List.of("Save, list and go to waypoints.", "", "Usage:", "> wp save <tag> <name>", "> wp list", "> wp goto <name>")));
            list.add(new CommandInfo(List.of("gamer"), "Beat the game",
                    List.of("Starts the speedrun brain.", "", "Usage:", "> gamer", "> gamer status", "> gamer reset")));
            list.add(new CommandInfo(List.of("gui", "clickgui"), "Open the click gui",
                    List.of("Opens this panel. Right shift by default, rebind in Settings or with set guiKey.", "", "Usage:", "> gui")));
            list.add(new CommandInfo(List.of("help", "?"), "View all commands or help on specific ones",
                    List.of("Lists commands.", "", "Usage:", "> help", "> help <command>")));
            list.add(new CommandInfo(List.of("farm"), "Farm nearby crops",
                    List.of("Harvests and replants crops.", "", "Usage:", "> farm", "> farm <range>")));
            list.add(new CommandInfo(List.of("explore"), "Explore things",
                    List.of("Explores outward from a point.", "", "Usage:", "> explore", "> explore <x> <z>")));
        }

        @Override
        public List<CommandInfo> commands() {
            return list;
        }

        @Override
        public Result run(String line) {
            lastRun = line;
            actions.add(line);
            if (failNext) {
                failNext = false;
                return Result.fail("Unknown command \"" + line.split(" ")[0] + "\"", line);
            }
            return Result.ok("Ran " + line.split(" ")[0], line);
        }
    }

    // ----- items

    public final class FakeItems implements ItemsAccess {
        public final List<ItemEntry> gettable = new ArrayList<>();
        public final List<ItemEntry> mineable = new ArrayList<>();

        FakeItems() {
            String[] g = {"oak_log", "oak_planks", "stick", "crafting_table", "wooden_pickaxe", "stone_pickaxe", "iron_pickaxe",
                    "diamond_pickaxe", "cobblestone", "stone", "furnace", "coal", "charcoal", "iron_ingot", "iron_ore", "raw_iron",
                    "gold_ingot", "diamond", "obsidian", "bucket", "water_bucket", "lava_bucket", "flint", "flint_and_steel",
                    "bread", "cooked_beef", "cooked_porkchop", "cooked_chicken", "apple", "wheat", "wheat_seeds", "bed",
                    "white_wool", "string", "bow", "arrow", "shield", "iron_sword", "diamond_sword", "iron_helmet",
                    "iron_chestplate", "iron_leggings", "iron_boots", "torch", "ladder", "chest", "ender_pearl", "blaze_rod",
                    "blaze_powder", "ender_eye", "glass", "sand", "gravel", "dirt", "netherrack", "gold_block", "iron_block",
                    "diamond_block", "redstone", "lapis_lazuli", "emerald", "book", "paper", "sugar_cane", "leather", "fishing_rod"};
            for (String s : g) {
                int max = s.endsWith("_pickaxe") || s.endsWith("_sword") || s.endsWith("bucket") || s.contains("helmet")
                        || s.contains("chestplate") || s.contains("leggings") || s.contains("boots") || s.equals("bow")
                        || s.equals("shield") || s.equals("bed") || s.equals("fishing_rod") || s.equals("flint_and_steel") ? 1
                        : s.equals("ender_pearl") ? 16 : 64;
                gettable.add(new ItemEntry(s, ItemEntry.titleCase(s), "minecraft:" + s, max));
            }
            String[] m = {"stone", "cobblestone", "coal_ore", "iron_ore", "deepslate_iron_ore", "gold_ore", "diamond_ore",
                    "deepslate_diamond_ore", "redstone_ore", "lapis_ore", "emerald_ore", "ancient_debris", "oak_log", "birch_log",
                    "spruce_log", "dirt", "sand", "gravel", "clay", "obsidian", "netherrack", "nether_quartz_ore", "glowstone"};
            for (String s : m) {
                mineable.add(new ItemEntry(s, ItemEntry.titleCase(s), "minecraft:" + s, 64));
            }
        }

        @Override
        public List<ItemEntry> gettable() {
            return gettable;
        }

        @Override
        public List<ItemEntry> mineable() {
            return mineable;
        }

        @Override
        public Result get(List<BasketLine> lines) {
            StringBuilder cmd = new StringBuilder("get");
            int total = 0;
            for (BasketLine l : lines) {
                cmd.append(' ').append(l.name()).append(' ').append(l.count());
                total += l.count();
            }
            actions.add(cmd.toString());
            return Result.ok("Getting " + total + " items", cmd.toString());
        }

        @Override
        public Result mine(List<BasketLine> lines) {
            StringBuilder cmd = new StringBuilder("mine");
            for (BasketLine l : lines) {
                cmd.append(' ').append(l.name());
            }
            actions.add(cmd.toString());
            return Result.ok("Mining " + lines.size() + " block types", cmd.toString());
        }
    }

    // ----- world

    public final class FakeWorld implements WorldAccess {
        public Position pos = new Position(120.5, 64.0, -30.5);
        // looking a little west of north, so the map's arrow has a tilt to show
        public float yaw = 200f;
        public String dimension = "overworld";
        public String following;
        public final List<PlayerEntry> players = new ArrayList<>();
        public final List<EntityEntry> entities = new ArrayList<>();
        public final List<WaypointEntry> waypoints = new ArrayList<>();

        FakeWorld() {
            players.add(new PlayerEntry("aver", 0, true));
            players.add(new PlayerEntry("Steve", 12.4, false));
            players.add(new PlayerEntry("Alex", 48.0, false));
            players.add(new PlayerEntry("xXx_Notch_xXx", -1, false));
            players.add(new PlayerEntry("Herobrine", -1, false));
            int id = 100;
            String[][] e = {
                    {"minecraft:zombie", "Zombie", "", "8.2"}, {"minecraft:zombie", "Zombie", "", "14.9"}, {"minecraft:zombie", "Zombie", "", "31.0"},
                    {"minecraft:cow", "Cow", "", "5.1"}, {"minecraft:cow", "Cow", "", "6.3"}, {"minecraft:sheep", "Sheep", "", "9.9"},
                    {"minecraft:wolf", "Wolf", "Biscuit", "2.0"}, {"minecraft:creeper", "Creeper", "", "22.7"},
                    {"minecraft:skeleton", "Skeleton", "", "27.4"}, {"minecraft:item", "Item", "Oak Log", "3.3"},
                    {"minecraft:villager", "Villager", "", "40.2"}, {"minecraft:villager", "Villager", "", "41.8"},
                    {"minecraft:chicken", "Chicken", "", "11.0"}, {"minecraft:pig", "Pig", "", "17.5"}, {"minecraft:bat", "Bat", "", "60.0"},
            };
            for (String[] row : e) {
                entities.add(new EntityEntry(id++, row[0], row[1], row[2], Double.parseDouble(row[3]), row[0].equals("minecraft:item") ? "minecraft:oak_log" : row[0] + "_spawn_egg"));
            }
            waypoints.add(new WaypointEntry("home", "home", 118, 64, -32, 1_700_000_000_000L));
            waypoints.add(new WaypointEntry("iron cave", "user", 402, 23, 1190, 1_700_000_100_000L));
            waypoints.add(new WaypointEntry("village", "user", -1205, 71, 388, 1_700_000_200_000L));
            waypoints.add(new WaypointEntry("death", "death", 77, 12, -2048, 1_700_000_300_000L));
            waypoints.add(new WaypointEntry("portal", "user", 130, 63, -28, 1_700_000_400_000L));
        }

        @Override
        public Position playerPos() {
            return pos;
        }

        @Override
        public float playerYaw() {
            return yaw;
        }

        @Override
        public String dimension() {
            return dimension;
        }

        @Override
        public List<PlayerEntry> players() {
            return players;
        }

        @Override
        public List<EntityEntry> entities() {
            return entities;
        }

        private Result follow(String what, String cmd) {
            following = what;
            actions.add(cmd);
            return Result.ok("Following " + what, cmd);
        }

        @Override
        public Result followPlayer(String name) {
            return follow(name, "follow player " + name);
        }

        @Override
        public Result followEntity(int id) {
            for (EntityEntry e : entities) {
                if (e.id() == id) {
                    return follow(e.displayName().isEmpty() ? e.typeName().toLowerCase(Locale.ROOT) + " #" + id : e.displayName(), "follow entity " + id);
                }
            }
            return Result.fail("That entity is gone");
        }

        @Override
        public Result followType(String typeId) {
            String path = typeId.contains(":") ? typeId.substring(typeId.indexOf(':') + 1) : typeId;
            return follow("every " + path, "follow entity " + path);
        }

        @Override
        public Result followAllPlayers() {
            return follow("all players", "follow players");
        }

        @Override
        public Result followAllEntities() {
            return follow("all entities", "follow entities");
        }

        @Override
        public Result stopFollowing() {
            following = null;
            actions.add("stop");
            return Result.ok("Stopped following", "stop");
        }

        @Override
        public String following() {
            return following;
        }

        @Override
        public List<WaypointEntry> waypoints() {
            return waypoints;
        }

        @Override
        public List<String> waypointTags() {
            return List.of("user", "home", "death", "bed");
        }

        @Override
        public Result addWaypointHere(String name, String tag) {
            if (name == null || name.isBlank()) {
                return Result.fail("Give the waypoint a name");
            }
            waypoints.add(new WaypointEntry(name, tag, (int) Math.floor(pos.x()), (int) Math.floor(pos.y()), (int) Math.floor(pos.z()), System.currentTimeMillis()));
            String cmd = "wp save " + tag + " " + name;
            actions.add(cmd);
            return Result.ok("Saved " + name, cmd);
        }

        @Override
        public Result deleteWaypoint(WaypointEntry w) {
            waypoints.remove(w);
            actions.add("wp delete " + w.name());
            return Result.ok("Deleted " + w.name(), "wp delete " + w.name());
        }

        private Result go(Integer x, Integer y, Integer z, String verb) {
            String cmd;
            if (x != null && z != null && y != null) {
                cmd = verb + " " + x + " " + y + " " + z;
            } else if (x != null && z != null) {
                cmd = verb + " " + x + " " + z;
            } else if (y != null) {
                cmd = verb + " " + y;
            } else {
                return Result.fail("Need x and z, or a y level");
            }
            actions.add(cmd);
            return Result.ok((verb.equals("goto") ? "Going to " : "Goal set to ") + cmd.substring(verb.length() + 1), cmd);
        }

        @Override
        public Result goTo(Integer x, Integer y, Integer z) {
            return go(x, y, z, "goto");
        }

        @Override
        public Result setGoal(Integer x, Integer y, Integer z) {
            return go(x, y, z, "goal");
        }
    }

    // ----- status

    public final class FakeStatus implements StatusAccess {
        public Activity activity = new Activity(false, "Idle", "", null);

        @Override
        public Activity activity() {
            return activity;
        }

        @Override
        public Result stop() {
            activity = new Activity(false, "Idle", "", null);
            actions.add("stop");
            return Result.ok("Stopped", "stop");
        }
    }
}
