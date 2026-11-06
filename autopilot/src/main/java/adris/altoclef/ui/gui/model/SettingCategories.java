package adris.altoclef.ui.gui.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// which shelf a setting lives on. 332 settings in one list is a wall, so the tab cuts them up by what the name says.
// names are camelCase, so the rules look at whole words: "ore" must find oreBlocks and must not find "explore"
public final class SettingCategories {

    public static final String ALTO = "Tasks";
    public static final String MOVEMENT = "Movement and safety";
    public static final String MINING = "Mining";
    public static final String BUILDING = "Building";
    public static final String RENDERING = "Rendering";
    public static final String ELYTRA = "Elytra";
    public static final String CHAT = "Chat and commands";
    public static final String CACHE = "World cache";
    public static final String PATHING = "Pathing and processes";
    public static final String INTERFACE = "Interface";
    public static final String GENERAL = "General";

    // the order here is the order of the category column and the order the rules are tried in. first hit wins
    private static final Map<String, String[]> RULES = new LinkedHashMap<>();

    static {
        // a word matches a stem when it starts with it. "=ore" means the word must be exactly that
        RULES.put(MOVEMENT, new String[] {"allow", "assume", "avoid", "safe", "sprint", "parkour", "jump", "walk", "fall"});
        RULES.put(MINING, new String[] {"mine", "mining", "=ore", "=ores"});
        RULES.put(BUILDING, new String[] {"build", "schematic", "=mapart"});
        RULES.put(RENDERING, new String[] {"render", "color", "colour", "fade", "opacity"});
        RULES.put(ELYTRA, new String[] {"elytra"});
        RULES.put(CHAT, new String[] {"chat", "prefix", "echo", "notification", "=log", "=logs", "toast"});
        RULES.put(CACHE, new String[] {"cache", "cached", "caching", "chunk", "repack", "prune"});
        RULES.put(PATHING, new String[] {"explore", "follow", "farm", "goal", "path", "plan", "timeout", "cost", "gather"});
        RULES.put(INTERFACE, new String[] {"=gui", "=key", "=keys"});
    }

    // the settings an everyday player actually reaches for. everything not in here (the alto* block, the pathing
    // cost internals, the cache tuning...) is advanced and hides behind the "show advanced" switch
    private static final Set<String> EVERYDAY = Set.of(
            "allowBreak", "allowSprint", "allowPlace", "allowPlaceInFluidsSource", "allowPlaceInFluidsFlow",
            "allowInventory", "ticksBetweenInventoryMoves", "inventoryMoveOnlyIfStationary", "autoTool",
            "assumeExternalAutoTool", "allowWaterBucketFall", "allowLadderClutch", "pickupLadders", "selfDefense", "assumeWalkOnWater",
            "assumeStep", "assumeSafeWalk", "allowParkour", "allowParkourPlace", "allowParkourAscend", "allowNeos",
            "allowClimbJumps", "allowVines", "allowWalkOnBottomSlab", "allowWalkOnMagmaBlocks", "allowDiagonalAscend",
            "allowDiagonalDescend", "allowDownward", "allowSwimming", "allowBoats", "boatPickup", "sprintAscends",
            "sprintThroughDescends", "sprintThroughCorners", "sprintJumping", "preferFasterPathing", "allowGroundShortcuts",
            "fastMode", "walkWhileBreaking", "useSwordToMine", "toolWearChain","freeLook", "blockFreeLook", "elytraFreeLook", "smoothLook",
            "elytraSmoothLook", "antiCheatCompatibility", "rightClickSpeed", "blockReachDistance", "blockBreakSpeed",
            "considerPotionEffects", "pauseMiningForFallingBlocks",
            "mineGoalUpdateInterval", "minYLevelWhileMining", "maxYLevelWhileMining", "legitMine", "legitMineYLevel",
            "allowOnlyExposedOres", "allowOnlyExposedOresDistance", "exploreForBlocks", "replantCrops", "replantNetherWart",
            "farmWaitForGrowth", "mineScanDroppedItems", "itemSaver", "itemSaverThreshold", "preferSilkTouch",
            "buildInLayers", "layerOrder", "layerHeight", "startAtLayer", "buildRepeat", "buildRepeatCount",
            "buildRepeatSneaky", "buildOnlySelection", "breakFromAbove", "mapArtMode", "schematicOrientationX",
            "schematicOrientationY", "schematicOrientationZ", "buildSchematicRotation", "buildSchematicMirror",
            "buildIgnoreExisting", "buildIgnoreDirection",
            "renderPath", "renderPathAsLine", "renderPathRibbon", "renderGoal", "renderSelection", "renderCachedChunks",
            "cachedChunksOpacity", "pathRenderLineWidthPixels", "goalRenderLineWidthPixels", "selectionOpacity",
            "selectionLineWidth", "renderSelectionCorners", "renderGoalXZBeacon", "renderSelectionBoxes",
            "renderPathIgnoreDepth", "renderGoalIgnoreDepth", "renderSelectionBoxesIgnoreDepth",
            "prefix", "prefixControl", "shortBaritonePrefix", "chatControl", "chatControlAnyway", "logAsToast",
            "censorCoordinates", "censorRanCommands", "echoCommands", "desktopNotifications", "notificationOnPathComplete",
            "guiKey", "guiKeyEnabled", "guiDarkMode", "guiAnimations", "guiMenus", "guiMenuMusic", "keepFpsWhileBotting", "doBedWaypoints", "doDeathWaypoints", "backfill", "autoTorch",
            "followRadius", "followOffsetDistance", "followOffsetDirection", "disconnectOnArrival", "enterPortal",
            "rightClickContainerOnArrival", "splicePath", "askToGatherForPath",
            "elytraAutoJump", "elytraAutoSwap", "elytraConserveFireworks", "elytraPitchRange", "elytraFireworkSpeed",
            "elytraTermsAccepted", "elytraAllowAboveRoof", "elytraAllowAboveBuildLimit", "elytraUseCache",
            "elytraAllowLandOnNetherFortress"
    );

    private SettingCategories() {
    }

    public static boolean isAdvanced(String name) {
        return name == null || name.isEmpty() || !EVERYDAY.contains(name);
    }

    // "All" is the tab's own idea, it is not in here
    public static List<String> names() {
        List<String> out = new ArrayList<>();
        out.add(ALTO);
        out.addAll(RULES.keySet());
        out.add(GENERAL);
        return out;
    }

    public static String categoryOf(String name) {
        if (name == null || name.isEmpty()) {
            return GENERAL;
        }
        if (name.regionMatches(true, 0, "alto", 0, 4)) {
            return ALTO;
        }
        List<String> words = words(name);
        // the first word is the subject (elytraRenderSimulation is an elytra thing that happens to render), so a
        // prefix hit beats a keyword hit further in
        for (int upTo : new int[] {1, words.size()}) {
            for (Map.Entry<String, String[]> rule : RULES.entrySet()) {
                for (String key : rule.getValue()) {
                    if (matches(words.subList(0, Math.min(upTo, words.size())), key)) {
                        return rule.getKey();
                    }
                }
            }
        }
        return GENERAL;
    }

    private static boolean matches(List<String> words, String key) {
        boolean exact = key.startsWith("=");
        String k = exact ? key.substring(1) : key;
        for (String w : words) {
            if (exact ? w.equals(k) : w.startsWith(k)) {
                return true;
            }
        }
        return false;
    }

    // buildIgnoreBlocks -> [build, ignore, blocks], legitMine -> [legit, mine], mapARTMode stays sane
    static List<String> words(String name) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean upper = Character.isUpperCase(c);
            boolean prevLower = i > 0 && Character.isLowerCase(name.charAt(i - 1));
            boolean nextLower = i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1));
            boolean prevUpper = i > 0 && Character.isUpperCase(name.charAt(i - 1));
            // break before a capital that follows a lowercase, or that ends an acronym (MSFoo -> MS, Foo)
            if (cur.length() > 0 && upper && (prevLower || (prevUpper && nextLower))) {
                out.add(cur.toString().toLowerCase(Locale.ROOT));
                cur.setLength(0);
            }
            if (Character.isLetterOrDigit(c)) {
                cur.append(c);
            } else if (cur.length() > 0) {
                out.add(cur.toString().toLowerCase(Locale.ROOT));
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString().toLowerCase(Locale.ROOT));
        }
        // "map art" is two words in the name (mapArtMode) and one in the rule
        for (int i = 0; i + 1 < out.size(); i++) {
            if (out.get(i).equals("map") && out.get(i + 1).equals("art")) {
                out.set(i, "mapart");
                out.remove(i + 1);
            }
        }
        return out;
    }
}
