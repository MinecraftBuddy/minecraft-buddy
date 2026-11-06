package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasksystem.Task;
import baritone.pathing.movement.CliffAvoidance;
import baritone.utils.BlockStateInterface;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

// the game's half of StandModel: the world, the buddy and the mobs about as the model takes them, a new look every EVERY
// ticks, the walk to the cell, and one log line each time the cell changes. one per caller (the rods task's run, the mob
// defense's run, the ghast shelter), each holding its own cell
public final class StandPick {
    // a new look this often (ticks); between looks the cell held stands
    static final int EVERY = 5;
    // a cell not reached in this long is left out of this caller's picks until reset (BlazeFight's cover had the same 160)
    static final long WALK_TICKS = 160;
    // the mobs within this of the buddy are handed to the model (a ghast shoots from 64)
    static final double MOB_RANGE = 64;

    private final String who;
    private StandModel.Cost held;
    private long lookedAt = Long.MIN_VALUE;
    private long heldSince;
    private String lastWhy;
    private final Set<Long> banned = new HashSet<>();
    private GetToBlockTask walk;
    private BlockPos walkTo;

    public StandPick(String who) {
        this.who = who;
    }

    public static boolean on() {
        return StandModel.on();
    }

    public void reset() {
        held = null;
        lookedAt = Long.MIN_VALUE;
        lastWhy = null;
        banned.clear();
        walk = null;
        walkTo = null;
    }

    // the cell the model has for us now ({x, y, z} as a block), or null for none (the caller's own rule then). extras are cells
    // to weigh besides the ones walked to (a corner), target the one we fight (null for none), q what is asked
    public BlockPos cell(AltoClef mod, StandModel.Query q, List<BlockPos> extras, Entity target) {
        LocalPlayer player = mod.getPlayer();
        Level level = mod.getWorld();
        if (player == null || level == null || mod.getClientBaritone().getPlayerContext() == null) return null;
        long now = level.getGameTime();
        if (now < lookedAt) reset();
        if (lookedAt != Long.MIN_VALUE && now - lookedAt < EVERY) return held == null ? null : pos(held);
        lookedAt = now;
        // (the walk's clock runs while we are not in the cell: one reached and left again gets its time anew)
        if (held != null && player.blockPosition().equals(pos(held))) heldSince = now;
        if (held != null && !player.blockPosition().equals(pos(held)) && now - heldSince > WALK_TICKS) {
            banned.add(BlazeCover.key(held.x(), held.y(), held.z()));
            Debug.logInternal("stand: " + who + ", " + pos(held).toShortString() + " not reached in " + WALK_TICKS + " ticks, left out");
            held = null;
        }
        List<int[]> more = new ArrayList<>();
        for (BlockPos e : extras) if (e != null) more.add(new int[]{e.getX(), e.getY(), e.getZ()});
        StandModel.View view = view(mod, level);
        StandModel.Buddy buddy = new StandModel.Buddy(player.getX(), player.getY(), player.getZ(), FireRes.has(player));
        List<StandModel.Mob> mobs = mobs(mod, player);
        StandModel.Target t = target == null || !target.isAlive() ? null
                : new StandModel.Target(target.getX(), target.getY(), target.getZ(), target.getBbWidth(), target.getBbHeight());
        long t0 = System.nanoTime();
        StandModel.Choice c = StandModel.choose(view, buddy, mobs, t, q.extras(more).banned(banned), held == null ? null : held.cell());
        long micros = (System.nanoTime() - t0) / 1000;
        if (c.cell() == null) {
            String why = c.why();
            if (held != null || !why.equals(lastWhy)) {
                Debug.logInternal("stand: " + who + ", no cell: " + why + " (" + c.looked() + " cells, " + mobs.size() + " mobs, " + micros + " us)");
            }
            lastWhy = why;
            held = null;
            return null;
        }
        lastWhy = null;
        if (held == null || c.changed()) {
            StandModel.Cost was = c.held();
            Debug.logInternal("stand: " + who + ", to " + pos(c.cell()).toShortString() + ", " + c.cell().breakdown()
                    + (was == null ? "" : String.format(Locale.ROOT, "; was %s at %.1f", pos(was).toShortString(), was.total()))
                    + "; " + c.looked() + " cells, " + mobs.size() + " mobs, " + micros + " us");
            heldSince = now;
        }
        held = c.cell();
        return pos(held);
    }

    // the walk to the held cell, null standing in it or with none
    public Task walk(AltoClef mod) {
        if (held == null) return null;
        BlockPos p = pos(held);
        if (mod.getPlayer().blockPosition().equals(p)) return null;
        if (walk == null || !p.equals(walkTo)) {
            walk = new GetToBlockTask(p);
            walkTo = p;
        }
        return walk;
    }

    public BlockPos held() {
        return held == null ? null : pos(held);
    }

    private static BlockPos pos(StandModel.Cost c) {
        return new BlockPos(c.x(), c.y(), c.z());
    }

    static boolean wearsGold(LocalPlayer p) {
        return p.getItemBySlot(EquipmentSlot.HEAD).is(Items.GOLDEN_HELMET) || p.getItemBySlot(EquipmentSlot.CHEST).is(Items.GOLDEN_CHESTPLATE)
                || p.getItemBySlot(EquipmentSlot.LEGS).is(Items.GOLDEN_LEGGINGS) || p.getItemBySlot(EquipmentSlot.FEET).is(Items.GOLDEN_BOOTS);
    }

    // the world as the model reads it: Baritone's cells (an unloaded chunk is solid), and fire blocks
    static StandModel.View view(AltoClef mod, Level level) {
        CliffAvoidance.Cells cells = CliffAvoidance.of(new BlockStateInterface(mod.getClientBaritone().getPlayerContext(), true));
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        return new StandModel.View(cells, (x, y, z) -> level.getBlockState(at.set(x, y, z)).is(BlockTags.FIRE));
    }

    // the mobs about with a row in MobThreat, within MOB_RANGE. after us: aggressive (the flag the game syncs while a mob has a
    // target), or for one that shoots, a line from its eyes to ours; a piglin is neutral only to a buddy in gold, without any
    // it goes for one it sees within 16
    static List<StandModel.Mob> mobs(AltoClef mod, LocalPlayer player) {
        List<StandModel.Mob> out = new ArrayList<>();
        Vec3 eye = player.getEyePosition();
        boolean gold = wearsGold(player);
        for (Entity e : mod.getWorld().entitiesForRendering()) {
            if (!(e instanceof Mob mob) || !mob.isAlive() || mob.distanceTo(player) > MOB_RANGE) continue;
            String kind = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath();
            if (mob instanceof Piglin p && p.isHolding(s -> s.getItem() instanceof CrossbowItem)) kind = "piglin_crossbow";
            if (!MobThreat.known(kind)) continue;
            int size = mob instanceof Slime s ? s.getSize() : 1;
            MobThreat row = MobThreat.of(kind, size);
            boolean aware = mob.isAggressive();
            boolean sees = !aware && ((!row.neutral() && row.shot() > 0 && mob.distanceTo(player) <= row.range())
                    || (mob instanceof Piglin && !gold && mob.distanceTo(player) <= 16));
            if (sees) aware = GhastCover.clear(mod.getWorld(), mob.getEyePosition(), eye, player);
            out.add(new StandModel.Mob(kind, size, mob.getX(), mob.getY(), mob.getZ(), aware));
        }
        return out;
    }
}
