package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.AltoSettings;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.entity.AbstractKillEntityTask;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.speedrun.gamer.EndermanNook;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.input.Input;
import baritone.behavior.CombatAim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// the pearl hunt from a hole in a hill. dig (or find) a one wide tunnel two deep and two high with a lid, stand in its mouth and
// stare at an enderman until it takes the bait, step back to the back wall and hit its legs when it comes (it is 2.9 tall, the
// tunnel is not, so it stays in the mouth and one enderman at a time is all that can ever reach us). EndermanNook has the rules
public class NookHuntTask extends Task {
    private static final int SEARCH = 7;
    private static final long BUILD_TICKS = 1600;
    // nobody to stare at, nothing to fight and nothing to fetch for this long and the hole is not paying, hunt somewhere else
    private static final long IDLE_TICKS = 1200;
    // a stare that gets no answer this long is a stare at an enderman that does not care, try another
    private static final int STARE_TICKS = EndermanNook.STARE_TICKS;
    private static final long SKIP_TICKS = 400;
    private static final double STARE_RANGE = EndermanNook.STARE_RANGE;
    private static final double SWING_RANGE = 4.5;
    private static final double PEARL_RANGE = 10;
    private static final int PLACE_TICKS = 120;
    // an angry one this close is fought right away even if the nook is not finished
    private static final double HURRY_RANGE = 16;
    private static final int FIGHT_GIVEUP_SECONDS = 60;
    // the pearl is somebody else's problem after this many ticks of trying to reach it (down a hole, stuck in a wall)
    private static final int PEARL_GIVEUP_TICKS = 600;

    private final Object keys = new Object();
    private BlockPos mouth;
    private Direction dir;
    private boolean failed;
    private long startedAt = Long.MIN_VALUE;
    private long idleSince = Long.MIN_VALUE;
    private int stareTicks;
    private int placeTicks;
    private int stareId = -1;
    // enderman id -> game tick we stop caring that it ignored a stare
    private final Map<Integer, Long> skipped = new HashMap<>();
    private KillEntityTask brawl;
    private int brawlId = -1;
    // shared with the hunt that built us, see PearlHuntTask.AngryClock
    private final PearlHuntTask.AngryClock fightClock;
    private int pearlId = -1;
    private int pearlTicks;
    private final Set<Integer> pearlGivenUp = new HashSet<>();

    public NookHuntTask(PearlHuntTask.AngryClock fightClock) {
        this.fightClock = fightClock;
    }

    public boolean failed() {
        return failed;
    }

    @Override
    protected void onStart(AltoClef mod) {
        // (runs again after every interrupt, nothing here: the clocks are the task's, not the interrupt's)
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getInputControls().releaseOwnedBy(keys);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return failed || (idleSince != Long.MIN_VALUE && mod.getWorld().getGameTime() - idleSince > IDLE_TICKS);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other == this;
    }

    @Override
    protected String toDebugString() {
        return "Hunting endermen from a nook";
    }

    // ---- the tick

    @Override
    protected Task onTick(AltoClef mod) {
        Level level = mod.getWorld();
        LocalPlayer player = mod.getPlayer();
        long now = level.getGameTime();
        if (startedAt == Long.MIN_VALUE) {
            startedAt = now;
        }
        if (mouth == null && !find(mod, level, player)) {
            failed = true;
            return null;
        }
        // (every branch below that wants a key holds it again this tick, a child task must not inherit a stuck sneak)
        mod.getInputControls().releaseOwnedBy(keys);
        List<Enderman> men = mod.getEntityTracker().getTrackedEntities(Enderman.class);
        Enderman angry = nearestAngry(men, player, now);
        if (!built(level)) {
            if (now - startedAt > BUILD_TICKS) {
                failed = true;
                return null;
            }
            idleSince = Long.MIN_VALUE;
            // one that is already coming does not wait for the roof, it gets the plain fight until the hole is ready
            if (angry != null && angry.distanceTo(player) <= HURRY_RANGE) {
                if (brawl == null || brawlId != angry.getId() || brawl.stopped()) {
                    brawl = new KillEntityTask(angry);
                    brawlId = angry.getId();
                }
                setDebugState("Fighting before the nook is ready.");
                return brawl;
            }
            return build(mod, level, player);
        }
        if (idleSince == Long.MIN_VALUE) {
            idleSince = now;
        }
        if (angry != null) {
            idleSince = now;
            // fight time is per enderman and lives on the hunt, so a fresh nook does not hand an unreachable one another minute
            if (fightClock.tick(angry.getId(), FIGHT_GIVEUP_SECONDS)) {
                // this one is given up for good (nearestAngry skips it now) but the nook is fine, it ends on its own idle clock
                return null;
            }
            return fight(mod, player, angry);
        }
        Task pearl = fetchPearl(mod, level, player);
        if (pearl != null) {
            idleSince = now;
            setDebugState("Stepping out for the pearl.");
            return pearl;
        }
        Enderman bait = baitFor(men, player, now);
        boolean lava = lavaWithin(level, player.blockPosition(), EndermanNook.AGGRO_LAVA_GAP);
        if (bait != null && EndermanNook.mayAggro(player.isOnFire(), player.getHealth(), lava ? 1 : 99, countAngry(men), bait.distanceTo(player))) {
            idleSince = now;
            return stare(mod, player, bait);
        }
        stareTicks = 0;
        stareId = -1;
        return rest(mod, player);
    }

    // ---- the hole

    private BlockPos c1() {
        return mouth.relative(dir);
    }

    private BlockPos c2() {
        return mouth.relative(dir, 2);
    }

    private List<BlockPos> cells() {
        return List.of(c1(), c1().above(), c2(), c2().above());
    }

    // the far one first: once the near lid is in, it is between our eye and every face the far one could be clicked on
    private List<BlockPos> lids() {
        return List.of(c2().above(2), c1().above(2));
    }

    private boolean built(Level level) {
        for (BlockPos p : cells()) {
            if (!passable(level, p)) {
                return false;
            }
        }
        for (BlockPos p : lids()) {
            if (!solid(level, p)) {
                return false;
            }
        }
        return true;
    }

    private Task build(AltoClef mod, Level level, LocalPlayer player) {
        for (BlockPos cell : cells()) {
            if (!passable(level, cell)) {
                setDebugState("Digging the nook.");
                return new DestroyBlockTask(cell);
            }
        }
        // the lid goes on from the mouth, the cells are open now
        for (BlockPos lid : lids()) {
            if (solid(level, lid)) {
                continue;
            }
            if (!atMouth(player)) {
                setDebugState("Back to the mouth to roof it.");
                return new GetToBlockTask(mouth);
            }
            if (++placeTicks > PLACE_TICKS * 3) {
                failed = true;
                return null;
            }
            setDebugState("Roofing the nook.");
            place(mod, player, level, lid);
            return null;
        }
        return null;
    }

    private boolean atMouth(LocalPlayer player) {
        return Math.abs(player.getX() - (mouth.getX() + 0.5)) < 0.8 && Math.abs(player.getZ() - (mouth.getZ() + 0.5)) < 0.8
                && player.blockPosition().getY() == mouth.getY();
    }

    // one tick of putting a block in a lid cell: click a wall next to it (or the lid beside it)
    private void place(AltoClef mod, LocalPlayer player, Level level, BlockPos cell) {
        Item[] blocks = blocksInBag(mod);
        if (blocks.length == 0) {
            return;
        }
        Direction face = null;
        BlockPos support = null;
        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN) {
                continue;
            }
            BlockPos n = cell.relative(d);
            if (level.getFluidState(n).isEmpty() && level.getBlockState(n).isFaceSturdy(level, n, d.getOpposite())) {
                support = n;
                face = d.getOpposite();
                break;
            }
        }
        if (support == null) {
            return;
        }
        // sneak first: a furnace or a table in the wall would eat the click as a use
        mod.getInputControls().hold(Input.SNEAK, keys);
        if (!StorageHelper.isEquipped(mod, blocks)) {
            mod.getSlotHandler().forceEquipItem(blocks, true);
            return;
        }
        Vec3 at = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        mod.getClientBaritone().getPathingBehavior().requestPause();
        LookHelper.lookAt(mod, at);
        // our own ray along the look we just set, the crosshair is a frame behind it
        HitResult hit = RayTraceUtils.rayTraceTowards(player, LookHelper.getLookRotation(player), 4.5);
        if (!(hit instanceof BlockHitResult bhit) || !bhit.getBlockPos().equals(support) || bhit.getDirection() != face) {
            return;
        }
        InteractionResult result = Minecraft.getInstance().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, bhit);
        if (result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        }
    }

    // the blocks we may put over our head: the throwaway list first, then the usual suspects. never anything that falls
    private static Item[] blocksInBag(AltoClef mod) {
        Set<Item> all = new LinkedHashSet<>();
        for (Item item : AltoSettings.getThrowawayItems(mod, true)) {
            if (item instanceof BlockItem block && !(block.getBlock() instanceof FallingBlock)) {
                all.add(item);
            }
        }
        all.add(Items.NETHERRACK);
        all.add(Items.COBBLESTONE);
        all.add(Items.BLACKSTONE);
        all.add(Items.COBBLED_DEEPSLATE);
        all.add(Items.DIRT);
        List<Item> have = new ArrayList<>();
        for (Item item : all) {
            if (mod.getItemStorage().hasItem(item)) {
                have.add(item);
            }
        }
        return have.toArray(new Item[0]);
    }

    // ---- looking for the spot

    private boolean find(AltoClef mod, Level level, LocalPlayer player) {
        BlockPos feet = player.blockPosition();
        int bag = mod.getItemStorage().getItemCount(blocksInBag(mod));
        double best = Double.POSITIVE_INFINITY;
        for (int dx = -SEARCH; dx <= SEARCH; dx++) {
            for (int dz = -SEARCH; dz <= SEARCH; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos m = feet.offset(dx, dy, dz);
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        double walk = Math.sqrt(dx * dx + dz * dz);
                        if (walk >= best) {
                            continue;
                        }
                        Probe p = probe(level, m, d, bag);
                        if (!EndermanNook.spotOk(p.spot())) {
                            continue;
                        }
                        double cost = EndermanNook.spotCost(walk, p.dig(), p.spot().lidMissing());
                        if (cost < best) {
                            best = cost;
                            mouth = m.immutable();
                            dir = d;
                        }
                    }
                }
            }
        }
        return mouth != null;
    }

    private record Probe(EndermanNook.Spot spot, int dig) {
    }

    private static Probe probe(Level level, BlockPos m, Direction d, int bag) {
        BlockPos c1 = m.relative(d);
        BlockPos c2 = m.relative(d, 2);
        Direction left = d.getClockWise();
        Direction right = d.getCounterClockWise();
        boolean free = true;
        int dig = 0;
        int supply = bag;
        for (BlockPos c : new BlockPos[]{c1, c1.above(), c2, c2.above()}) {
            if (!passable(level, c)) {
                dig++;
                // only what lands in the bag as something that goes on a lid
                if (level.getBlockState(c).is(Blocks.NETHERRACK) || level.getBlockState(c).is(Blocks.BLACKSTONE)) {
                    supply++;
                }
            }
            free &= diggable(level, c);
        }
        boolean floor = sturdyUp(level, c1.below()) && sturdyUp(level, c2.below()) && sturdyUp(level, m.below());
        boolean sides = true;
        for (BlockPos c : new BlockPos[]{c1, c2}) {
            for (int up = 0; up < 2; up++) {
                sides &= solid(level, c.above(up).relative(left)) && solid(level, c.above(up).relative(right));
            }
        }
        boolean back = solid(level, c2.relative(d)) && solid(level, c2.relative(d).above());
        boolean mouthOpen = passable(level, m) && passable(level, m.above()) && passable(level, m.above(2));
        int lidMissing = 0;
        boolean lidBlocked = false;
        // the far one first, same order the task puts them on: the near one can lean on it
        boolean farLidThere = true;
        for (BlockPos lid : new BlockPos[]{c2.above(2), c1.above(2)}) {
            BlockState s = level.getBlockState(lid);
            if (solid(level, lid) && !(s.getBlock() instanceof FallingBlock)) {
                continue;
            }
            boolean leansOnFar = lid.equals(c1.above(2)) && farLidThere;
            if (solid(level, lid) || !passable(level, lid) || !(hasSupport(level, lid) || leansOnFar)) {
                lidBlocked = true;
                farLidThere &= !lid.equals(c2.above(2));
                continue;
            }
            lidMissing++;
        }
        boolean lava = false;
        for (BlockPos c : new BlockPos[]{c1, c1.above(), c2, c2.above()}) {
            lava |= lavaWithin(level, c, 1);
        }
        lava |= lavaWithin(level, m, 2);
        return new Probe(new EndermanNook.Spot(free, floor, sides, back, mouthOpen, lidMissing, lidBlocked, lava, supply), dig);
    }

    // a lid cell with something to click: a side, the back, or the other lid (which is there or is about to be)
    private static boolean hasSupport(Level level, BlockPos lid) {
        for (Direction n : Direction.values()) {
            if (n == Direction.DOWN) {
                continue;
            }
            BlockPos p = lid.relative(n);
            if (level.getBlockState(p).isFaceSturdy(level, p, n.getOpposite())) {
                return true;
            }
        }
        return false;
    }

    private static boolean passable(Level level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.getCollisionShape(level, p).isEmpty() && s.getFluidState().isEmpty();
    }

    private static boolean solid(Level level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.isCollisionShapeFullBlock(level, p) && s.getFluidState().isEmpty();
    }

    private static boolean sturdyUp(Level level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.isFaceSturdy(level, p, Direction.UP) && s.getFluidState().isEmpty();
    }

    // air, or a block a pick takes in a moment that is no chest and drops nothing on us
    private static boolean diggable(Level level, BlockPos p) {
        if (passable(level, p)) {
            return true;
        }
        BlockState s = level.getBlockState(p);
        float hardness = s.getDestroySpeed(level, p);
        return solid(level, p) && !(s.getBlock() instanceof FallingBlock) && !s.hasBlockEntity() && hardness >= 0 && hardness <= 3;
    }

    private static boolean lavaWithin(Level level, BlockPos at, int r) {
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    if (level.getFluidState(at.offset(x, y, z)).is(FluidTags.LAVA)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ---- standing in it

    // our middle along the tunnel, from the middle of the cell outside the mouth
    private double inFromMouth(LocalPlayer player) {
        return (player.getX() - (mouth.getX() + 0.5)) * dir.getStepX() + (player.getZ() - (mouth.getZ() + 0.5)) * dir.getStepZ();
    }

    private double offAxis(LocalPlayer player) {
        Direction side = dir.getClockWise();
        return Math.abs((player.getX() - (mouth.getX() + 0.5)) * side.getStepX() + (player.getZ() - (mouth.getZ() + 0.5)) * side.getStepZ());
    }

    private Vec3 floorOutside() {
        return new Vec3(mouth.getX() + 0.5, mouth.getY(), mouth.getZ() + 0.5);
    }

    // at the back wall and out of an enderman's reach, or on the way there. gaze on the floor of the mouth, nowhere near a face
    private Task rest(AltoClef mod, LocalPlayer player) {
        double in = inFromMouth(player);
        if (in < 1.2 || offAxis(player) > 0.45 || player.blockPosition().getY() != mouth.getY()) {
            mod.getInputControls().releaseOwnedBy(keys);
            setDebugState("Back into the nook.");
            return new GetToBlockTask(c2());
        }
        double creep = EndermanNook.creepBy(in);
        if (creep > 0) {
            setDebugState("Pressing against the back wall.");
            mod.getClientBaritone().getPathingBehavior().requestPause();
            LookHelper.lookAt(mod, new Vec3(c2().getX() + 0.5 + dir.getStepX() * 0.5, c2().getY(), c2().getZ() + 0.5 + dir.getStepZ() * 0.5));
            mod.getInputControls().hold(Input.SNEAK, keys);
            mod.getInputControls().hold(Input.MOVE_FORWARD, keys);
            return null;
        }
        mod.getInputControls().releaseOwnedBy(keys);
        setDebugState("Waiting in the nook.");
        LookHelper.lookAt(mod, floorOutside());
        return null;
    }

    private Task fight(AltoClef mod, LocalPlayer player, Enderman angry) {
        // (a hair of slack: the creep stops a hair past the line and a knock can take it back)
        if (!EndermanNook.standDeepEnough(inFromMouth(player) + 0.05)) {
            // the gaze comes off its face on the very tick it turns angry (the walk back picks its own look once its path
            // is up, which is a tick or two too late)
            LookHelper.lookAt(mod, floorOutside());
            return rest(mod, player);
        }
        mod.getInputControls().releaseOwnedBy(keys);
        setDebugState("Fighting an enderman from the nook.");
        double dist = angry.distanceTo(player);
        if (dist > SWING_RANGE) {
            LookHelper.lookAt(mod, floorOutside());
            return null;
        }
        mod.getControllerExtras().aimAt(angry, CombatAim.Priority.KILL_TASK);
        if (!AbstractKillEntityTask.equipWeapon(mod, true, angry) && (mod.getControllerExtras().attackReady() || mod.getControllerExtras().wantsCritTick())) {
            mod.getControllerExtras().melee(angry);
        }
        return null;
    }

    // ---- the bait

    private Task stare(AltoClef mod, LocalPlayer player, Enderman bait) {
        if (!atMouth(player)) {
            mod.getInputControls().releaseOwnedBy(keys);
            setDebugState("To the mouth to get an enderman's attention.");
            return new GetToBlockTask(mouth);
        }
        if (bait.getId() != stareId) {
            stareId = bait.getId();
            stareTicks = 0;
        }
        if (++stareTicks > STARE_TICKS) {
            skipped.put(bait.getId(), mod.getWorld().getGameTime() + SKIP_TICKS);
            stareTicks = 0;
            stareId = -1;
            return null;
        }
        mod.getInputControls().releaseOwnedBy(keys);
        setDebugState("Staring an enderman down.");
        LookHelper.lookAt(mod, bait.getEyePosition());
        return null;
    }

    private Enderman baitFor(List<Enderman> men, LocalPlayer player, long now) {
        Enderman best = null;
        for (Enderman man : men) {
            if (!man.isAlive() || man.isCreepy() || skipped.getOrDefault(man.getId(), 0L) > now) {
                continue;
            }
            double d = man.distanceTo(player);
            if (d > STARE_RANGE || !man.hasLineOfSight(player)) {
                continue;
            }
            if (best == null || d < best.distanceTo(player)) {
                best = man;
            }
        }
        return best;
    }

    private int countAngry(List<Enderman> men) {
        int n = 0;
        for (Enderman man : men) {
            if (man.isAlive() && man.isCreepy() && !fightClock.givenUp(man.getId())) {
                n++;
            }
        }
        return n;
    }

    private Enderman nearestAngry(List<Enderman> men, LocalPlayer player, long now) {
        double[] d = new double[men.size()];
        for (int i = 0; i < d.length; i++) {
            Enderman man = men.get(i);
            boolean skip = fightClock.givenUp(man.getId());
            d[i] = man.isAlive() && man.isCreepy() && !skip ? man.distanceTo(player) : Double.NaN;
        }
        int at = EndermanNook.nearestAngry(d);
        return at < 0 ? null : men.get(at);
    }

    // the drop of a kill, when nothing else angry is about and it is not lying next to lava
    private Task fetchPearl(AltoClef mod, Level level, LocalPlayer player) {
        Optional<ItemEntity> drop = mod.getEntityTracker().getClosestItemDrop(e -> !pearlGivenUp.contains(e.getId()), Items.ENDER_PEARL);
        if (drop.isEmpty()) {
            return null;
        }
        ItemEntity pearl = drop.get();
        Vec3 spot = floorOutside();
        if (pearl.position().distanceTo(spot) > PEARL_RANGE || lavaWithin(level, pearl.blockPosition(), 2)) {
            return null;
        }
        if (pearl.getId() != pearlId) {
            pearlId = pearl.getId();
            pearlTicks = 0;
        }
        if (++pearlTicks > PEARL_GIVEUP_TICKS) {
            pearlGivenUp.add(pearlId);
            return null;
        }
        // walk onto it: the pickup task picks its own pearl and could go for the one we gave up on
        return new GetToBlockTask(pearl.blockPosition());
    }
}
