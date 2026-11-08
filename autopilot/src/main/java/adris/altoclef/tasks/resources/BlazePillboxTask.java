package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.PlaceBlockTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.BlazePillbox;
import adris.altoclef.util.helpers.BlazeShelter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

// the buddy's fork: builds a BlazePillbox plan by the spawner (steering 21): to the cell, then the blocks in the plan's order,
// each one only while no blaze that sees the cell is near its shot (the volley clock: a charged blaze shoots the moment it
// sees us, so a block waits while one has been charged LOADED ticks or more; the old wall went up under fire and was never
// finished, 0 of 10). a block not in after PLACE_TICKS is left out, the whole after GIVE_UP_TICKS: then the camp is the
// open one as before. done when every block of the plan is solid. LOADED and PLACE_TICKS by Tuning (pillbox_loaded,
// pillbox_place_ticks), their defaults what the rod drills' yardstick plays: 1,000,000 (the clock holds no block back) and 300
final class BlazePillboxTask extends Task {
    static final double LOADED = 1_000_000;
    static final long PLACE_TICKS = 300;
    static final long GIVE_UP_TICKS = 2400;
    private static final Block[] BLOCKS = {Blocks.COBBLESTONE, Blocks.NETHERRACK, Blocks.COBBLED_DEEPSLATE, Blocks.BLACKSTONE,
            Blocks.STONE, Blocks.DIRT, Blocks.NETHER_BRICKS, Blocks.BASALT};

    private final BlazePillbox.Plan plan;
    private final BlockPos cell;
    private int next;
    private long startedAt = Long.MIN_VALUE;
    private long runTicks;
    private Task walk;
    private BlockPos walkTo;
    private Task placing;
    private BlockPos placingPos;
    private long placingTicks;
    private long waitLoggedAt = Long.MIN_VALUE / 2;
    private boolean done, gaveUp;
    static final int ROOF_RETRIES = 3;
    private int roofRetries;
    private int left;

    BlazePillboxTask(BlazePillbox.Plan plan) {
        this(plan, true);
    }

    // (the buddy's fork, Tuning far_box, off by default, steering 89: the far box is planned once the spawner is seen, as much
    // as 140 blocks off. its give-up clock counted that walk and ran out on it, not one block placed, strat3 t1, 7 Nov 2026.
    // with clockAtCell the clock starts once the buddy is within CLOCK_NEAR of the cell, and the walk there is given up after
    // WALK_STUCK ticks of this task with no step closer, walkStuck true. no other box uses it)
    static final double CLOCK_NEAR = 6;
    static final long WALK_STUCK = 1200;
    private boolean clockAtCell, atCell, walkStuck;
    private double walkBest = Double.MAX_VALUE;
    private long walkTicks;

    void clockAtCell() {
        clockAtCell = true;
    }

    boolean walkStuck() {
        return walkStuck;
    }

    // hides false: a box that is not the fight's corner (the brew box, BrewBoxTask) leaves the fight's hiding place in its own
    // pillbox where it is (hideAway is one for all)
    BlazePillboxTask(BlazePillbox.Plan plan, boolean hides) {
        this.plan = plan;
        if (hides) hideAway(plan);
        this.cell = new BlockPos(plan.cell()[0], plan.cell()[1], plan.cell()[2]);
    }

    // the blocks in the bag a box is built of (BLOCKS), all kinds together
    static int blocksInBag(AltoClef mod) {
        int n = 0;
        for (Block b : BLOCKS) n += mod.getItemStorage().getItemCount(b.asItem());
        return n;
    }

    // what the brew of fire resistance is made with and in (BrewFireResistanceTask): never a box's block. a brew box's wall cell
    // got the brewing stand, that block of the build was never clicked, and the brew had no stand
    static boolean brewItem(net.minecraft.world.item.Item item) {
        return item == net.minecraft.world.item.Items.BREWING_STAND || item == net.minecraft.world.item.Items.GLASS_BOTTLE
                || item == net.minecraft.world.item.Items.POTION || item == net.minecraft.world.item.Items.BLAZE_ROD
                || item == net.minecraft.world.item.Items.BLAZE_POWDER || item == net.minecraft.world.item.Items.NETHER_WART
                || item == net.minecraft.world.item.Items.MAGMA_CREAM;
    }

    // a click only with its block in hand, for the brew box (Tuning brew_box): with the brew's things in the bag nothing else
    // may go into a wall. the fight's own pillbox keeps the old click (after 20 failed equips, with whatever is in hand), so
    // the yardstick runs as before
    private static adris.altoclef.tasks.InteractWithBlockTask held(adris.altoclef.tasks.InteractWithBlockTask t) {
        return adris.altoclef.util.helpers.Tuning.get("brew_box", 0) >= 1 ? t.onlyWith() : t;
    }

    // the block a click places: the first of BLOCKS in the bag, never one the brew needs; null with none
    static net.minecraft.world.item.Item buildingBlock(java.util.function.Predicate<net.minecraft.world.item.Item> inBag) {
        for (Block b : BLOCKS) {
            net.minecraft.world.item.Item item = b.asItem();
            if (!brewItem(item) && inBag.test(item)) return item;
        }
        return null;
    }

    BlockPos cell() {
        return cell;
    }

    // the plan's blocks, for the fight's placing ban to leave alone; for a raised box also the columns under B and F up there,
    // where Baritone's pillar climbs (feet level and up is banned in a fight)
    java.util.Set<BlockPos> blocks() {
        java.util.Set<BlockPos> s = new java.util.HashSet<>();
        for (int[] b : plan.blocks()) s.add(new BlockPos(b[0], b[1], b[2]));
        if (plan.raise() > 0 && plan.front() != null) {
            BlockPos f = new BlockPos(plan.front()[0], plan.front()[1], plan.front()[2]);
            for (int k = 1; k <= plan.raise(); k++) {
                s.add(cell.below(k));
                s.add(f.below(k));
            }
        }
        return s;
    }

    // the fight's corner while building: the cell, for a raised box the cell on the ground under it till B's floor is in
    BlockPos corner(AltoClef mod) {
        if (plan.raise() <= 0 || solid(mod, cell.below())) return cell;
        return cell.below(plan.raise());
    }

    BlockPos lookout() {
        return new BlockPos(plan.lookout()[0], plan.lookout()[1], plan.lookout()[2]);
    }

    // the bent box on the ground as a brew box's two cells, {B, F - B as x and z}: null for the one-cell or raised box
    // (BrewBoxTask, brew_box 2)
    int[][] brewCells() {
        if (plan.raise() > 0) return null;
        int[] b = plan.cell();
        // (the one-cell box: its cell is B and its look out F, whose open sides the brew box walls; no door then, the way in
        // is F itself. with none, the box of its own found no floor at one-cell camps, seven tries in a run, piece2craft-3)
        if (plan.front() == null) {
            int[] o = plan.lookout();
            int dx = o[0] - b[0], dz = o[2] - b[2];
            if (Math.abs(dx) + Math.abs(dz) != 1 || o[1] != b[1]) return null;
            return new int[][]{b.clone(), {dx, dz}, null};
        }
        int[] f = plan.front();
        int[] door = BlazePillbox.door(plan);
        return new int[][]{b.clone(), {f[0] - b[0], f[2] - b[2]}, door};
    }

    // the cell outside the look out's open side (BlazePillbox.door), null for the raised box
    BlockPos door() {
        int[] d = BlazePillbox.door(plan);
        return d == null ? null : new BlockPos(d[0], d[1], d[2]);
    }

    // the buddy stands in the box: over the cell, the front cell or the look out, from a block under its floor to its roof
    // (by x and z: a jump in the cell takes the feet a block up)
    boolean holds(BlockPos at) {
        for (BlockPos c : new BlockPos[]{cell, front(), lookout()}) {
            if (c != null && c.getX() == at.getX() && c.getZ() == at.getZ() && at.getY() >= c.getY() - 1 && at.getY() <= c.getY() + 2) return true;
        }
        return false;
    }

    // the bent box's front cell, null for the one-cell box
    BlockPos front() {
        return plan.front() == null ? null : new BlockPos(plan.front()[0], plan.front()[1], plan.front()[2]);
    }

    boolean built() {
        return done;
    }

    // the first block of the plan is in (the wall nearest the spawner): the cell gives some cover from then on
    boolean begun(AltoClef mod) {
        if (plan.blocks().isEmpty()) return true;
        int[] b = plan.blocks().get(0);
        BlockPos p = new BlockPos(b[0], b[1], b[2]);
        var level = mod.getWorld();
        return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    // given up with at most SHORT_OK blocks short: the box is cover enough to lure from (realfort-b run 7: one roof block
    // would not go in, "not built in 2400 ticks, 1 blocks short", and the lure never ran, a stall)
    static final int SHORT_OK = 1;

    boolean nearlyBuilt(AltoClef mod) {
        if (done) return true;
        if (!gaveUp) return false;
        return openBlocks(mod) <= SHORT_OK;
    }

    // the blocks of the plan that are not in
    private int openBlocks(AltoClef mod) {
        var level = mod.getWorld();
        int open = 0;
        for (int[] b : plan.blocks()) {
            BlockPos p = new BlockPos(b[0], b[1], b[2]);
            if (level.getBlockState(p).getCollisionShape(level, p).isEmpty()) open++;
        }
        return open;
    }

    boolean gaveUp() {
        return gaveUp;
    }

    // a block placed from inside the box (for a raised one, not its ground part, the step and the columns: those are reached
    // from outside)
    private boolean fromBox(int i) {
        if (plan.stands() == null) return true;
        return plan.stands().get(i)[1] == cell.getY();
    }

    // the box kept closed (steering 36: in run 3 of realfort-b-raised2 the pillbox had a hole in its back, which left the
    // buddy open to the blazes): the blocks of a standing box that are open now, each put back at most REPAIR_TRIES
    // times (one that will not go in is not tried for ever)
    static final int REPAIR_TRIES = 2;
    static final int HOLES_NEAR = 16;
    private final java.util.Map<BlockPos, Integer> repairs = new java.util.HashMap<>();

    java.util.List<BlockPos> holes(AltoClef mod) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        var level = mod.getWorld();
        // (not dead, not far off, and only blocks of loaded chunks: right after a death every block read as gone, realfort-b-repair
        // runs 2 and 4, "8 of its blocks gone" in the second of the death)
        var me = mod.getPlayer();
        if (me == null || !me.isAlive() || me.blockPosition().distManhattan(cell) > HOLES_NEAR) return out;
        for (int i = 0; i < plan.blocks().size(); i++) {
            int[] b = plan.blocks().get(i);
            BlockPos p = new BlockPos(b[0], b[1], b[2]);
            if (fromBox(i) && level.isLoaded(p) && repairs.getOrDefault(p, 0) < REPAIR_TRIES && level.getBlockState(p).getCollisionShape(level, p).isEmpty()) out.add(p);
        }
        return out;
    }

    // build again from the first open block; done once they are in, or after GIVE_UP_TICKS
    void repair(AltoClef mod, java.util.List<BlockPos> holes) {
        for (BlockPos p : holes) repairs.merge(p, 1, Integer::sum);
        done = false;
        gaveUp = false;
        next = 0;
        placing = null;
        walk = null;
        startedAt = mod.getWorld().getGameTime();
        StringBuilder sb = new StringBuilder();
        for (BlockPos p : holes) sb.append(sb.length() == 0 ? "" : ", ").append(p.toShortString());
        Debug.logInternal("pillbox: " + holes.size() + " of its blocks gone, putting them back: " + sb);
    }

    @Override
    protected void onStart(AltoClef mod) {
        if (startedAt == Long.MIN_VALUE) startedAt = mod.getWorld().getGameTime();
        walk = null;
        placing = null;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        long now = mod.getWorld().getGameTime();
        if (now < startedAt) startedAt = now;
        // (the buddy's fork, Tuning pillbox_clock_run, off by default: the give-up counts only the ticks this task runs. counted
        // from its first start in game time, the clock ran on through a ghast shelter, kills and runs, and the box was given up
        // with not one block placed, the camp then the open one, realfort-ab-leash-3 run 4)
        boolean runClock = adris.altoclef.util.helpers.Tuning.get("pillbox_clock_run", 0) > 0;
        if (clockAtCell && !atCell) {
            double d = mod.getPlayer().position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(cell));
            if (d <= CLOCK_NEAR) {
                atCell = true;
                startedAt = now;
                runTicks = 0;
                Debug.logInternal("pillbox: at the cell " + cell.toShortString() + ", the clock starts");
            } else {
                if (d < walkBest - 1) {
                    walkBest = d;
                    walkTicks = 0;
                }
                if (++walkTicks > WALK_STUCK) {
                    gaveUp = true;
                    walkStuck = true;
                    Debug.logInternal(String.format(java.util.Locale.ROOT, "pillbox: the walk to the cell %s came no closer in %d ticks, %.1f from it, given up",
                            cell.toShortString(), WALK_STUCK, d));
                    return null;
                }
            }
        }
        // (the far box's clock waits for the cell)
        boolean clockWaits = clockAtCell && !atCell;
        if (!clockWaits && (runClock ? ++runTicks > GIVE_UP_TICKS : now - startedAt > GIVE_UP_TICKS)) {
            gaveUp = true;
            Debug.logInternal("pillbox: not built in " + GIVE_UP_TICKS + " ticks, " + (plan.blocks().size() - next) + " blocks short");
            return null;
        }
        var level = mod.getWorld();
        while (next < plan.blocks().size()) {
            int[] b = plan.blocks().get(next);
            BlockPos p = new BlockPos(b[0], b[1], b[2]);
            if (level.getBlockState(p).getCollisionShape(level, p).isEmpty()) break;
            next++;
        }
        // (a roof row block left out is tried again: without it the box is no cover, a ball came into the back cell through the
        // front cell's missing roof, bent-e run 3, and the buddy burned in it)
        if (next >= plan.blocks().size() && roofRetries < ROOF_RETRIES) {
            for (int i = 0; i < plan.blocks().size(); i++) {
                int[] b = plan.blocks().get(i);
                BlockPos p = new BlockPos(b[0], b[1], b[2]);
                // (with pillbox_repair every block of the box, not the roof row alone: one the client saw go in was gone half a
                // minute later, the server's no, and a fireball came through it, realfort-b-raised2 run 3, steering 36)
                boolean any = adris.altoclef.util.helpers.Tuning.get("pillbox_repair", 0) > 0 && fromBox(i);
                if ((b[1] >= cell.getY() + 2 || any) && level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
                    roofRetries++;
                    left--;
                    next = i;
                    placing = null;
                    Debug.logInternal("pillbox: the " + (b[1] >= cell.getY() + 2 ? "roof " : "") + "block at " + p.toShortString() + " again (" + roofRetries + " of " + ROOF_RETRIES + ")");
                    break;
                }
            }
        }
        if (next >= plan.blocks().size()) {
            // (more than SHORT_OK of its blocks open at the end is no box: counted as standing, its bare cell became the
            // fight's corner and the camp, and the runs into it burned there. given up instead, so the camp is picked again)
            int open = openBlocks(mod);
            if (open > SHORT_OK) {
                gaveUp = true;
                Debug.logInternal("pillbox: through its plan at " + cell.toShortString() + " with " + open + " of " + plan.blocks().size() + " blocks open, given up");
                return null;
            }
            done = true;
            Debug.logInternal("pillbox: built at " + cell.toShortString() + " (" + left + " of " + plan.blocks().size() + " left out), looking out from " + lookout().toShortString());
            return null;
        }
        int[] nb = plan.blocks().get(next);
        BlockPos pos = new BlockPos(nb[0], nb[1], nb[2]);
        // (Tuning pillbox_no_face, on by default, route jar 3: a block with no solid block beside, under or over it has nothing
        // for a click to go against, and no click puts it in: left out at once. a bent box's front roof after the back roof
        // was left out took the clicks and the steps to the cell's middle under fireballs till the buddy died in the box,
        // "block 9 of 9", route jar 2 run 2. the roof row's retry below tries it again once its neighbour is in)
        // (Tuning pillbox_at_cell, on by default, route jar 5: the check only once the buddy stands at the block's stand, below,
        // with the block and its six neighbours in loaded chunks. made before the walk, a box planned 620 blocks off read every
        // neighbour from unloaded chunks as air: all 13 blocks left out, the roof tried three times, the plan given up, and the
        // spawner barred from a box for the rest of the run, the brew's box with it, realfort-route-1 run 1)
        boolean noFace = adris.altoclef.util.helpers.Tuning.get("pillbox_no_face", 0) > 0;
        boolean faceAtCell = adris.altoclef.util.helpers.Tuning.get("pillbox_at_cell", 0) > 0;
        if (noFace && BlazePillbox.faceCall(faceAtCell, false, true, anyFace(mod, pos)) == BlazePillbox.Face.LEAVE_OUT) return leaveOutNoFace(pos);
        BlockPos stand = standFor(next, pos);
        if (!inColumn(mod.getPlayer().position(), stand) && BlazeShelter.loadedOn(mod, cell, now) < adris.altoclef.util.helpers.Tuning.get("pillbox_loaded", LOADED)) {
            setDebugState(stand.equals(cell) ? "To the pillbox's cell" : "To the pillbox's front cell");
            if (walk == null || !stand.equals(walkTo)) {
                walk = new GetToBlockTask(stand);
                walkTo = stand;
            }
            return walk;
        }
        walk = null;
        long loaded = BlazeShelter.loadedOn(mod, cell, now);
        if (loaded >= adris.altoclef.util.helpers.Tuning.get("pillbox_loaded", LOADED)) {
            if (now - waitLoggedAt >= 40) {
                waitLoggedAt = now;
                Debug.logInternal("pillbox: waiting out of sight, a blaze that sees the cell has been charged " + loaded + " ticks");
            }
            setDebugState("Waiting out a blaze's volley, out of its sight");
            // (out of sight while it waits: standing at the bare cell, it burned there, corner23's first check)
            Task hide = hideNear(mod, now);
            return hide;
        }
        hideAt = null;
        hideWalk = null;
        if (noFace && faceAtCell) {
            BlazePillbox.Face f = BlazePillbox.faceCall(true, inColumn(mod.getPlayer().position(), stand), loadedRound(mod, pos), anyFace(mod, pos));
            if (f == BlazePillbox.Face.LEAVE_OUT) return leaveOutNoFace(pos);
            if (f == BlazePillbox.Face.WAIT) {
                setDebugState("Waiting for the chunks round the pillbox's block " + (next + 1) + " of " + plan.blocks().size());
                return null;
            }
        }
        boolean roofRow = pos.getY() >= stand.getY() + 2;
        if (placing == null || !pos.equals(placingPos)) {
            placing = click(mod, pos, roofRow);
            placingPos = pos;
            placingTicks = 0;
        }
        // (the ticks spent placing it, not since it was begun: a fight's interruptions ran the clock out)
        if (++placingTicks > adris.altoclef.util.helpers.Tuning.get("pillbox_place_ticks", PLACE_TICKS)) {
            Debug.logInternal("pillbox: the block at " + pos.toShortString() + " would not go in, left out");
            left++;
            next++;
            placing = null;
            return null;
        }
        if (centre(mod, stand, pos)) {
            setDebugState("To the middle of the pillbox's cell, block " + (next + 1) + " of " + plan.blocks().size());
            return null;
        }
        // (a roof row block placed from a cell other than the box's own, a bent box's F: to that cell's middle first. the walk
        // there ends at the column's edge, inColumn takes up to 0.8 off the middle, and 0.02 short of F the face of B's roof
        // that F's roof goes on looks away from the eye: every click missed, three tries of 1200 ticks, and the brew box
        // waited a minute with only that block to place, piece2in-1 run 10)
        // (behind Tuning roof_centre, off by default, steering 78: in the blocks after it came in, deaths before the first rod
        // went from about one run in eight to nearly one in two, the step and its crouch in the front cell a suspect)
        if (roofRow && !stand.equals(cell) && adris.altoclef.util.helpers.Tuning.get("roof_centre", 0) > 0 && centreIn(mod, stand, HIDE_SLACK)) {
            setDebugState("To the middle of the pillbox's front cell, block " + (next + 1) + " of " + plan.blocks().size());
            return null;
        }
        setDebugState("Building the pillbox, block " + (next + 1) + " of " + plan.blocks().size());
        // (a roof row block on a wall's top face wants a jump: standing, the eye is at 1.62, under that face, which no click can
        // see; at a jump's top it can, as a player builds a roof. the click stays put and the aim is set on the ground, so the
        // jump's top is spent clicking: merged-d jumped but its click walked off for a spot to see the face from, 88 walks back
        // to the cell and the roof never in. one against a side face is clicked standing: at a jump's top the body is in its cell)
        if (roofRow && solid(mod, pos.below()) && mod.getPlayer().onGround()) {
            adris.altoclef.util.helpers.LookHelper.lookAt(mod, new net.minecraft.world.phys.Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
            mod.getInputControls().tryPress(baritone.api.utils.input.Input.JUMP);
        }
        return placing;
    }

    // where a block is placed from: the roof row of a bent box from its front cell (from the back cell the face F's roof goes on
    // faces away), all else from the cell
    // a raised box's from its plan (its ground part from the ground, the floors and the roof from F up there)
    private BlockPos standFor(int index, BlockPos pos) {
        if (plan.stands() != null) {
            int[] s = plan.stands().get(index);
            return new BlockPos(s[0], s[1], s[2]);
        }
        if (plan.front() != null && pos.getY() >= cell.getY() + 2) return new BlockPos(plan.front()[0], plan.front()[1], plan.front()[2]);
        return cell;
    }

    // in the stand cell's column, up to a jump's top (BlazeShelter.in is the feet's block alone: a jump read as out of the cell)
    static boolean inColumn(net.minecraft.world.phys.Vec3 at, BlockPos c) {
        double dx = at.x - (c.getX() + 0.5), dz = at.z - (c.getZ() + 0.5), dy = at.y - c.getY();
        return dx * dx + dz * dz <= 0.64 && dy > -0.5 && dy < 1.6;
    }

    // the body in the block's cell: the game refuses the block. the walk to the cell stops at its edge (bent-a, every run at
    // 3196.14 against the middle's 3196.5, the box 0.16 into the wall's cell, each fireball pushed it back in, and the wall
    // never went in), so a step to the middle of the stand cell first, again after each push: the movement keys under the
    // look it has, crouched so it does not overshoot. true while stepping
    static final double CENTRE_SLACK = 0.05;

    private static boolean centre(AltoClef mod, BlockPos stand, BlockPos pos) {
        if (!mod.getPlayer().getBoundingBox().intersects(new net.minecraft.world.phys.AABB(pos))) return false;
        return centreIn(mod, stand, CENTRE_SLACK);
    }

    // in the middle of the cell, within slack each way: false; else a step toward it this tick, true. for the box's hiding
    // too (BlazeFight, BlazeLureTask): "in the corner" counts up to 0.8 off its middle, half in the front cell, which the look
    // out shows, and bent-d run 3 healed and burned there
    static final double HIDE_SLACK = 0.15;

    static boolean centreIn(AltoClef mod, BlockPos stand, double slack) {
        return centreAt(mod, stand, 0, 0, slack);
    }

    // a bent box's hiding place: B's middle moved HIDE_BACK away from F. a ball over the look out's open face or top came in at
    // the corner of O, F and B and touched the buddy 0.5 off B's middle on F's side, straight-bent-g run 4; a roof over the
    // look out shut most spawn eyes out of it (44 against the half needed), so deeper in B instead
    static final double HIDE_BACK = 0.2;
    static volatile int hideAwayX, hideAwayZ;

    static void hideAway(BlazePillbox.Plan plan) {
        hideAwayX = plan.front() == null ? 0 : plan.cell()[0] - plan.front()[0];
        hideAwayZ = plan.front() == null ? 0 : plan.cell()[2] - plan.front()[2];
    }

    static boolean hideIn(AltoClef mod, BlockPos cell) {
        return centreAt(mod, cell, hideAwayX * HIDE_BACK, hideAwayZ * HIDE_BACK, HIDE_SLACK);
    }

    static boolean centreAt(AltoClef mod, BlockPos stand, double ox, double oz, double slack) {
        var player = mod.getPlayer();
        double dx = stand.getX() + 0.5 + ox - player.getX(), dz = stand.getZ() + 0.5 + oz - player.getZ();
        if (Math.abs(dx) <= slack && Math.abs(dz) <= slack) return false;
        double yaw = Math.toRadians(player.getYRot());
        // (forward is (-sin, cos) of the yaw, left (cos, sin): facing south, +z, the left hand is east)
        double f = -dx * Math.sin(yaw) + dz * Math.cos(yaw), l = dx * Math.cos(yaw) + dz * Math.sin(yaw);
        var in = mod.getInputControls();
        in.tryPress(baritone.api.utils.input.Input.SNEAK);
        double k = Math.min(slack, CENTRE_SLACK);
        if (f > k) in.tryPress(baritone.api.utils.input.Input.MOVE_FORWARD);
        else if (f < -k) in.tryPress(baritone.api.utils.input.Input.MOVE_BACK);
        if (l > k) in.tryPress(baritone.api.utils.input.Input.MOVE_LEFT);
        else if (l < -k) in.tryPress(baritone.api.utils.input.Input.MOVE_RIGHT);
        return true;
    }

    private Task leaveOutNoFace(BlockPos pos) {
        Debug.logInternal("pillbox: the block at " + pos.toShortString() + " has nothing to go against, left out");
        left++;
        next++;
        placing = null;
        return null;
    }

    // the block and its six neighbours in loaded chunks
    private static boolean loadedRound(AltoClef mod, BlockPos p) {
        var level = mod.getWorld();
        if (!level.isLoaded(p)) return false;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            if (!level.isLoaded(p.relative(d))) return false;
        }
        return true;
    }

    // a solid block on any of the six sides of p: something a click can put a block at p against
    private static boolean anyFace(AltoClef mod, BlockPos p) {
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            if (solid(mod, p.relative(d))) return true;
        }
        return false;
    }

    private static boolean solid(AltoClef mod, BlockPos p) {
        var level = mod.getWorld();
        return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    // a plain click on a solid face beside the block, the floor or the block under it first (as the portal frame's cells,
    // 7e521bd): Baritone's builder never got its place click in during the fight ("couldn't get a place click in for 2.0s"),
    // with or without the shield, corner23d and corner23f. the builder only when no face is solid. with brew_box on a click
    // goes out only with its block in hand (held, InteractWithBlockTask.onlyWith): a brewing stand is never a block of the box
    private static Task click(AltoClef mod, BlockPos pos, boolean stayPut) {
        net.minecraft.world.item.Item item = buildingBlock(i -> mod.getItemStorage().hasItem(i));
        var level = mod.getWorld();
        if (item != null) {
            adris.altoclef.util.ItemTarget it = new adris.altoclef.util.ItemTarget(item, 1);
            BlockPos below = pos.below();
            // (a click that does not stay put takes only a face the eye is in front of: from a raised box's front cell the face of
            // its own floor toward the back cell looks away, and B's floor goes against the inside of B's walls instead. the roof
            // row's click is made standing and clicked at a jump's top, so it keeps the floor's face as before)
            if (!stayPut) {
                net.minecraft.world.phys.Vec3 eye = mod.getPlayer().getEyePosition();
                if (!level.getBlockState(below).getCollisionShape(level, below).isEmpty() && eye.y > pos.getY()) {
                    return held(new adris.altoclef.tasks.InteractWithBlockTask(it, net.minecraft.core.Direction.UP, below, false));
                }
                for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                    BlockPos by = pos.relative(d);
                    if (!level.getBlockState(by).getCollisionShape(level, by).isEmpty() && facesEye(eye, pos, d)) {
                        return held(new adris.altoclef.tasks.InteractWithBlockTask(it, d.getOpposite(), by, false));
                    }
                }
            }
            // (the roof row stays where it stands, standFor picked the cell it is reached from)
            if (!level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
                var t = held(new adris.altoclef.tasks.InteractWithBlockTask(it, net.minecraft.core.Direction.UP, below, false));
                return stayPut ? t.stayPut() : t;
            }
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos by = pos.relative(d);
                if (!level.getBlockState(by).getCollisionShape(level, by).isEmpty()) {
                    var t = held(new adris.altoclef.tasks.InteractWithBlockTask(it, d.getOpposite(), by, false));
                    return stayPut ? t.stayPut() : t;
                }
            }
        }
        return new PlaceBlockTask(pos, BLOCKS, true, false);
    }

    // the face between pos and its neighbour pos + d (the neighbour's face toward pos) has the eye on pos's side: a click can hit it
    static boolean facesEye(net.minecraft.world.phys.Vec3 eye, BlockPos pos, net.minecraft.core.Direction d) {
        if (d.getStepX() != 0) {
            double plane = pos.getX() + (d.getStepX() > 0 ? 1 : 0);
            return d.getStepX() > 0 ? eye.x < plane : eye.x > plane;
        }
        double plane = pos.getZ() + (d.getStepZ() > 0 ? 1 : 0);
        return d.getStepZ() > 0 ? eye.z < plane : eye.z > plane;
    }

    // the nearest cell by walk no blaze sees (BlazeCover.pick), looked for again every HIDE_CHECK ticks; null standing in it
    // or with none
    static final long HIDE_CHECK = 40;
    private BlockPos hideAt;
    private long hideAtSince = Long.MIN_VALUE / 2;
    private Task hideWalk;

    private Task hideNear(AltoClef mod, long now) {
        if (hideAt == null || now - hideAtSince >= HIDE_CHECK) {
            hideAtSince = now;
            if (mod.getClientBaritone().getPlayerContext() == null) return null;
            var cells = baritone.pathing.movement.CliffAvoidance.of(new baritone.utils.BlockStateInterface(mod.getClientBaritone().getPlayerContext(), true));
            java.util.List<net.minecraft.world.phys.Vec3> eyes = new java.util.ArrayList<>();
            try {
                for (net.minecraft.world.entity.monster.Blaze b : mod.getEntityTracker().getTrackedEntities(net.minecraft.world.entity.monster.Blaze.class)) {
                    if (b.isAlive() && b.distanceTo(mod.getPlayer()) <= adris.altoclef.util.helpers.BlazeCover.SIGHT) eyes.add(b.getEyePosition());
                }
            } catch (java.util.ConcurrentModificationException ignored) {
                return hideWalk;
            }
            BlockPos at = mod.getPlayer().blockPosition();
            int[] c = adris.altoclef.util.helpers.BlazeCover.pick(cells, at.getX(), at.getY(), at.getZ(), java.util.List.of(), new java.util.HashSet<>(),
                    (x, y, z) -> adris.altoclef.util.helpers.BlazeCover.hidden(mod.getWorld(), eyes, x, y, z, mod.getPlayer()));
            BlockPos h = c == null ? null : new BlockPos(c[0], c[1], c[2]);
            if (h != null && !h.equals(hideAt)) adris.altoclef.util.snapshot.Snapshots.chose("pillbox.hide", c);
            if (h == null || !h.equals(hideAt)) hideWalk = h == null ? null : new GetToBlockTask(h);
            hideAt = h;
        }
        if (hideAt == null || BlazeShelter.in(mod.getPlayer().position(), hideAt)) return null;
        return hideWalk;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return done || gaveUp;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BlazePillboxTask t && t.cell.equals(cell);
    }

    @Override
    protected String toDebugString() {
        return "Building a pillbox at " + cell.toShortString();
    }
}
