package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.AltoSettings;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.GetDownColumnTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.speedrun.gamer.NightShelter;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.NightSleepHook;
import baritone.api.utils.input.Input;
import adris.altoclef.util.helpers.StorageHelper;
import baritone.api.utils.RayTraceUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// a three deep hole in the dirt with the top cell capped, for the rest of the night. NightShelter has the rules, this walks,
// digs, clicks and waits. the cap goes flush with the ground so it has four walls to be clicked against, and breaking it on the
// way out hands the block back for the pillar
public class ShelterTask extends Task {
    private enum Stage {
        WALK, DIG, CAP, WAIT, EXIT, DONE
    }

    private static final int WALK_TICKS = 400;
    private static final int DIG_TICKS = 600;
    private static final int CAP_TICKS = 300;
    private static final int EXIT_TICKS = 900;
    // a mob in the cap cell: give it this long to move before we call the cap a no
    private static final int MOB_PATIENCE_TICKS = 100;
    // the block has to read back this many ticks in a row (lag can show a block that is not there yet, or take it back)
    private static final int CONFIRM_TICKS = 5;
    // the order of the search: our own column first, then the ring around it
    private static final int REACH = 2;

    private final BlockPos top;
    private final long night;
    private Stage stage = Stage.WALK;
    private long stageSince = Long.MIN_VALUE;
    private boolean gaveUp;
    private int bounces;
    private long sealedAt;
    private long waitCap;
    private int confirmed;
    private int mobTicks;
    private float lastHealth = -1;
    private boolean lifted;
    // the sneak the centering and the cap click hold, let go of on every way out
    private final Object keys = new Object();
    // the hitbox is 0.6 wide: more than 0.2 off the middle and we still stand on the next block when ours is dug out
    private static final double CENTER_SLACK = 0.15;
    private static final long WORK_TICKS = 2000;
    private long startedAt = Long.MIN_VALUE;
    private static final int CENTER_TICKS = 60;

    // top = the cell we stand in on the surface above the hole (feet), night = NightSleep.nightOf when it began
    public ShelterTask(BlockPos top, long night) {
        this.top = top.immutable();
        this.night = night;
    }

    public BlockPos top() {
        return top;
    }

    public boolean gaveUp() {
        return gaveUp;
    }

    // sealed and nobody has hit us yet: this is when mob defense stands down
    public boolean sealed() {
        return stage == Stage.WAIT && !lifted;
    }

    // ---- the column

    // the standing cell of the first column near us that makes a good hole, null = nowhere to hide
    public static BlockPos pick(AltoClef mod) {
        return pick(mod, null);
    }

    // skip = a spot that already failed tonight, the retry wants a different hole
    public static BlockPos pick(AltoClef mod, BlockPos skip) {
        LocalPlayer player = mod.getPlayer();
        if (player == null || !player.onGround()) {
            return null;
        }
        List<BlockPos> spots = new ArrayList<>();
        List<NightShelter.Column> columns = new ArrayList<>();
        scan(mod, player, skip, spots, columns);
        int at = NightShelter.pickColumn(columns);
        return at < 0 ? null : spots.get(at);
    }

    // the log line for a pick that came back empty: not standing on the ground, or the counts of what each column failed on
    public static String whyNoColumn(AltoClef mod, BlockPos skip) {
        LocalPlayer player = mod.getPlayer();
        if (player == null || !player.onGround()) {
            return "not on ground";
        }
        List<BlockPos> spots = new ArrayList<>();
        List<NightShelter.Column> columns = new ArrayList<>();
        scan(mod, player, skip, spots, columns);
        return NightShelter.failCounts(columns) + ", nearest " + cellIds(mod.getWorld(), spots);
    }

    // what the three cells of the nearest column are made of. the counts say what failed, not what the ground was
    private static String cellIds(Level level, List<BlockPos> spots) {
        if (spots.isEmpty()) {
            return "none";
        }
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < NightShelter.DEPTH; i++) {
            if (i > 0) {
                ids.append('/');
            }
            ids.append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(spots.get(0).below(i + 1)).getBlock()).getPath());
        }
        return ids.toString();
    }

    private static void scan(AltoClef mod, LocalPlayer player, BlockPos skip, List<BlockPos> spots, List<NightShelter.Column> columns) {
        Level level = mod.getWorld();
        BlockPos feet = player.blockPosition();
        boolean havePick = havePick(mod);
        Item[] caps = capItems(mod);
        int bag = mod.getItemStorage().getItemCount(caps);
        for (int ring = 0; ring <= REACH; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    BlockPos spot = feet.offset(dx, 0, dz);
                    if (spot.equals(skip)) {
                        continue;
                    }
                    spots.add(spot);
                    columns.add(look(level, spot, havePick, bag));
                }
            }
        }
    }

    private static NightShelter.Column look(Level level, BlockPos spot, boolean havePick, int bag) {
        BlockPos[] cells = new BlockPos[NightShelter.DEPTH];
        boolean[] dig = new boolean[NightShelter.DEPTH];
        boolean fluid = false;
        boolean walls = true;
        int supply = bag;
        for (int i = 0; i < cells.length; i++) {
            cells[i] = spot.below(i + 1);
            BlockState s = level.getBlockState(cells[i]);
            // (a pick only opens plain stone: ores are not worth the swing and give nothing to cap with)
            dig[i] = NightShelter.diggable(s.is(BlockTags.MINEABLE_WITH_SHOVEL), s.is(BlockTags.BASE_STONE_OVERWORLD),
                    s.getDestroySpeed(level, cells[i]), havePick);
            fluid |= !s.getFluidState().isEmpty();
            // what the dig leaves in the bag that can go over our head: dirt for the dirt kinds, cobble for stone
            if (dig[i] && (s.is(BlockTags.DIRT) || s.is(BlockTags.BASE_STONE_OVERWORLD)) && !(s.getBlock() instanceof FallingBlock)) {
                supply++;
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = cells[i].relative(d);
                BlockState ns = level.getBlockState(n);
                fluid |= !ns.getFluidState().isEmpty();
                walls &= ns.isCollisionShapeFullBlock(level, n);
            }
        }
        BlockPos floor = spot.below(NightShelter.DEPTH + 1);
        BlockState fs = level.getBlockState(floor);
        boolean floorSolid = fs.isFaceSturdy(level, floor, Direction.UP);
        boolean floorFluid = !fs.getFluidState().isEmpty();
        // the two cells over the hole must be open sky air, or the cap is not flush and we are not on a surface
        boolean open = true;
        for (int i = 0; i < 2; i++) {
            BlockPos p = spot.above(i);
            BlockState s = level.getBlockState(p);
            open &= s.getCollisionShape(level, p).isEmpty() && s.getFluidState().isEmpty();
        }
        return new NightShelter.Column(dig[0], dig[1], dig[2], floorSolid, floorFluid, fluid, walls, open, supply);
    }

    private static boolean havePick(AltoClef mod) {
        return mod.getItemStorage().hasItem(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.GOLDEN_PICKAXE, Items.IRON_PICKAXE,
                Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);
    }

    // the blocks we may put over our head: the throwaway list first, then the usual suspects. never anything that falls (sand and
    // gravel are on the throwaway list and would land on us), and the list is checked against the bag by the caller
    // filled on first use in a world: tags are only bound once one is loaded, and a unit test loading this class must not
    // touch the registry at all
    private static java.util.List<Item> _wood = java.util.List.of();

    private static java.util.List<Item> woodCaps() {
        if (!_wood.isEmpty()) return _wood;
        java.util.List<Item> wood = new java.util.ArrayList<>();
        for (Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            net.minecraft.world.item.ItemStack stack = item.getDefaultInstance();
            if (stack.is(net.minecraft.tags.ItemTags.PLANKS) || stack.is(net.minecraft.tags.ItemTags.LOGS)) {
                wood.add(item);
            }
        }
        _wood = wood;
        return wood;
    }

    private static Item[] capItems(AltoClef mod) {
        Set<Item> out = new LinkedHashSet<>();
        for (Item item : AltoSettings.getThrowawayItems(mod, true)) {
            if (item instanceof BlockItem block && !(block.getBlock() instanceof FallingBlock)) {
                out.add(item);
            }
        }
        out.add(Items.DIRT);
        out.add(Items.COBBLESTONE);
        out.add(Items.NETHERRACK);
        out.add(Items.COBBLED_DEEPSLATE);
        // planks and logs last: a fresh respawn digging into snow gets nothing to put back over its head, but there is
        // almost always some wood in the bag by night one. better a roof of planks than no hole at all
        out.addAll(woodCaps());
        return out.toArray(new Item[0]);
    }

    private static Item[] capInBag(AltoClef mod) {
        Item[] all = capItems(mod);
        boolean[] falling = new boolean[all.length];
        boolean[] have = new boolean[all.length];
        for (int i = 0; i < all.length; i++) {
            falling[i] = all[i] instanceof BlockItem block && block.getBlock() instanceof FallingBlock;
            have[i] = mod.getItemStorage().hasItem(all[i]);
        }
        int at = NightShelter.pickCap(falling, have);
        return at < 0 ? new Item[0] : new Item[]{all[at]};
    }

    // ---- the task

    @Override
    protected void onStart(AltoClef mod) {
        // (runs again after every interrupt, so the clocks are not reset here: a skeleton plinking us would keep them from ever firing)
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // an interrupt (a meal) is not the end of the night, the stand down must outlive it
        if (!isInterrupting()) {
            NightSleepHook.clearSheltered();
        }
        mod.getInputControls().releaseOwnedBy(keys);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other == this;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return stage == Stage.DONE;
    }

    @Override
    protected String toDebugString() {
        return "Hiding from the night (" + stage + ")";
    }

    private void enter(Stage next, long now) {
        stage = next;
        stageSince = now;
        confirmed = 0;
        mobTicks = 0;
    }

    // a try that fell through: if we are down the hole, climb out, otherwise it is just over
    private void fail(AltoClef mod, long now) {
        gaveUp = true;
        mod.getInputControls().releaseOwnedBy(keys);
        // (only when we are really in the hole, a walk that ran low on the way is not a reason to dig the cap cell up)
        if (inColumn(mod.getPlayer()) && mod.getPlayer().blockPosition().getY() < top.getY()) {
            enter(Stage.EXIT, now);
        } else {
            enter(Stage.DONE, now);
        }
    }

    private BlockPos capCell() {
        return top.below();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        Level level = mod.getWorld();
        LocalPlayer player = mod.getPlayer();
        long now = level.getGameTime();
        if (stageSince == Long.MIN_VALUE) {
            stageSince = now;
        }
        if (startedAt == Long.MIN_VALUE) {
            startedAt = now;
        }
        // one deadline for all the work before the seal, the stages restart their own clocks on every round trip
        if (sealedAt == 0 && stage != Stage.DONE && now - startedAt > WORK_TICKS) {
            if (stage == Stage.EXIT) {
                enter(Stage.DONE, now);
            } else {
                fail(mod, now);
            }
            return null;
        }
        long inStage = now - stageSince;
        return switch (stage) {
            case WALK -> walk(mod, player, inStage, now);
            case DIG -> dig(mod, player, level, inStage, now);
            case CAP -> cap(mod, player, level, inStage, now);
            case WAIT -> waitIt(mod, player, level, now);
            case EXIT -> exit(mod, player, level, inStage, now);
            case DONE -> null;
        };
    }

    private boolean inColumn(LocalPlayer player) {
        return Math.abs(player.getX() - (top.getX() + 0.5)) < 0.45 && Math.abs(player.getZ() - (top.getZ() + 0.5)) < 0.45;
    }

    private Task walk(AltoClef mod, LocalPlayer player, long inStage, long now) {
        if (inColumn(player) && player.blockPosition().getY() == top.getY() && player.onGround()) {
            // in the block is not enough, a toe over the edge keeps us standing on the next one when ours is dug out
            // (and only while the ground is still there: on the lip of a hole already started, the dig step walks us in)
            if (offCenter(player) > CENTER_SLACK && inStage <= WALK_TICKS + CENTER_TICKS && !mod.getWorld().getBlockState(top.below()).canBeReplaced()) {
                nudge(mod, player);
                return null;
            }
            mod.getInputControls().releaseOwnedBy(keys);
            enter(Stage.DIG, now);
            return null;
        }
        mod.getInputControls().releaseOwnedBy(keys);
        if (inStage > WALK_TICKS) {
            fail(mod, now);
            return null;
        }
        setDebugState("Walking to the spot for a hole.");
        return new GetToBlockTask(top);
    }

    private double offCenter(LocalPlayer player) {
        return Math.hypot(player.getX() - (top.getX() + 0.5), player.getZ() - (top.getZ() + 0.5));
    }

    // creep to the middle of the block: sneaking is slow enough not to overshoot it
    private void nudge(AltoClef mod, LocalPlayer player) {
        setDebugState("Stepping to the middle of the spot.");
        mod.getClientBaritone().getPathingBehavior().requestPause();
        LookHelper.lookAt(mod, new Vec3(top.getX() + 0.5, player.getEyeY(), top.getZ() + 0.5));
        mod.getInputControls().hold(Input.SNEAK, keys);
        mod.getInputControls().hold(Input.MOVE_FORWARD, keys);
    }

    private Task dig(AltoClef mod, LocalPlayer player, Level level, long inStage, long now) {
        if (inStage > DIG_TICKS) {
            fail(mod, now);
            return null;
        }
        int bottom = top.getY() - NightShelter.DEPTH;
        if (player.blockPosition().getY() <= bottom && player.onGround()) {
            enter(Stage.CAP, now);
            return null;
        }
        // knocked or walked out of the column before the first cell was open: start over from the walk
        if (!inColumn(player) && player.blockPosition().getY() >= top.getY()) {
            // (the attempt's clock keeps running through these, three trips out and back and it is a lost cause)
            if (NightShelter.bounceOver(++bounces)) {
                fail(mod, now);
                return null;
            }
            enter(Stage.WALK, now);
            return null;
        }
        // straight down the column: baritone mines the block under our feet and we drop, never a side cell to dig from.
        // no wander, a failed progress check walking us out of the shaft is the bounce we are avoiding
        setDebugState("Digging a hole.");
        return new GetDownColumnTask(top.getX(), top.getZ(), top.getY() - NightShelter.DEPTH, false);
    }

    private Task cap(AltoClef mod, LocalPlayer player, Level level, long inStage, long now) {
        BlockPos cell = capCell();
        if (!level.getBlockState(cell).canBeReplaced()) {
            confirmed++;
            if (confirmed >= CONFIRM_TICKS) {
                sealedAt = now;
                waitCap = NightShelter.waitCap(mod.getWorld().getOverworldClockTime());
                lastHealth = player.getHealth();
                lifted = false;
                enter(Stage.WAIT, now);
            }
            return null;
        }
        confirmed = 0;
        if (inStage > CAP_TICKS) {
            fail(mod, now);
            return null;
        }
        // something standing in the cell: it moves or we give up on the cap
        AABB box = new AABB(cell);
        if (!level.getEntitiesOfClass(LivingEntity.class, box, e -> e != player).isEmpty()) {
            if (++mobTicks > MOB_PATIENCE_TICKS) {
                fail(mod, now);
            }
            return null;
        }
        mobTicks = 0;
        if (!clickCap(mod, player, level, cell)) {
            // nothing to place with or nothing to click: no point staying down here
            if (inStage > 40) {
                fail(mod, now);
            }
        }
        return null;
    }

    // one tick of putting the block in the cap cell, clicked against a wall beside it. same click as MobDefenseChain.capCell.
    // true = still at it
    private boolean clickCap(AltoClef mod, LocalPlayer player, Level level, BlockPos cell) {
        Item[] blocks = capInBag(mod);
        if (blocks.length == 0) {
            return false;
        }
        Direction clickFace = null;
        BlockPos support = null;
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos n = cell.relative(d);
            Direction face = d.getOpposite();
            if (level.getFluidState(n).isEmpty() && level.getBlockState(n).isFaceSturdy(level, n, face)) {
                support = n;
                clickFace = face;
                break;
            }
        }
        if (support == null) {
            return false;
        }
        // sneak first: a crafting table or a furnace in the wall would eat the click as a use
        mod.getInputControls().hold(Input.SNEAK, keys);
        if (!StorageHelper.isEquipped(mod, blocks)) {
            mod.getSlotHandler().forceEquipItem(blocks, true);
            return true;
        }
        Vec3 face = Vec3.atCenterOf(support).add(clickFace.getStepX() * 0.5, clickFace.getStepY() * 0.5, clickFace.getStepZ() * 0.5);
        mod.getClientBaritone().getPathingBehavior().requestPause();
        LookHelper.lookAt(mod, face);
        // our own ray along the look we just set, the crosshair is a frame behind it
        HitResult hit = RayTraceUtils.rayTraceTowards(player, LookHelper.getLookRotation(player), 4.5);
        if (!(hit instanceof BlockHitResult bhit) || !bhit.getBlockPos().equals(support) || bhit.getDirection() != clickFace) {
            return true;
        }
        InteractionResult result = Minecraft.getInstance().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, bhit);
        if (result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        }
        return true;
    }

    private Task waitIt(AltoClef mod, LocalPlayer player, Level level, long now) {
        setDebugState("Waiting out the night in a hole.");
        // mob defense ran us out of the hole after the stand down was lifted: the night is not spent standing on the surface for this
        if (!inColumn(player) || player.blockPosition().getY() > top.getY() - NightShelter.DEPTH) {
            gaveUp = true;
            enter(Stage.DONE, now);
            return null;
        }
        // the cap is gone (lag took it back, or something broke it): put it back before anything else
        if (level.getBlockState(capCell()).canBeReplaced()) {
            enter(Stage.CAP, now);
            return null;
        }
        mod.getInputControls().releaseOwnedBy(keys);
        // the sky came back before the day count rolled over (the thunder that opened the window cleared, or dawn)
        if (level.isBrightOutside()) {
            enter(Stage.EXIT, now);
            return null;
        }
        if (!lifted && NightShelter.hurtWhileSealed(lastHealth, player.getHealth())) {
            lifted = true;
        }
        lastHealth = player.getHealth();
        if (NightShelter.waitOver(night, mod.getWorld().getOverworldClockTime(), now - sealedAt, waitCap)) {
            enter(Stage.EXIT, now);
        }
        return null;
    }

    private Task exit(AltoClef mod, LocalPlayer player, Level level, long inStage, long now) {
        BlockPos cell = capCell();
        mod.getInputControls().releaseOwnedBy(keys);
        if (inStage > EXIT_TICKS || (player.blockPosition().getY() >= top.getY() && player.onGround())) {
            enter(Stage.DONE, now);
            return null;
        }
        setDebugState("Climbing out of the hole.");
        if (!level.getBlockState(cell).canBeReplaced()) {
            return new DestroyBlockTask(cell);
        }
        return new GetToBlockTask(top);
    }
}
