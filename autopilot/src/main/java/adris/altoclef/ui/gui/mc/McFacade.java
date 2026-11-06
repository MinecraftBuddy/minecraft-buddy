package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.facade.BotFacade;
import adris.altoclef.ui.gui.facade.CommandsAccess;
import adris.altoclef.ui.gui.facade.ItemsAccess;
import adris.altoclef.ui.gui.facade.KeyNames;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.SchematicsAccess;
import adris.altoclef.ui.gui.facade.SettingsAccess;
import adris.altoclef.ui.gui.facade.StatsAccess;
import adris.altoclef.ui.gui.facade.StatusAccess;
import adris.altoclef.ui.gui.facade.WorldAccess;
import adris.altoclef.ui.gui.kit.Clipboard;
import adris.altoclef.ui.gui.kit.Safe;

import java.util.List;
import java.util.function.Supplier;

// the real BotFacade: the game on the other side of every interface the tabs talk to
public final class McFacade implements BotFacade {

    // direct references, a lookup by name would hide the classes from a shrinker. if a constructor throws the tabs
    // get empty lists and a polite no instead. set these to plug something else in
    public static Supplier<ItemsAccess> itemsFactory = () -> guarded("McItems", McItems::new, EmptyItems::new);
    public static Supplier<WorldAccess> worldFactory = () -> guarded("McWorld", McWorld::new, EmptyWorld::new);
    // the one shared McSchematics, so the screen rebuilding itself doesn't throw the parsed folder away
    public static Supplier<SchematicsAccess> schematicsFactory = () -> guarded("McSchematics", McSchematics::shared, () -> SchematicsAccess.NONE);

    private final SettingsAccess settings;
    private final CommandsAccess commands;
    private final ItemsAccess items;
    private final WorldAccess world;
    private final StatusAccess status;
    private final KeyNames keyNames;
    private final Clipboard clipboard;
    private final StatsAccess stats;
    private final SchematicsAccess schematics;

    public McFacade(SettingsAccess settings, CommandsAccess commands, ItemsAccess items, WorldAccess world,
                    StatusAccess status, KeyNames keyNames, Clipboard clipboard, StatsAccess stats,
                    SchematicsAccess schematics) {
        this.settings = settings;
        this.commands = commands;
        this.items = items;
        this.world = world;
        this.status = status;
        this.keyNames = keyNames;
        this.clipboard = clipboard;
        this.stats = stats;
        this.schematics = schematics;
    }

    public static McFacade create() {
        return new McFacade(new McSettings(), new McCommands(), itemsFactory.get(), worldFactory.get(),
                new McStatus(), new McKeyNames(), new McClipboard(), new McStats(), schematicsFactory.get());
    }

    private static <T> T guarded(String name, Supplier<? extends T> make, Supplier<? extends T> fallback) {
        try {
            return make.get();
        } catch (Throwable t) {
            Safe.report("gui " + name, t);
            return fallback.get();
        }
    }

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
    public KeyNames keyNames() {
        return keyNames;
    }

    @Override
    public Clipboard clipboard() {
        return clipboard;
    }

    @Override
    public StatsAccess stats() {
        return stats;
    }

    @Override
    public SchematicsAccess schematics() {
        return schematics;
    }

    private static final class EmptyItems implements ItemsAccess {
        private static final Result NOPE = Result.fail("Not wired yet");

        @Override
        public List<ItemEntry> gettable() {
            return List.of();
        }

        @Override
        public List<ItemEntry> mineable() {
            return List.of();
        }

        @Override
        public Result get(List<BasketLine> lines) {
            return NOPE;
        }

        @Override
        public Result mine(List<BasketLine> lines) {
            return NOPE;
        }
    }

    private static final class EmptyWorld implements WorldAccess {
        private static final Result NOPE = Result.fail("Not wired yet");

        @Override
        public Position playerPos() {
            return null;
        }

        @Override
        public String dimension() {
            return "overworld";
        }

        @Override
        public List<PlayerEntry> players() {
            return List.of();
        }

        @Override
        public List<EntityEntry> entities() {
            return List.of();
        }

        @Override
        public Result followPlayer(String name) {
            return NOPE;
        }

        @Override
        public Result followEntity(int id) {
            return NOPE;
        }

        @Override
        public Result followType(String typeId) {
            return NOPE;
        }

        @Override
        public Result followAllPlayers() {
            return NOPE;
        }

        @Override
        public Result followAllEntities() {
            return NOPE;
        }

        @Override
        public Result stopFollowing() {
            return NOPE;
        }

        @Override
        public String following() {
            return null;
        }

        @Override
        public List<WaypointEntry> waypoints() {
            return List.of();
        }

        @Override
        public List<String> waypointTags() {
            return List.of();
        }

        @Override
        public Result addWaypointHere(String name, String tag) {
            return NOPE;
        }

        @Override
        public Result deleteWaypoint(WaypointEntry waypoint) {
            return NOPE;
        }

        @Override
        public Result goTo(Integer x, Integer y, Integer z) {
            return NOPE;
        }

        @Override
        public Result setGoal(Integer x, Integer y, Integer z) {
            return NOPE;
        }
    }
}
