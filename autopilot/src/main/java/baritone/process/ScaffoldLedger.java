/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.api.utils.BetterBlockPos;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

// every block that went down during a build where the file wants nothing (air, or outside the box), and every right
// block something other than the builder dug out to get through. the scaffold stays put till the whole build is done
// and then comes down in one go, the dug out ones wait till we're out of the way. one per build: it outlives stops,
// resumes, pauses and repeats of the same build, a different build starts it over. no world in here, the tests ask it
// straight
final class ScaffoldLedger {

    // a pillar next to the box is still ours. further out than this is the walk there, nobody wants that broken
    static final int MARGIN = 8;
    // cleanup rounds per build end. a round's own pillars come down in the next one
    static final int ROUNDS = 3;

    enum Detour {
        // first time: left out while we're still going past it
        PASSING,
        // second time: it waits for the end of the build
        LATE,
        // third time and on: the path wants that hole more than we want the block
        GIVE_UP
    }

    // which build this is about. the origin as build() got it, a repeat moves the live one and it's still the same build
    private String name;
    private Vec3i origin;
    private Object dimension;
    private final LongOpenHashSet scaffold = new LongOpenHashSet();
    // scaffold the cleanup ran out of rounds on. still never broken, just never cleaned up either
    private final LongOpenHashSet abandoned = new LongOpenHashSet();
    // blocks that went in where the file wants a different real block. not cleanup work, the builder swaps them like any
    // wrong block, just not out from under our feet (BuilderProcess.besideUs). so only known, never kept
    private final LongOpenHashSet solid = new LongOpenHashSet();
    // what went in, per scaffold and solid cell. the builder is off for whole gathers and nights, and a bed or a chest
    // that lands in the same cell meanwhile is nobody's scaffold. a Block in the game, anything in the tests
    private final Long2ObjectOpenHashMap<Object> what = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap detours = new Long2IntOpenHashMap();
    // first time detours we're still going past, and of those the ones we haven't walked out of reach of yet
    private final LongOpenHashSet passing = new LongOpenHashSet();
    private final LongOpenHashSet near = new LongOpenHashSet();
    private final LongOpenHashSet late = new LongOpenHashSet();
    // scaffold cells that went back to air or the right block, ever. the watchdog counts these during the cleanup
    private long cleared;
    // bumped on every change, the bcc copies only when it moved
    private int version;
    // which wipe of which ledger this is. a click is confirmed a tick or more after it went out, and by then a one block
    // build (altoclef's portal frame, a cell at a time) can be the next cell's build already. from one counter for all
    // of them, so a fresh ledger never hands out a number an old one had
    private static final AtomicInteger GENERATIONS = new AtomicInteger();
    private int generation = GENERATIONS.incrementAndGet();

    // true when it's the same build and the ledger carries on, false when it just got wiped for a new one
    boolean own(String name, Vec3i origin) {
        if (Objects.equals(this.name, name) && Objects.equals(this.origin, origin)) {
            return true;
        }
        clear();
        this.name = name;
        this.origin = origin;
        return false;
    }

    void clear() {
        scaffold.clear();
        abandoned.clear();
        solid.clear();
        what.clear();
        detours.clear();
        passing.clear();
        near.clear();
        late.clear();
        version++;
        generation = GENERATIONS.incrementAndGet();
    }

    // a block went in where the file wants nothing. who put it there doesn't matter, it comes down at the end either way
    void scaffold(long cell, Object block) {
        solid.remove(cell);
        what.put(cell, block);
        if (scaffold.add(cell)) {
            abandoned.remove(cell);
            version++;
        }
    }

    // nothing to copy for the bcc, only the game thread asks
    void solid(long cell, Object block) {
        solid.add(cell);
        what.put(cell, block);
    }

    // what we put in a scaffold or solid cell, null for none
    Object what(long cell) {
        return what.get(cell);
    }

    boolean isSolid(long cell) {
        return solid.contains(cell);
    }

    LongOpenHashSet solid() {
        return solid;
    }

    boolean isScaffold(long cell) {
        return scaffold.contains(cell) || abandoned.contains(cell);
    }

    // the cell holds air, what the file wants or somebody else's block: nothing of ours to take down any more
    // counts = it's air or the right block now, not water or a chest
    void gone(long cell, boolean counts) {
        solid.remove(cell);
        what.remove(cell);
        if (scaffold.remove(cell) | abandoned.remove(cell)) {
            if (counts) {
                cleared++;
            }
            version++;
        }
    }

    // and a dug out one is back. the count stays, that's the whole point of counting
    void filled(long cell) {
        if (passing.remove(cell) | late.remove(cell)) {
            near.remove(cell);
            version++;
        }
    }

    Detour detour(long cell) {
        int n = detours.addTo(cell, 1) + 1;
        version++;
        if (n == 1) {
            passing.add(cell);
            near.add(cell);
            return Detour.PASSING;
        }
        passing.remove(cell);
        near.remove(cell);
        if (n == 2) {
            late.add(cell);
            return Detour.LATE;
        }
        late.remove(cell);
        return Detour.GIVE_UP;
    }

    // the passing ones still on the path (or the one that just ended) or still in reach stay out, the rest rejoin. once
    // out they stay out: a path that goes to put one back walks right up to it, and that's not a reason to skip it again.
    // onPath and inReach get asked per cell. the ones that rejoined, so the builder can look at them again
    LongArrayList updatePassing(CellTest onPath, CellTest inReach) {
        LongArrayList rejoined = new LongArrayList();
        LongIterator it = passing.iterator();
        while (it.hasNext()) {
            long cell = it.nextLong();
            if (near.contains(cell) && !inReach.test(cell)) {
                near.remove(cell);
            }
            if (!near.contains(cell) && !onPath.test(cell)) {
                it.remove();
                rejoined.add(cell);
            }
        }
        if (!rejoined.isEmpty()) {
            version++;
        }
        return rejoined;
    }

    // a ledger from another dimension is about somebody else's blocks. a reconnect to the same one keeps it
    void world(Object dimension) {
        if (!Objects.equals(this.dimension, dimension)) {
            if (this.dimension != null) {
                clear();
            }
            this.dimension = dimension;
        }
    }

    // nothing else is left, so they're the work now. true if there were any
    boolean endPassing() {
        if (passing.isEmpty()) {
            return false;
        }
        passing.clear();
        near.clear();
        version++;
        return true;
    }

    // the ones the cleanup puts back. once it has, they're plain cells like any other
    LongOpenHashSet late() {
        return late;
    }

    void dropLate() {
        if (!late.isEmpty()) {
            late.clear();
            version++;
        }
    }

    LongOpenHashSet scaffold() {
        return scaffold;
    }

    LongOpenHashSet passing() {
        return passing;
    }

    LongOpenHashSet abandoned() {
        return abandoned;
    }

    // dug out cells still waiting on something, the passing ones and the late ones. a copy
    LongOpenHashSet waiting() {
        LongOpenHashSet out = new LongOpenHashSet(passing);
        out.addAll(late);
        return out;
    }

    int detours(long cell) {
        return detours.get(cell);
    }

    // a fresh start of the same build gets a fresh set of rounds at what the last one left up
    void unabandon() {
        if (!abandoned.isEmpty()) {
            scaffold.addAll(abandoned);
            abandoned.clear();
            version++;
        }
    }

    // out of rounds, these stay up
    int abandon() {
        int n = scaffold.size();
        if (n > 0) {
            abandoned.addAll(scaffold);
            scaffold.clear();
            version++;
        }
        return n;
    }

    boolean hasScaffold() {
        return !scaffold.isEmpty();
    }

    boolean hasLate() {
        return !late.isEmpty();
    }

    long cleared() {
        return cleared;
    }

    int version() {
        return version;
    }

    int generation() {
        return generation;
    }

    // a stamp from before the last wipe is about blocks of a build that's not this one any more
    boolean current(int stamp) {
        return stamp == generation;
    }

    // what a bcc gets, copied once per change and not per tick
    View view(LongOpenHashSet active, boolean lateIsWork) {
        LongOpenHashSet kept = new LongOpenHashSet(scaffold.size() + abandoned.size());
        LongIterator it = scaffold.iterator();
        while (it.hasNext()) {
            long cell = it.nextLong();
            if (active == null || !active.contains(cell)) {
                kept.add(cell);
            }
        }
        kept.addAll(abandoned);
        LongOpenHashSet deferred = new LongOpenHashSet(passing);
        if (!lateIsWork) {
            deferred.addAll(late);
        }
        return new View(kept, deferred);
    }

    // kept = scaffold the builder leaves alone right now, deferred = dug out cells that are fine empty right now
    record View(LongOpenHashSet kept, LongOpenHashSet deferred) {

        static final View EMPTY = new View(new LongOpenHashSet(), new LongOpenHashSet());
    }

    @FunctionalInterface
    interface CellTest {
        boolean test(long cell);
    }

    // within MARGIN of the box (min and max inclusive)
    static boolean nearBox(int x, int y, int z, int x0, int y0, int z0, int x1, int y1, int z1) {
        return x >= x0 - MARGIN && x <= x1 + MARGIN && y >= y0 - MARGIN && y <= y1 + MARGIN
                && z >= z0 - MARGIN && z <= z1 + MARGIN;
    }

    // nearest column first, then top down, so we never mine the block we stand on before the one above it. same idea
    // as altoclef's old scaffold list
    static List<BetterBlockPos> cleanupOrder(Collection<BetterBlockPos> cells, BlockPos from) {
        List<BetterBlockPos> out = new ArrayList<>(cells);
        Comparator<BetterBlockPos> order = Comparator.comparingLong(p -> flat(p, from));
        order = order.thenComparingInt(p -> p.x).thenComparingInt(p -> p.z).thenComparingInt(p -> -p.y);
        out.sort(order);
        return out;
    }

    // whole columns go by their xz distance, a pillar is one trip and not three
    private static long flat(BetterBlockPos p, BlockPos from) {
        long dx = p.x - from.getX();
        long dz = p.z - from.getZ();
        return dx * dx + dz * dz;
    }
}
