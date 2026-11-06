package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.ShieldRaise;
import baritone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.phys.Vec3;

// the buddy's fork: the corner camp's look round the corner (BlazeCover.corner). from the corner, now and then a step into the
// peek cell, where the spawn area sees us, with the shield up and turned at the nearest blaze, for a moment; then back behind the
// wall. a blaze that saw us flies at us for a few ticks once we are gone, so look by look they come round the corner into the
// sword's reach. a blaze that has just seen us needs three seconds to charge before its first ball, and the shield takes the
// balls of one that had
final class BlazeLureTask extends Task {

    // between two looks, standing in the corner (game ticks). a blaze flies at us only for the 5 ticks after it lost sight of
    // us, so it is the number of looks that brings it round, not their length (4 Nov 2026: with a look every two
    // seconds the blazes were not always drawn and did not come)
    static final int GAP_TICKS = 12;
    // a look lasts until every blaze that sees us is charged (it has us for its target: a blaze takes one only while it sees it,
    // and asks about every half second), at least PEEK_MIN and at most PEEK_MAX
    static final int PEEK_MIN = 6;
    static final int PEEK_MAX = 30;
    // a walk to either cell that has not got there in this long is given up for this round
    static final int STEP_TICKS = 40;
    // the looks go to the cells NEAR steps from the corner; with none of them in a blaze's sight, as far as FAR steps (the trip
    // out to lure: in the corner out of everyone's sight nothing came, 40 game minutes and 4 rods of ten, corner14: it never
    // left its cell, and a step out now and then draws them better, 4 Nov 2026)
    static final int NEAR = 2;
    static final int FAR = 4;
    // a look goes where the fewest blazes see, one at least, so they come to the choke point a few at a time; MANY or more
    // seeing a cell and it is the last choice
    static final int MANY = 3;
    // a rod lying this close to the corner is fetched between the looks, while at most FETCH_SEEN blazes see where it lies, none
    // is within FETCH_CLEAR of it and health is FETCH_HP or more; given up after FETCH_TICKS (the step out is for the drops
    // too; a drop lasts five minutes)
    static final double FETCH_RANGE = 8;
    static final int FETCH_SEEN = 1;
    static final double FETCH_CLEAR = 3;
    static final float FETCH_HP = 10;
    static final int FETCH_TICKS = 80;

    private enum Stage { WAIT, OUT, PEEK, BACK, FETCH }

    // the trips out of corner15 to corner20, off since steering 18 (4 Nov 2026: back to corner14's stance, the corner is home;
    // one change a block from there): looks up to FAR steps, the cell the fewest see, a rod fetched from up to FETCH_RANGE
    static final boolean FAR_LOOKS = false;
    static final boolean FEWEST = false;
    static final boolean FETCH = false;

    // the buddy's fork, Tuning rod_fetch (steering 62 point 1, 6 Nov 2026: a rod on the ground is progress lying there): the
    // fetch on again, with its own tests (at most FETCH_SEEN blazes see the rod's cell, none within FETCH_CLEAR of it, health
    // FETCH_HP, the leash). it went off with the trips out at steering 18, one change a block, not for a fault of its own
    static boolean fetchOn() {
        return FETCH || adris.altoclef.util.helpers.Tuning.get("rod_fetch", 0) > 0;
    }

    // the volley clock (steering 20, from the game's code: Blaze.isOnFire is its charge, the client sees it; charged 60 ticks
    // before its first ball, and a blaze that has us for target but no line keeps its charge until it sees us, or drops us
    // after 60 ticks unseen): no look out while a blaze that sees the look's cell has been charged LOOK_LOADED ticks or more
    // (it shoots the moment it sees us), and back in from a look once one that sees us has been charged PEEK_LOADED (its ball
    // is due at 60). both by Tuning: look_loaded, peek_loaded. the defaults are what the rod drills' yardstick plays: 1,000,000,
    // the clock holds no look back and ends none (at 40 and 50 a buddy without a tuning file played what nobody had measured)
    static final double LOOK_LOADED = 1_000_000;
    static final double PEEK_LOADED = 1_000_000;
    private long loadedLoggedAt = Long.MIN_VALUE / 2;


    private static final net.minecraft.world.item.Item[] SWORDS = {net.minecraft.world.item.Items.NETHERITE_SWORD, net.minecraft.world.item.Items.DIAMOND_SWORD,
            net.minecraft.world.item.Items.IRON_SWORD, net.minecraft.world.item.Items.STONE_SWORD, net.minecraft.world.item.Items.GOLDEN_SWORD,
            net.minecraft.world.item.Items.WOODEN_SWORD, net.minecraft.world.item.Items.IRON_PICKAXE, net.minecraft.world.item.Items.STONE_PICKAXE};

    private final BlockPos corner;
    private final BlockPos peek;
    // the cells within NEAR steps of the corner a look may go out to, then the ones up to FAR (BlazeCover.around)
    private final java.util.List<BlockPos> lookouts;
    private final java.util.List<BlockPos> farouts;
    private ItemEntity fetching;
    private final java.util.Set<Integer> fetchFailed = new java.util.HashSet<>();
    private int fetches;
    private BlockPos out;
    private final java.util.Set<BlockPos> failed = new java.util.HashSet<>();
    private Stage stage = Stage.WAIT;
    private long since;
    private int looks;
    private GetToBlockTask walk;

    BlazeLureTask(BlockPos corner, BlockPos peek, java.util.List<BlockPos> lookouts, java.util.List<BlockPos> farouts) {
        this.corner = corner;
        this.peek = peek;
        this.lookouts = lookouts;
        this.farouts = farouts;
    }

    // the looks from a corner: the cells a walk reaches in NEAR steps, then in FAR; the peek cell given, or the first of them
    // (null when the corner has no cell round it at all)
    // the wait in the corner at its middle (a pillbox's cell, BlazePillboxTask.centreIn)
    private boolean centred;

    BlazeLureTask centred() {
        centred = true;
        return this;
    }

    static BlazeLureTask of(baritone.pathing.movement.CliffAvoidance.Cells cells, BlockPos corner, BlockPos peek) {
        java.util.List<BlockPos> near = new java.util.ArrayList<>(), far = new java.util.ArrayList<>();
        // (steering 18, back to corner14's stance: the looks within NEAR steps only, no far ones)
        for (int[] c : adris.altoclef.util.helpers.BlazeCover.around(cells, corner.getX(), corner.getY(), corner.getZ(), FAR_LOOKS ? FAR : NEAR)) {
            (c[3] <= NEAR ? near : far).add(new BlockPos(c[0], c[1], c[2]));
        }
        if (near.isEmpty()) return null;
        return new BlazeLureTask(corner, peek != null ? peek : near.get(0), near, far);
    }

    int looks() {
        return looks;
    }

    @Override
    protected void onStart(AltoClef mod) {
        stage = Stage.WAIT;
        since = mod.getWorld().getGameTime();
        // (a fresh start forgets the last walk and rod: after a fight broke into a look, "back into the corner" went on with the
        // look's walk out to the door and onto the platform, in every blaze's sight, corner15 run 4)
        walk = null;
        fetching = null;
        intoBox.reset();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        long now = mod.getWorld().getGameTime();
        if (now < since) since = now;
        Vec3 at = mod.getPlayer().position();
        adris.altoclef.util.helpers.BlazeShelter.lookingOut = stage == Stage.OUT || stage == Stage.PEEK || stage == Stage.FETCH;
        switch (stage) {
            case WAIT -> {
                lower(mod);
                if (!in(at, corner)) {
                    setDebugState("Back into the corner");
                    Task fire = fireOnTheWay(mod);
                    if (fire != null) return fire;
                    // (round by a standing pillbox's door, BoxWay; the plain walk at the cell once that had its time)
                    Task way = intoBox.step(mod, corner);
                    return way != null ? way : walk(corner);
                }
                intoBox.reset();
                // (a pillbox's cell: to its middle, out of the front cell the look out shows)
                if (centred && BlazePillboxTask.hideIn(mod, corner)) {
                    setDebugState("To the middle of the corner");
                    return null;
                }
                setDebugState("In the corner, waiting for the blazes to come round");
                if (now - since >= GAP_TICKS) {
                    ItemEntity drop = fetchOn() ? fetchable(mod) : null;
                    if (drop != null) {
                        fetching = drop;
                        fetches++;
                        next(Stage.FETCH, now);
                        Debug.logInternal("rods: out of the corner for a rod at " + drop.blockPosition().toShortString() + ", "
                                + String.format(java.util.Locale.ROOT, "%.1f", drop.position().distanceTo(Vec3.atBottomCenterOf(corner))) + " blocks");
                        return null;
                    }
                    BlockPos look = lookout(mod);
                    long loaded = adris.altoclef.util.helpers.BlazeShelter.loadedOn(mod, look, now);
                    if (loaded >= adris.altoclef.util.helpers.Tuning.get("look_loaded", LOOK_LOADED)) {
                        // (a loaded blaze sees the look's cell: it would shoot as we step in; wait for it to drop its charge)
                        if (now - loadedLoggedAt >= 40) {
                            loadedLoggedAt = now;
                            Debug.logInternal("rods: no look out to " + look.toShortString() + ", a blaze that sees it has been charged " + loaded + " ticks");
                        }
                        return null;
                    }
                    next(Stage.OUT, now);
                    out = look;
                }
                return null;
            }
            case FETCH -> {
                lower(mod);
                // (back at the first sign of a crowd: two blazes with a line on us, or one by us)
                if (fetching == null || !fetching.isAlive() || now - since > FETCH_TICKS || seenBy(mod, mod.getPlayer().getEyePosition()) > FETCH_SEEN) {
                    if (fetching != null && fetching.isAlive() && now - since > FETCH_TICKS) fetchFailed.add(fetching.getId());
                    fetching = null;
                    next(Stage.BACK, now);
                    return null;
                }
                setDebugState("Out of the corner for a rod");
                return walk(fetching.blockPosition());
            }
            case OUT -> {
                if (out == null) out = peek;
                // (seen is the point of a look: a blaze with a line on us now is as good as being in the cell. but only once out of
                // the corner: see OUT_OF_CORNER)
                if (outOfCorner(at) && (in(at, out) || seen(mod))) {
                    lower(mod);
                    looks++;
                    next(Stage.PEEK, now);
                    return null;
                }
                if (now - since > STEP_TICKS) {
                    // (a cell not got to, a blaze in it or the way, is left out of the next looks)
                    lower(mod);
                    failed.add(out);
                    next(Stage.BACK, now);
                    return null;
                }
                setDebugState("A look round the corner");
                // (in the look's block already but still by the corner, where the walk counts itself there: a step on to its
                // middle)
                if (mod.getPlayer().blockPosition().equals(out)) {
                    LookHelper.lookAt(mod, new Vec3(out.getX() + 0.5, mod.getPlayer().getEyeY(), out.getZ() + 0.5));
                    mod.getInputControls().hold(Input.MOVE_FORWARD, this);
                    return null;
                }
                return walk(out);
            }
            case PEEK -> {
                // (the sword in hand, or an empty hand: the held key with food is a meal and the shield does not go up, with a
                // block it places one, with a water bucket it empties it, with flint and steel it lights the block in the
                // crosshair. and the key is held only for a ready shield in the off hand)
                boolean handSafe = peekHand(mod);
                var off = mod.getPlayer().getOffhandItem();
                boolean shield = off.is(net.minecraft.world.item.Items.SHIELD) && !mod.getPlayer().getCooldowns().isOnCooldown(off);
                Blaze blaze = nearest(mod);
                if (blaze != null) LookHelper.lookAt(mod, blaze.getEyePosition());
                if (handSafe && shield) {
                    mod.getInputControls().hold(Input.CLICK_RIGHT, this);
                    ShieldRaise.raise(mod);
                    setDebugState("Looking round the corner, shield up");
                } else {
                    lower(mod);
                    setDebugState("Looking round the corner");
                }
                long held = now - since;
                // (back in before the ball: a blaze that sees us charged PEEK_LOADED ticks, its first one due at 60)
                boolean due = adris.altoclef.util.helpers.BlazeShelter.loadedOn(mod, mod.getPlayer().blockPosition(), now) >= adris.altoclef.util.helpers.Tuning.get("peek_loaded", PEEK_LOADED);
                if (held >= PEEK_MAX || due || (held >= PEEK_MIN && allSeeingCharged(mod))) {
                    lower(mod);
                    next(Stage.BACK, now);
                }
                return null;
            }
            case BACK -> {
                lower(mod);
                if (in(at, corner) || now - since > STEP_TICKS) {
                    next(Stage.WAIT, now);
                    return null;
                }
                setDebugState("Back behind the wall");
                Task fire = fireOnTheWay(mod);
                return fire != null ? fire : walk(corner);
            }
        }
        return null;
    }

    // a look starts only this far from the corner's middle or more. standing on the corner's edge the buddy was in the corner and
    // in the first look out at once (IN is 0.8), and a blaze our ray had on us ended the walk before a step, where the game's
    // ray had none: 1140 looks in place in run 8 of corner14, the blazes by the door with no line either way, and none came
    // for the rest of runs 7 and 8, stuck there (4 Nov 2026)
    static final double OUT_OF_CORNER = 0.9;

    private boolean outOfCorner(Vec3 at) {
        double dx = at.x - (corner.getX() + 0.5), dz = at.z - (corner.getZ() + 0.5);
        return dx * dx + dz * dz > OUT_OF_CORNER * OUT_OF_CORNER;
    }

    // in a cell: within IN of its middle across (standing on its edge flipped the block position each tick, and the walk and the
    // looks restarted each other three hundred times in a run, 4 Nov 2026)
    static final double IN = 0.8;

    static boolean in(Vec3 at, BlockPos cell) {
        double dx = at.x - (cell.getX() + 0.5), dz = at.z - (cell.getZ() + 0.5);
        return dx * dx + dz * dz <= IN * IN && Math.abs(at.y - cell.getY()) < 1;
    }

    private void next(Stage s, long now) {
        stage = s;
        since = now;
        walk = null;
    }

    // a fire between us and the corner, put out before the walk back (FireOnTheWay)
    private final adris.altoclef.util.helpers.FireOnTheWay fireWay = new adris.altoclef.util.helpers.FireOnTheWay();

    private Task fireOnTheWay(AltoClef mod) {
        return fireWay.step(mod, corner);
    }

    private Task walk(BlockPos to) {
        if (walk == null) walk = new GetToBlockTask(to);
        return walk;
    }

    // the walk back into the corner from outside it
    private final adris.altoclef.util.helpers.BoxWay intoBox = new adris.altoclef.util.helpers.BoxWay();

    private void lower(AltoClef mod) {
        // (the key let go is the shield down: the game lets go of a use whose key is up)
        mod.getInputControls().releaseOwnedBy(this);
    }

    // the main hand for a look: a sword (or a pickaxe of the list), else an empty hotbar slot. true once the hand holds one of
    // them, so that a held use key does nothing with the main hand
    private static boolean peekHand(AltoClef mod) {
        var inventory = mod.getPlayer().getInventory();
        net.minecraft.world.item.ItemStack main = mod.getPlayer().getMainHandItem();
        if (main.isEmpty() || java.util.Arrays.asList(SWORDS).contains(main.getItem())) return true;
        if (mod.getSlotHandler().forceEquipItem(SWORDS)) return true;
        for (int i = 0; i < 9; i++) {
            if (inventory.getItem(i).isEmpty()) {
                inventory.setSelectedSlot(i);
                return true;
            }
        }
        return false;
    }

    // the look out the fewest live blazes see from where they are now, one at least: the near cells first, the far ones when no
    // near cell is in any blaze's sight; the corner's own peek cell when none is. (the cell the most saw drew the whole crowd at
    // once, and from the cells by the corner most often none: the looks went on in nobody's sight, corner14)
    private BlockPos lookout(AltoClef mod) {
        java.util.List<Vec3> eyes = new java.util.ArrayList<>();
        try {
            for (Blaze b : mod.getEntityTracker().getTrackedEntities(Blaze.class)) {
                if (b.isAlive() && b.distanceTo(mod.getPlayer()) <= adris.altoclef.util.helpers.BlazeCover.SIGHT) eyes.add(b.getEyePosition());
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            return peek;
        }
        if (!FEWEST) return most(mod, eyes, lookouts);
        BlockPos near = fewest(mod, eyes, lookouts);
        if (near != null) return near;
        BlockPos far = fewest(mod, eyes, farouts);
        return far != null ? far : peek;
    }

    // corner14's choice: the look out the most live blazes see from where they are now; the peek cell when none sees any
    private BlockPos most(AltoClef mod, java.util.List<Vec3> eyes, java.util.List<BlockPos> cells) {
        BlockPos best = peek;
        int bestSeen = 0;
        for (BlockPos c : cells) {
            if (failed.contains(c)) continue;
            int seen = 0;
            for (Vec3 eye : eyes) {
                if (!adris.altoclef.util.helpers.BlazeCover.hidden(mod.getWorld(), java.util.List.of(eye), c.getX(), c.getY(), c.getZ(), mod.getPlayer())) seen++;
            }
            if (seen > bestSeen) {
                bestSeen = seen;
                best = c;
            }
        }
        return best;
    }

    // of the cells (nearest first), the one seen by the fewest of the eyes, at least one; fewer than MANY beats MANY or more
    private BlockPos fewest(AltoClef mod, java.util.List<Vec3> eyes, java.util.List<BlockPos> cells) {
        BlockPos best = null;
        int bestSeen = Integer.MAX_VALUE;
        for (BlockPos c : cells) {
            if (failed.contains(c)) continue;
            int seen = 0;
            for (Vec3 eye : eyes) {
                if (!adris.altoclef.util.helpers.BlazeCover.hidden(mod.getWorld(), java.util.List.of(eye), c.getX(), c.getY(), c.getZ(), mod.getPlayer())) seen++;
            }
            if (seen >= 1 && seen < bestSeen) {
                bestSeen = seen;
                best = c;
            }
        }
        return best;
    }

    // live blazes within the sight that have a line on a point
    private static int seenBy(AltoClef mod, Vec3 point) {
        int n = 0;
        try {
            for (Blaze b : mod.getEntityTracker().getTrackedEntities(Blaze.class)) {
                if (b.isAlive() && b.getEyePosition().distanceTo(point) <= adris.altoclef.util.helpers.BlazeCover.SIGHT
                        && adris.altoclef.util.helpers.GhastCover.clear(mod.getWorld(), b.getEyePosition(), point, mod.getPlayer())) n++;
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            return Integer.MAX_VALUE;
        }
        return n;
    }

    // the nearest rod within FETCH_RANGE of the corner (two up or down at most) that is safe to fetch now, or null
    private ItemEntity fetchable(AltoClef mod) {
        if (mod.getPlayer().getHealth() < FETCH_HP) return null;
        Vec3 c = Vec3.atBottomCenterOf(corner);
        java.util.Optional<ItemEntity> drop = mod.getEntityTracker().getClosestItemDrop(c,
                e -> !fetchFailed.contains(e.getId()) && e.position().distanceTo(c) <= FETCH_RANGE && Math.abs(e.getY() - c.y) <= 2,
                net.minecraft.world.item.Items.BLAZE_ROD);
        if (drop.isEmpty()) return null;
        ItemEntity d = drop.get();
        BlockPos at = d.blockPosition();
        if (seenBy(mod, new Vec3(at.getX() + 0.5, at.getY() + 1.62, at.getZ() + 0.5)) > FETCH_SEEN) return null;
        try {
            for (Blaze b : mod.getEntityTracker().getTrackedEntities(Blaze.class)) {
                if (b.isAlive() && b.position().distanceTo(d.position()) <= FETCH_CLEAR) return null;
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            return null;
        }
        return d;
    }

    // a live blaze with a line on our eyes
    private static boolean seen(AltoClef mod) {
        var p = mod.getPlayer();
        try {
            for (Blaze b : mod.getEntityTracker().getTrackedEntities(Blaze.class)) {
                if (b.isAlive() && b.distanceTo(p) <= adris.altoclef.util.helpers.BlazeCover.SIGHT
                        && adris.altoclef.util.helpers.GhastCover.clear(mod.getWorld(), b.getEyePosition(), p.getEyePosition(), p)) return true;
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            // the tracker rebuilds on another thread sometimes
        }
        return false;
    }

    // every blaze with a line on our eyes is charged, which is what a blaze with us for its target shows (none seeing us: no)
    private static boolean allSeeingCharged(AltoClef mod) {
        var p = mod.getPlayer();
        int seeing = 0;
        try {
            for (Blaze b : mod.getEntityTracker().getTrackedEntities(Blaze.class)) {
                if (!b.isAlive() || b.distanceTo(p) > adris.altoclef.util.helpers.BlazeCover.SIGHT) continue;
                if (!adris.altoclef.util.helpers.GhastCover.clear(mod.getWorld(), b.getEyePosition(), p.getEyePosition(), p)) continue;
                seeing++;
                if (!b.isOnFire()) return false;
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            return false;
        }
        return seeing > 0;
    }

    private static Blaze nearest(AltoClef mod) {
        Blaze best = null;
        double bestD = Double.MAX_VALUE;
        Vec3 us = mod.getPlayer().position();
        try {
            for (Blaze b : mod.getEntityTracker().getTrackedEntities(Blaze.class)) {
                double d = b.position().distanceTo(us);
                if (b.isAlive() && d < bestD) {
                    best = b;
                    bestD = d;
                }
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            // the tracker rebuilds on another thread sometimes
        }
        return best;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        adris.altoclef.util.helpers.BlazeShelter.lookingOut = false;
        lower(mod);
        if (looks > 0 || fetches > 0) Debug.logInternal("rods: the corner's looks so far " + looks + ", rods fetched " + fetches);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BlazeLureTask t && t.corner.equals(corner) && t.peek.equals(peek);
    }

    @Override
    protected String toDebugString() {
        return "Luring blazes round the corner at " + corner.toShortString();
    }
}
