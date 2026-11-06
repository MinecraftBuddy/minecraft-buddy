/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.altoclef;

import baritone.utils.ExperimentalMovement;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

// the knobs altoclef turns on baritone. same class and method names the old patched fork had so altoclef compiles as is
public class AltoClefSettings {

    private static final AltoClefSettings INSTANCE = new AltoClefSettings();

    // the fork had four mutexes and took one on every single cost lookup, from inside A*, and then made a BlockPos and a
    // stream for good measure. now the writers (the game thread) mutate plain lists and the readers get an immutable
    // snapshot behind one volatile read. all four getMutex() calls hand out this one lock, so the synchronized blocks in
    // BotBehaviour still hold everything still while it does its clear() + addAll() dance, and a reader that comes
    // looking for a rebuilt snapshot waits for the dance to end instead of seeing the empty list in the middle of it
    private final Object lock = new Object();

    private final WatchedSet<BlockPos> blocksToAvoidBreaking = new WatchedSet<>();
    private final WatchedList<Predicate<BlockPos>> breakAvoiders = new WatchedList<>();
    private final WatchedList<Predicate<BlockPos>> placeAvoiders = new WatchedList<>();
    private final WatchedList<Predicate<BlockPos>> forceCanWalkOn = new WatchedList<>();
    private final WatchedList<Predicate<BlockPos>> forceAvoidWalkThrough = new WatchedList<>();
    private final WatchedList<BiPredicate<BlockState, ItemStack>> forceUseTool = new WatchedList<>();
    private final WatchedSet<Item> protectedItems = new WatchedSet<>();
    // how many of a protected item altoclef is holding for itself. a protected item with no entry here is spoken for in
    // full (that's most of them). only written through setProtectedReserve, under the lock
    private final Map<Item, Integer> protectedReserve = new HashMap<>();

    // nothing reads these, the fork's heuristic hook was commented out and flowing water was never wired up. they're here
    // because BotBehaviour copies them in and out and it would be rude to make it stop
    private final List<BiFunction<Double, BlockPos, Double>> globalHeuristics = new ArrayList<>();
    private volatile boolean allowFlowingWaterPass;

    private volatile boolean pauseInteractions;
    private volatile boolean dontPlaceBucketButStillFall;
    private volatile boolean allowSwimThroughLava;
    // combat keeps this ahead of the clock every tick it wants no jump moves planned. it runs out by itself on purpose, so a
    // clear that never comes (mob defense off, a disconnect) can't leave the bot walking without jumps forever
    private volatile long jumpsOffUntilTick = Long.MIN_VALUE;
    private volatile boolean canWalkOnEndPortal;
    // the end portal frame rules (walkable, never broken, never walked into, nothing mined out from under) are altoclef's,
    // stock soprano treats a frame like any other odd block. only on while altoclef has the bot, see BaritoneSettingsScope
    private volatile boolean endPortalFrameRules;

    // null means a list changed and whoever asks next builds a new one. EMPTY to start so the idle case never builds anything
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    public static AltoClefSettings getInstance() {
        return INSTANCE;
    }

    // everything the pathing thread needs to know about, frozen. arrays are null when there's nothing in them, so
    // the "altoclef isn't doing anything" case is one null check per question. nobody may write to these
    public static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot(null, null, null, null, null, null, null, null);

        // block positions are packed longs so asking about one doesn't need a BlockPos
        public final LongOpenHashSet breakPositions;
        public final Predicate<BlockPos>[] breakAvoiders;
        public final Predicate<BlockPos>[] placeAvoiders;
        public final Predicate<BlockPos>[] forceWalkOn;
        public final Predicate<BlockPos>[] avoidWalkThrough;
        public final BiPredicate<BlockState, ItemStack>[] forceUseTool;
        public final HashSet<Item> protectedItems;
        public final HashMap<Item, Integer> protectedReserve;

        Snapshot(LongOpenHashSet breakPositions, Predicate<BlockPos>[] breakAvoiders, Predicate<BlockPos>[] placeAvoiders,
                 Predicate<BlockPos>[] forceWalkOn, Predicate<BlockPos>[] avoidWalkThrough,
                 BiPredicate<BlockState, ItemStack>[] forceUseTool, HashSet<Item> protectedItems,
                 HashMap<Item, Integer> protectedReserve) {
            this.breakPositions = breakPositions;
            this.breakAvoiders = breakAvoiders;
            this.placeAvoiders = placeAvoiders;
            this.forceWalkOn = forceWalkOn;
            this.avoidWalkThrough = avoidWalkThrough;
            this.forceUseTool = forceUseTool;
            this.protectedItems = protectedItems;
            this.protectedReserve = protectedReserve;
        }

        // does any predicate say yes. the pos is only good for the duration of the call, predicates must not keep it
        public static boolean anyMatch(Predicate<BlockPos>[] predicates, BlockPos pos) {
            if (predicates == null) {
                return false;
            }
            for (Predicate<BlockPos> predicate : predicates) {
                try {
                    if (predicate.test(pos)) {
                        return true;
                    }
                } catch (Throwable t) {
                    // task code in the middle of a search or an executor tick. a rule that blows up is a rule that says no
                    AltoClefBridge.onHookError(t);
                }
            }
            return false;
        }

        public boolean avoidsBreaking(BlockPos pos) {
            return (breakPositions != null && breakPositions.contains(pos.asLong())) || anyMatch(breakAvoiders, pos);
        }

        // true when there is anything at all for the break question, so callers can skip making a BlockPos
        public boolean hasBreakRules() {
            return breakPositions != null || breakAvoiders != null;
        }

        public boolean hasPlaceRules() {
            return placeAvoiders != null;
        }

        // any of the four position rules the pathing code cares about
        public boolean hasPathingRules() {
            return breakPositions != null || breakAvoiders != null || placeAvoiders != null || forceWalkOn != null || avoidWalkThrough != null;
        }
    }

    // swap in a fresh snapshot only if somebody mutated something. the common read is the volatile load and nothing else
    public Snapshot snapshot() {
        Snapshot s = snapshot;
        if (s != null) {
            return s;
        }
        synchronized (lock) {
            s = snapshot;
            if (s == null) {
                s = buildSnapshot();
                snapshot = s;
            }
            return s;
        }
    }

    @SuppressWarnings("unchecked")
    private Snapshot buildSnapshot() {
        return new Snapshot(
                blocksToAvoidBreaking.isEmpty() ? null : packed(blocksToAvoidBreaking),
                breakAvoiders.isEmpty() ? null : breakAvoiders.toArray(new Predicate[0]),
                placeAvoiders.isEmpty() ? null : placeAvoiders.toArray(new Predicate[0]),
                forceCanWalkOn.isEmpty() ? null : forceCanWalkOn.toArray(new Predicate[0]),
                forceAvoidWalkThrough.isEmpty() ? null : forceAvoidWalkThrough.toArray(new Predicate[0]),
                forceUseTool.isEmpty() ? null : forceUseTool.toArray(new BiPredicate[0]),
                protectedItems.isEmpty() ? null : new HashSet<>(protectedItems),
                protectedReserve.isEmpty() ? null : new HashMap<>(protectedReserve)
        );
    }

    private static LongOpenHashSet packed(Collection<BlockPos> positions) {
        LongOpenHashSet out = new LongOpenHashSet(positions.size());
        for (BlockPos pos : positions) {
            out.add(pos.asLong());
        }
        return out;
    }

    // altoclef mutates the lists it gets back from the getters (BotBehaviour copies them in and out), so the lists have
    // to notice. everything that can change them goes through here and throws the snapshot away. cheap, no allocation
    private void dirty() {
        snapshot = null;
    }

    private final class WatchedList<E> extends AbstractList<E> {
        private final ArrayList<E> items = new ArrayList<>();

        @Override
        public E get(int index) {
            return items.get(index);
        }

        @Override
        public int size() {
            return items.size();
        }

        @Override
        public E set(int index, E element) {
            synchronized (lock) {
                E old = items.set(index, element);
                dirty();
                return old;
            }
        }

        @Override
        public void add(int index, E element) {
            synchronized (lock) {
                items.add(index, element);
                modCount++;
                dirty();
            }
        }

        @Override
        public E remove(int index) {
            synchronized (lock) {
                E old = items.remove(index);
                modCount++;
                dirty();
                return old;
            }
        }

        // AbstractList would do these one element at a time, which is a lot of dirtying for no reason
        @Override
        public boolean addAll(Collection<? extends E> c) {
            synchronized (lock) {
                boolean changed = items.addAll(c);
                if (changed) {
                    modCount++;
                    dirty();
                }
                return changed;
            }
        }

        @Override
        public void clear() {
            synchronized (lock) {
                // clearing nothing is not a change. BotBehaviour does it on every applyState
                if (items.isEmpty()) {
                    return;
                }
                items.clear();
                modCount++;
                dirty();
            }
        }
    }

    // HashSet because the fork's getters said HashSet and BotBehaviour holds them as one
    private final class WatchedSet<E> extends HashSet<E> {
        @Override
        public boolean add(E e) {
            synchronized (lock) {
                boolean changed = super.add(e);
                if (changed) {
                    dirty();
                }
                return changed;
            }
        }

        @Override
        public boolean remove(Object o) {
            synchronized (lock) {
                boolean changed = super.remove(o);
                if (changed) {
                    dirty();
                }
                return changed;
            }
        }

        @Override
        public boolean addAll(Collection<? extends E> c) {
            synchronized (lock) {
                boolean changed = super.addAll(c);
                if (changed) {
                    dirty();
                }
                return changed;
            }
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            synchronized (lock) {
                boolean changed = super.removeAll(c);
                if (changed) {
                    dirty();
                }
                return changed;
            }
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            synchronized (lock) {
                boolean changed = super.retainAll(c);
                if (changed) {
                    dirty();
                }
                return changed;
            }
        }

        @Override
        public boolean removeIf(Predicate<? super E> filter) {
            synchronized (lock) {
                boolean changed = super.removeIf(filter);
                if (changed) {
                    dirty();
                }
                return changed;
            }
        }

        @Override
        public void clear() {
            synchronized (lock) {
                if (isEmpty()) {
                    return;
                }
                dirty();
                super.clear();
            }
        }

        @Override
        public Iterator<E> iterator() {
            Iterator<E> inner = super.iterator();
            return new Iterator<E>() {
                @Override
                public boolean hasNext() {
                    return inner.hasNext();
                }

                @Override
                public E next() {
                    return inner.next();
                }

                @Override
                public void remove() {
                    synchronized (lock) {
                        dirty();
                        inner.remove();
                    }
                }
            };
        }
    }

    public void canWalkOnEndPortal(boolean canWalk) {
        canWalkOnEndPortal = canWalk;
    }

    public boolean isCanWalkOnEndPortal() {
        return canWalkOnEndPortal;
    }

    public void endPortalFrameRules(boolean on) {
        endPortalFrameRules = on;
    }

    public boolean hasEndPortalFrameRules() {
        return endPortalFrameRules;
    }

    public void avoidBlockBreak(BlockPos pos) {
        // BlockPos can be a MutableBlockPos, don't hold onto somebody else's
        blocksToAvoidBreaking.add(pos.immutable());
    }

    public void avoidBlockBreak(Predicate<BlockPos> avoider) {
        breakAvoiders.add(avoider);
    }

    public void avoidBlockPlace(Predicate<BlockPos> avoider) {
        placeAvoiders.add(avoider);
    }

    public void configurePlaceBucketButDontFall(boolean allow) {
        dontPlaceBucketButStillFall = allow;
    }

    public boolean shouldNotPlaceBucketButStillFall() {
        return dontPlaceBucketButStillFall;
    }

    public boolean shouldAvoidBreaking(int x, int y, int z) {
        Snapshot s = snapshot();
        if (!s.hasBreakRules()) {
            return false; // nothing to ask, so don't make a pos
        }
        return s.avoidsBreaking(new BlockPos(x, y, z));
    }

    public boolean shouldAvoidBreaking(BlockPos pos) {
        return snapshot().avoidsBreaking(pos);
    }

    public boolean shouldAvoidPlacingAt(BlockPos pos) {
        return Snapshot.anyMatch(snapshot().placeAvoiders, pos);
    }

    public boolean shouldAvoidPlacingAt(int x, int y, int z) {
        Snapshot s = snapshot();
        return s.placeAvoiders != null && Snapshot.anyMatch(s.placeAvoiders, new BlockPos(x, y, z));
    }

    public boolean canWalkOnForce(int x, int y, int z) {
        Snapshot s = snapshot();
        return s.forceWalkOn != null && Snapshot.anyMatch(s.forceWalkOn, new BlockPos(x, y, z));
    }

    public boolean shouldAvoidWalkThroughForce(BlockPos pos) {
        return Snapshot.anyMatch(snapshot().avoidWalkThrough, pos);
    }

    public boolean shouldAvoidWalkThroughForce(int x, int y, int z) {
        Snapshot s = snapshot();
        return s.avoidWalkThrough != null && Snapshot.anyMatch(s.avoidWalkThrough, new BlockPos(x, y, z));
    }

    public boolean shouldForceUseTool(BlockState state, ItemStack tool) {
        BiPredicate<BlockState, ItemStack>[] predicates = snapshot().forceUseTool;
        if (predicates == null) {
            return false;
        }
        for (BiPredicate<BlockState, ItemStack> predicate : predicates) {
            try {
                if (predicate.test(state, tool)) {
                    return true;
                }
            } catch (Throwable t) {
                AltoClefBridge.onHookError(t);
            }
        }
        return false;
    }

    public boolean isInteractionPaused() {
        return pauseInteractions;
    }

    public void setInteractionPaused(boolean paused) {
        pauseInteractions = paused;
    }

    public boolean canSwimThroughLava() {
        return allowSwimThroughLava;
    }

    public void allowSwimThroughLava(boolean allow) {
        allowSwimThroughLava = allow;
    }

    public void noJumpsFor(long now) {
        jumpsOffUntilTick = now + ExperimentalMovement.JUMPS_OFF_TICKS;
    }

    public long jumpsOffUntilTick() {
        return jumpsOffUntilTick;
    }

    public boolean isFlowingWaterPassAllowed() {
        return allowFlowingWaterPass;
    }

    public void setFlowingWaterPass(boolean pass) {
        allowFlowingWaterPass = pass;
    }

    public boolean isItemProtected(Item item) {
        HashSet<Item> items = snapshot().protectedItems;
        return items != null && items.contains(item);
    }

    // nobody put a number on this one, so all of it is spoken for
    public static final int FULLY_RESERVED = Integer.MAX_VALUE;

    // how many of this item the movements have to leave alone: none for something unprotected, all of it for a protected
    // item nobody put a number on, otherwise whatever altoclef said it is saving. what's held above that is free to build with
    public int reservedCount(Item item) {
        Snapshot s = snapshot();
        if (s.protectedItems == null || !s.protectedItems.contains(item)) {
            return 0;
        }
        Integer saving = s.protectedReserve == null ? null : s.protectedReserve.get(item);
        return saving == null ? FULLY_RESERVED : saving;
    }

    // BotBehaviour adds up its whole state stack and hands the sum here. it gets called on every applyState, so a map that
    // says what the last one said changes nothing (a rebuilt snapshot is not free)
    public void setProtectedReserve(Map<Item, Integer> reserve) {
        synchronized (lock) {
            if (protectedReserve.equals(reserve)) {
                return;
            }
            protectedReserve.clear();
            protectedReserve.putAll(reserve);
            dirty();
        }
    }

    public void protectItem(Item item) {
        protectedItems.add(item);
    }

    public void stopProtectingItem(Item item) {
        protectedItems.remove(item);
    }

    public static final int TOGGLE_END_PORTAL = 1;
    public static final int TOGGLE_LAVA = 2;
    public static final int TOGGLE_FRAMES = 4;

    // the block type toggles bake into PrecomputedData's table, so it needs to know when to rebuild
    public int blockToggleBits() {
        return (canWalkOnEndPortal ? TOGGLE_END_PORTAL : 0) | (allowSwimThroughLava ? TOGGLE_LAVA : 0) | (endPortalFrameRules ? TOGGLE_FRAMES : 0);
    }

    // back to what an idle soprano looks like: no rules, no toggles, nothing paused. altoclef calls this when it gives
    // up for the session (or dies halfway through starting) so it can never leave any of its knobs turned
    public void resetAll() {
        synchronized (lock) {
            blocksToAvoidBreaking.clear();
            breakAvoiders.clear();
            placeAvoiders.clear();
            forceCanWalkOn.clear();
            forceAvoidWalkThrough.clear();
            forceUseTool.clear();
            protectedItems.clear();
            protectedReserve.clear();
            globalHeuristics.clear();
            dirty();
        }
        allowFlowingWaterPass = false;
        pauseInteractions = false;
        dontPlaceBucketButStillFall = false;
        allowSwimThroughLava = false;
        jumpsOffUntilTick = Long.MIN_VALUE;
        canWalkOnEndPortal = false;
        endPortalFrameRules = false;
    }

    public HashSet<BlockPos> getBlocksToAvoidBreaking() {
        return blocksToAvoidBreaking;
    }

    public List<Predicate<BlockPos>> getBreakAvoiders() {
        return breakAvoiders;
    }

    public List<Predicate<BlockPos>> getPlaceAvoiders() {
        return placeAvoiders;
    }

    public List<Predicate<BlockPos>> getForceWalkOnPredicates() {
        return forceCanWalkOn;
    }

    public List<Predicate<BlockPos>> getForceAvoidWalkThroughPredicates() {
        return forceAvoidWalkThrough;
    }

    public List<BiPredicate<BlockState, ItemStack>> getForceUseToolPredicates() {
        return forceUseTool;
    }

    public List<BiFunction<Double, BlockPos, Double>> getGlobalHeuristics() {
        return globalHeuristics;
    }

    public HashSet<Item> getProtectedItems() {
        return protectedItems;
    }

    public Object getBreakMutex() {
        return lock;
    }

    public Object getPlaceMutex() {
        return lock;
    }

    public Object getPropertiesMutex() {
        return lock;
    }

    public Object getGlobalHeuristicMutex() {
        return lock;
    }
}
