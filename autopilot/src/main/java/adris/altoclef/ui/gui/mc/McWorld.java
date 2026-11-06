package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.facade.CommandsAccess;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.WorldAccess;
import adris.altoclef.ui.gui.kit.Safe;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.cache.IWaypoint;
import baritone.api.cache.IWorldData;
import baritone.api.cache.Waypoint;
import baritone.api.process.IFollowProcess;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

// where you are, who is around, and the go to / follow / waypoint actions. the actions run the commands you would have
// typed (follow, goto, goal, wp) so the behaviour is exactly chat's; the lists are read straight off the client
public final class McWorld implements WorldAccess {

    // the follow tab shows at most this many mobs, nearest first
    private static final int ENTITY_CAP = 256;

    private final CommandsAccess runner;

    // what we asked baritone to follow and the filter it was handed, so following() can say "all players" and not
    // just count heads. a follow typed in chat has a different filter and falls back to describing the entities
    private Predicate<Entity> askedFilter;
    private String askedLabel;
    private boolean askedStanding;

    public McWorld() {
        this(new McCommands());
    }

    McWorld(CommandsAccess runner) {
        this.runner = runner;
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    @Override
    public Position playerPos() {
        try {
            Player p = mc().player;
            if (p == null) {
                return null;
            }
            return new Position(p.getX(), p.getY(), p.getZ());
        } catch (Throwable t) {
            Safe.report("gui playerPos", t);
            return null;
        }
    }

    @Override
    public float playerYaw() {
        try {
            Player p = mc().player;
            return p == null ? 0f : p.getYRot();
        } catch (Throwable t) {
            Safe.report("gui playerYaw", t);
            return 0f;
        }
    }

    @Override
    public String dimension() {
        try {
            ClientLevel level = mc().level;
            return level == null ? "overworld" : level.dimension().identifier().getPath();
        } catch (Throwable t) {
            Safe.report("gui dimension", t);
            return "overworld";
        }
    }

    @Override
    public List<PlayerEntry> players() {
        List<PlayerEntry> out = new ArrayList<>();
        try {
            Minecraft mc = mc();
            ClientLevel level = mc.level;
            Player me = mc.player;
            if (level == null || me == null) {
                return out;
            }
            Set<UUID> seen = new HashSet<>();
            List<PlayerEntry> loaded = new ArrayList<>();
            for (AbstractClientPlayer p : level.players()) {
                seen.add(p.getUUID());
                boolean self = p.getUUID().equals(me.getUUID());
                loaded.add(new PlayerEntry(p.getGameProfile().name(), self ? 0 : p.distanceTo(me), self));
            }
            loaded.sort(Comparator.comparingDouble(PlayerEntry::distance));
            out.addAll(loaded);
            // the tab list knows people we cannot see, they are still worth a name
            if (mc.getConnection() != null) {
                List<PlayerEntry> far = new ArrayList<>();
                for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                    if (seen.add(info.getProfile().id())) {
                        far.add(new PlayerEntry(info.getProfile().name(), -1, false));
                    }
                }
                far.sort(Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT)));
                out.addAll(far);
            }
        } catch (Throwable t) {
            Safe.report("gui players", t);
        }
        return out;
    }

    @Override
    public List<EntityEntry> entities() {
        List<EntityEntry> out = new ArrayList<>();
        try {
            Minecraft mc = mc();
            ClientLevel level = mc.level;
            Player me = mc.player;
            if (level == null || me == null) {
                return out;
            }
            // living things only, same as "follow entities": an item on the floor is not company. armor stands are
            // living too, and servers use them as floating text by the dozen
            for (Entity e : level.entitiesForRendering()) {
                if (e == me || e instanceof Player || !(e instanceof LivingEntity) || e instanceof ArmorStand
                        || !e.isAlive()) {
                    continue;
                }
                EntityType<?> type = e.getType();
                Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                String custom = e.hasCustomName() ? e.getCustomName().getString() : "";
                out.add(new EntityEntry(e.getId(), key.toString(), type.getDescription().getString(), custom,
                        e.distanceTo(me), IconResolver.forEntityType(type)));
            }
            out.sort(Comparator.comparingDouble(EntityEntry::distance));
            if (out.size() > ENTITY_CAP) {
                return new ArrayList<>(out.subList(0, ENTITY_CAP));
            }
        } catch (Throwable t) {
            Safe.report("gui entities", t);
        }
        return out;
    }

    @Override
    public Result followPlayer(String name) {
        if (name == null || name.isBlank() || name.chars().anyMatch(Character::isWhitespace)) {
            return Result.fail("Pick a player first.");
        }
        try {
            Minecraft mc = mc();
            if (mc.level != null && mc.player != null) {
                if (name.equalsIgnoreCase(mc.player.getGameProfile().name())) {
                    return Result.fail("That is you. Pick someone else.");
                }
                // follow swaps in an empty filter before it finds out nobody is there, so a far name must not get that far
                boolean near = mc.level.players().stream().anyMatch(p -> p.getGameProfile().name().equalsIgnoreCase(name));
                if (!near) {
                    return Result.fail(name + " is too far away to follow.");
                }
            }
        } catch (Throwable t) {
            Safe.report("gui followPlayer", t);
        }
        return follow("follow player " + name, "Following " + name, name, true);
    }

    @Override
    public Result followEntity(int id) {
        try {
            ClientLevel level = mc().level;
            Entity e = level == null ? null : level.getEntity(id);
            if (e == null || !e.isAlive()) {
                return Result.fail("That one is gone. Pick another.");
            }
            // chat has no word for one specific mob, so this goes to the process directly
            Predicate<Entity> filter = other -> other.getId() == id;
            String name = e.getName().getString();
            baritone().getFollowProcess().follow(filter);
            remember(filter, name, false);
            return Result.ok("Following " + name);
        } catch (Throwable t) {
            Safe.report("gui followEntity", t);
            return Result.fail("Could not follow that.");
        }
    }

    @Override
    public Result followType(String typeId) {
        try {
            Identifier rl = typeId == null ? null : Identifier.tryParse(typeId);
            EntityType<?> type = rl == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(rl).orElse(null);
            if (type == null) {
                return Result.fail("No mob called " + typeId + ".");
            }
            String plural = type.getDescription().getString().toLowerCase(Locale.ROOT);
            return follow("follow entity " + rl, "Following every " + plural, "every " + plural, true);
        } catch (Throwable t) {
            Safe.report("gui followType", t);
            return Result.fail("Could not follow that.");
        }
    }

    @Override
    public Result followAllPlayers() {
        return follow("follow players", "Following all players", "all players", true);
    }

    @Override
    public Result followAllEntities() {
        return follow("follow entities", "Following all entities", "all entities", true);
    }

    private Result follow(String line, String okMessage, String label, boolean standing) {
        try {
            Result r = runner.run(line);
            if (!r.ok()) {
                return Result.fail(McItems.sentence(r.message()), line);
            }
            // the command has just handed the process its filter
            remember(baritone().getFollowProcess().currentFilter(), label, standing);
            return Result.ok(okMessage, line);
        } catch (Throwable t) {
            Safe.report("gui follow", t);
            return Result.fail("Could not follow that.");
        }
    }

    // standing: "every zombie" is still the plan with none around. one named mob that died is over
    private void remember(Predicate<Entity> filter, String label, boolean standing) {
        askedFilter = filter;
        askedLabel = label;
        askedStanding = standing;
    }

    @Override
    public Result stopFollowing() {
        try {
            baritone().getFollowProcess().cancel();
            askedFilter = null;
            askedLabel = null;
            return Result.ok("Stopped following", "stop");
        } catch (Throwable t) {
            Safe.report("gui stopFollowing", t);
            return Result.fail("Could not stop.");
        }
    }

    @Override
    public String following() {
        try {
            IFollowProcess p = baritone().getFollowProcess();
            Predicate<Entity> filter = p.currentFilter();
            if (filter == null) {
                return null;
            }
            List<Entity> caught = p.following();
            boolean any = caught != null && !caught.isEmpty();
            if (filter == askedFilter) {
                return any || askedStanding ? askedLabel : null;
            }
            // somebody typed #follow, say what it caught
            if (!any) {
                return null;
            }
            return caught.size() == 1 ? caught.get(0).getName().getString() : caught.size() + " entities";
        } catch (Throwable t) {
            Safe.report("gui following", t);
            return null;
        }
    }

    @Override
    public List<WaypointEntry> waypoints() {
        List<WaypointEntry> out = new ArrayList<>();
        try {
            IWorldData world = baritone().getWorldProvider().getCurrentWorld();
            if (world == null) {
                return out;
            }
            for (IWaypoint w : world.getWaypoints().getAllWaypoints()) {
                BetterBlockPos at = w.getLocation();
                out.add(new WaypointEntry(w.getName(), w.getTag().getName(), at.x, at.y, at.z, w.getCreationTimestamp()));
            }
            out.sort(Comparator.comparingLong(WaypointEntry::createdMillis).reversed());
        } catch (Throwable t) {
            Safe.report("gui waypoints", t);
        }
        return out;
    }

    @Override
    public List<String> waypointTags() {
        List<String> out = new ArrayList<>();
        for (IWaypoint.Tag tag : IWaypoint.Tag.values()) {
            out.add(tag.getName());
        }
        return out;
    }

    @Override
    public Result addWaypointHere(String name, String tag) {
        try {
            IWaypoint.Tag t = tag == null ? IWaypoint.Tag.USER : IWaypoint.Tag.getByName(tag);
            if (t == null) {
                return Result.fail("No tag called " + tag + ". Try " + String.join(", ", waypointTags()) + ".");
            }
            IWorldData world = baritone().getWorldProvider().getCurrentWorld();
            if (world == null || mc().player == null) {
                return Result.fail("Join a world first.");
            }
            String clean = name == null ? "" : name.trim();
            BetterBlockPos at = baritone().getPlayerContext().playerFeet();
            String shown = clean.isEmpty() ? "your spot" : clean;
            String line = saveLine(clean, t, at);
            if (line == null) {
                // a name with a space cannot be typed, so chat cannot save it and the api has to
                world.getWaypoints().addWaypoint(new Waypoint(clean, t, at));
                return Result.ok("Saved " + shown);
            }
            Result r = runner.run(line);
            if (!r.ok()) {
                return Result.fail(McItems.sentence(r.message()), line);
            }
            return Result.ok("Saved " + shown, line);
        } catch (Throwable e) {
            Safe.report("gui addWaypoint", e);
            return Result.fail("Could not save that.");
        }
    }

    @Override
    public Result deleteWaypoint(WaypointEntry waypoint) {
        try {
            if (waypoint == null) {
                return Result.fail("Pick a waypoint first.");
            }
            String line = deleteLine(waypoint);
            Result r = runner.run(line);
            if (!r.ok()) {
                return Result.fail(McItems.sentence(r.message()), line);
            }
            String shown = waypoint.name().isEmpty() ? "waypoint" : waypoint.name();
            return Result.ok("Deleted " + shown, line);
        } catch (Throwable t) {
            Safe.report("gui deleteWaypoint", t);
            return Result.fail("Could not delete that.");
        }
    }

    @Override
    public Result goTo(Integer x, Integer y, Integer z) {
        return goal("goto", "Going to ", x, y, z);
    }

    @Override
    public Result setGoal(Integer x, Integer y, Integer z) {
        return goal("goal", "Goal set to ", x, y, z);
    }

    private Result goal(String verb, String lead, Integer x, Integer y, Integer z) {
        try {
            String coords = coords(x, y, z);
            if (coords == null) {
                return Result.fail("Give x and z, y alone, or all three.");
            }
            String line = verb + " " + coords;
            Result r = runner.run(line);
            if (!r.ok()) {
                return Result.fail(McItems.sentence(r.message()), line);
            }
            return Result.ok(lead + where(x, y, z), line);
        } catch (Throwable t) {
            Safe.report("gui " + verb, t);
            return Result.fail("Could not do that.");
        }
    }

    // what the goal command takes: "y", "x z" or "x y z". anything else is not a goal
    static String coords(Integer x, Integer y, Integer z) {
        if (x != null && y != null && z != null) {
            return x + " " + y + " " + z;
        }
        if (x != null && y == null && z != null) {
            return x + " " + z;
        }
        if (x == null && y != null && z == null) {
            return String.valueOf(y);
        }
        return null;
    }

    static String where(Integer x, Integer y, Integer z) {
        if (x != null && y != null && z != null) {
            return x + " " + y + " " + z;
        }
        if (x != null && z != null) {
            return "x " + x + ", z " + z;
        }
        return "y " + y;
    }

    // "wp save user home 10 64 -5". a name with a space cannot be typed, so there is no line to show for it
    static String saveLine(String name, IWaypoint.Tag tag, BetterBlockPos at) {
        if (name.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        String pos = at.x + " " + at.y + " " + at.z;
        return "wp save " + tag.getName() + " " + (name.isEmpty() ? "" : name + " ") + pos;
    }

    // the tag and the timestamp pick exactly one waypoint, names can repeat
    static String deleteLine(WaypointEntry w) {
        return "wp delete " + w.tag() + " @ " + w.createdMillis();
    }
}
