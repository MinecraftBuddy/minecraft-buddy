package adris.altoclef;

import adris.altoclef.util.slots.Slot;
import baritone.altoclef.AltoClefSettings;
import baritone.altoclef.SettingsOverrides;
import baritone.api.Settings;
import baritone.api.utils.RayTraceUtils;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.core.BlockPos;
import baritone.api.utils.Pair;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Represents the current behaviour/"on the fly settings" of the bot.
 * <p>
 * Use this to change how the bot works for the duration of a task.
 * <p>
 * (for example, "Build this bridge and avoid mining any blocks nearby")
 */
public class BotBehaviour {

    private final AltoClef _mod;
    Deque<State> _states = new ArrayDeque<>();
    // mob defense exclusions of tasks a chain has paused, see holdWhilePaused
    private final PausedHolds<Predicate<Entity>> _pausedExclusions = new PausedHolds<>();

    public BotBehaviour(AltoClef mod) {
        _mod = mod;

        // Start with one state.
        push();
    }

    // Getter(s)

    /**
     * Returns the current state of Behaviour for escapeLava
     *
     * @return The current state of Behaviour for escapeLava
     */
    public boolean shouldEscapeLava() {
        return current().escapeLava;
    }

    /// Parameters

    /**
     * If the bot should escape lava or not, part of WorldSurvivalChain
     *
     * @param allow True if the bot should escape lava
     */
    public void setEscapeLava(boolean allow) {
        current().escapeLava = allow;
        current().applyState();
    }

    public void setFollowDistance(double distance) {
        current().followOffsetDistance = distance;
        current().applyState();
    }

    public void setMineScanDroppedItems(boolean value) {
        current().mineScanDroppedItems = value;
        current().applyState();
    }


    public boolean exclusivelyMineLogs() {
        return current().exclusivelyMineLogs;
    }

    public void setExclusivelyMineLogs(boolean value) {
        current().exclusivelyMineLogs = value;
        current().applyState();
    }

    public boolean shouldExcludeFromForcefield(Entity entity) {
        if (!current().excludeFromForceField.isEmpty()) {
            for (Predicate<Entity> pred : current().excludeFromForceField) {
                if (pred.test(entity)) return true;
            }
        }
        return false;
    }

    public void addForceFieldExclusion(Predicate<Entity> pred) {
        current().excludeFromForceField.add(pred);
        // Not needed, as excludeFromForceField isn't applied anywhere else.
        // current.applyState();
    }

    // stronger than the forcefield one: mob defense will not run away from it or send a kill task after it either.
    // for tasks that fight one specific mob on their own terms (the golem on its pillar, nothing else walks off it)
    public boolean shouldExcludeFromMobDefense(Entity entity) {
        for (Predicate<Entity> pred : current().excludeFromMobDefense) {
            if (pred.test(entity)) return true;
        }
        return _pausedExclusions.any(pred -> pred.test(entity));
    }

    // something has mob defense excluded right now (the child levels copy their parent's, so the top one says it all).
    // a paused task's lines count too: the blaze task is paused exactly when mob defense has the wheel, which is exactly
    // when the heal-up hold asks
    public boolean hasMobDefenseExclusion() {
        return !current().excludeFromMobDefense.isEmpty() || _pausedExclusions.size() > 0;
    }

    public void addMobDefenseExclusion(Predicate<Entity> pred) {
        current().excludeFromMobDefense.add(pred);
    }

    // a chain cutting in pops the task's level, and with it the "these blazes are mine" line. mob defense then counted the
    // blazes it had been told to ignore, and three of them is a crowd, which is a run. so a task that is only paused hands its
    // level's mob defense exclusions over here until it comes back (or stops for real). only those: the rest of the
    // level is popped like it always was. level is the one the task pushed, not current(): the interrupt stops the
    // parent before the child, so by the time we get here the top can be anybody's
    public void holdWhilePaused(Object owner, Object chain, State level) {
        if (level == null) {
            _pausedExclusions.release(owner);
            return;
        }
        _pausedExclusions.hold(owner, chain, level.excludeFromMobDefense);
    }

    public void releasePaused(Object owner) {
        _pausedExclusions.release(owner);
    }

    // the chain got the wheel back. its tasks hold again on the next cut, and a root that finished while it was out
    // gets dropped without a stop or a start, so nobody else would ever let go of its blazes
    public void releasePausedFor(Object chain) {
        _pausedExclusions.releaseFor(chain);
    }

    public void clearPaused() {
        _pausedExclusions.clear();
    }

    public List<Pair<Slot, Predicate<ItemStack>>> getConversionSlots() {
        return current().conversionSlots;
    }

    public void markSlotAsConversionSlot(Slot slot, Predicate<ItemStack> itemBelongsHere) {
        current().conversionSlots.add(new Pair<>(slot, itemBelongsHere));
        // apply not needed
    }

    public void avoidBlockBreaking(BlockPos pos) {
        // tasks call this every tick with the same furnace. a set that already has it is not news
        if (!current().blocksToAvoidBreaking.add(pos.immutable())) {
            return;
        }
        current().applyState();
    }

    public void avoidBlockBreaking(Predicate<BlockPos> pred) {
        current().toAvoidBreaking.add(pred);
        current().applyState();
    }

    public void avoidBlockPlacing(Predicate<BlockPos> pred) {
        current().toAvoidPlacing.add(pred);
        current().applyState();
    }

    public void allowWalkingOn(Predicate<BlockPos> pred) {
        current().allowWalking.add(pred);
        current().applyState();
    }

    public void avoidWalkingThrough(Predicate<BlockPos> pred) {
        current().avoidWalkingThrough.add(pred);
        current().applyState();
    }


    public void forceUseTool(BiPredicate<BlockState, ItemStack> pred) {
        current().forceUseTools.add(pred);
        current().applyState();
    }

    public void setRayTracingFluidHandling(ClipContext.Fluid fluidHandling) {
        // ClearLiquidTask says this every tick. the static gets reset behind our back sometimes, so check it too
        if (current().rayFluidHandling == fluidHandling && RayTraceUtils.fluidHandling == fluidHandling) {
            return;
        }
        current().rayFluidHandling = fluidHandling;
        //Debug.logMessage("OOF: " + fluidHandling);
        current().applyState();
    }

    public void setAllowWalkThroughFlowingWater(boolean value) {
        // the portal speedrun says this every tick. only the settings object can disagree with us (a reset), so ask it too
        if (current()._allowWalkThroughFlowingWater == value && _mod.getExtraBaritoneSettings().isFlowingWaterPassAllowed() == value) {
            return;
        }
        current()._allowWalkThroughFlowingWater = value;
        current().applyState();
    }

    public void setPauseOnLostFocus(boolean pauseOnLostFocus) {
        current().pauseOnLostFocus = pauseOnLostFocus;
        current().applyState();
    }

    public void addProtectedItems(Item... items) {
        // ResourceTask and friends do this every tick. it used to be a list with no dedupe, so it grew forever and
        // every tick rebuilt the pathing snapshot from it. now it only goes through when something is actually new
        // no number given, so the movements get none of it (see reserveProtectedItems for the polite kind)
        boolean changed = false;
        for (Item item : items) {
            changed |= current().protectedItems.add(item);
            changed |= current().wholeProtected.add(item);
        }
        if (changed) {
            current().applyState();
        }
    }

    // protected, but only the first `count` of each. for a task that is collecting N of something for a target: the movements
    // may still build with whatever is held above N, they just can't eat into the N itself (a recipe that needs 6 cobble was
    // getting its own cobble pillared into the ground, then mining more, then pillaring it again). levels add up, and an
    // item any level protected without a number (addProtectedItems) stays whole. it only ever adds or overwrites keys: a
    // caller whose map shrinks has to zero the keys it drops, or removeProtectedItems them, or the old count sticks till pop
    //
    // this form writes into whatever level is on top right now, which is only the caller's own if nobody pushed on top of it.
    // a task that has children should keep the State its push() handed back and use the form below
    public void reserveProtectedItems(Map<Item, Integer> counts) {
        reserveProtectedItems(null, counts);
    }

    // same, into the level the caller pushed. a parent writes every tick, and once a child pushed its own level the plain
    // form was putting the parent's numbers in the child's level while the parent's own copy sat there stale. the sum then
    // read double for as long as the child lived (19, 36, 19, 36, and every flip was an applyState). null means the top
    // level, and a level that has been popped already is ignored: its numbers went with it
    public void reserveProtectedItems(State level, Map<Item, Integer> counts) {
        State target = level == null ? current() : level;
        List<State> from = levelsFrom(_states, target);
        if (from.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (Map.Entry<Item, Integer> entry : counts.entrySet()) {
            // a child pushed after this level copied its protected set, so the ones above have to hear about it too
            for (State s : from) {
                changed |= s.protectedItems.add(entry.getKey());
            }
            Integer old = target.reserve.put(entry.getKey(), entry.getValue());
            changed |= !entry.getValue().equals(old);
        }
        if (changed) {
            current().applyState();
        }
    }

    // a floor for a level: at least this many of each item stay spoken for for as long as the level lives, however the task
    // levels above come and go. the task reserves are slices of one outstanding need (6 of the 18 cobble the stone kit wants),
    // so they do not add to a floor, the biggest of the two wins (see sumReserves). unlike a reserve the whole map is
    // replaced: an item that is gone from it (or at 0) stops being protected, unless somebody else reserved it too
    public void setReserveFloor(State level, Map<Item, Integer> floor) {
        if (level == null) {
            return;
        }
        List<State> from = levelsFrom(_states, level);
        if (from.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (Map.Entry<Item, Integer> entry : floor.entrySet()) {
            if (entry.getValue() > 0) {
                changed |= claimFloor(from, entry.getKey());
            }
        }
        Map<Item, Integer> next = new HashMap<>();
        for (Map.Entry<Item, Integer> entry : floor.entrySet()) {
            next.put(entry.getKey(), Math.max(0, entry.getValue()));
        }
        for (Item gone : level.floor.keySet()) {
            // stays in the map at 0 so a child level that copied the protection reads "nothing spoken for" and not "all of it"
            if (next.getOrDefault(gone, 0) <= 0) {
                next.put(gone, 0);
                changed |= releaseFloor(from, gone);
            }
        }
        if (!next.equals(level.floor)) {
            level.floor.clear();
            level.floor.putAll(next);
            changed = true;
        }
        if (changed) {
            current().applyState();
        }
    }

    private static boolean claimFloor(List<State> from, Item item) {
        boolean changed = false;
        for (State s : from) {
            if (s.protectedItems.add(item)) {
                s.floorAdded.add(item);
                changed = true;
            }
        }
        return changed;
    }

    // only takes the protection back where the floor was what put it, and nobody else has since asked for the same item
    private static boolean releaseFloor(List<State> from, Item item) {
        boolean changed = false;
        for (State s : from) {
            if (s.floorAdded.remove(item) && !s.reserve.containsKey(item) && !s.wholeProtected.contains(item)) {
                changed |= s.protectedItems.remove(item);
            }
        }
        return changed;
    }

    // the levels from the top of the stack down to and including `level`, which are the ones that have it underneath them
    // (and so inherited a copy of what it protects). empty when it is not in the stack, i.e. it was popped. pure so it can
    // be tested without a game
    static <S> List<S> levelsFrom(Deque<S> stack, S level) {
        List<S> out = new ArrayList<>(4);
        for (S s : stack) {
            out.add(s);
            if (s == level) {
                return out;
            }
        }
        return List.of();
    }

    public void removeProtectedItems(Item... items) {
        boolean changed = false;
        for (Item item : items) {
            changed |= current().protectedItems.remove(item);
            // the numbers go with it, or the next protect of the same item would inherit last task's count
            changed |= current().wholeProtected.remove(item);
            changed |= current().reserve.remove(item) != null;
        }
        if (changed) {
            current().applyState();
        }
    }

    public boolean isProtected(Item item) {
        // For now nothing is protected.
        return current().protectedItems.contains(item);
    }

    public boolean shouldForceFieldPlayers() {
        return current().forceFieldPlayers;
    }

    // a shield up is a fireball punch skipped (KillAura), and the explosion knocks us off a one wide bridge either way
    public boolean noFireballShield() {
        return current().noFireballShield;
    }

    public void setNoFireballShield(boolean on) {
        current().noFireballShield = on;
    }

    public void setForceFieldPlayers(boolean forceFieldPlayers) {
        current().forceFieldPlayers = forceFieldPlayers;
        // Not needed, nothing changes.
        // current.applyState()
    }

    public void allowSwimThroughLava(boolean allow) {
        current().swimThroughLava = allow;
        current().applyState();
    }

    public void setPreferredStairs(boolean allow) {
        //current().preferredStairs = allow;
        current().applyState();
    }

    public void setAllowDiagonalAscend(boolean allow) {
        current().allowDiagonalAscend = allow;
        current().applyState();
    }

    public void setBlockPlacePenalty(double penalty) {
        current().blockPlacePenalty = penalty;
        current().applyState();
    }

    // fast mode caps the placement cost at this one (min of the two), so a level that wants placing to cost more has to
    // raise both or the cap quietly wins
    public void setExperimentalBlockPlacePenalty(double penalty) {
        current().experimentalBlockPlacePenalty = penalty;
        current().applyState();
    }

    // both at once, for the levels that mean "placing is expensive" and do not care which setting says so
    public void setBlockPlacePenalties(double penalty) {
        current().blockPlacePenalty = penalty;
        current().experimentalBlockPlacePenalty = penalty;
        current().applyState();
    }

    public void setBlockBreakAdditionalPenalty(double penalty) {
        current().blockBreakAdditionalPenalty = penalty;
        current().applyState();
    }

    public void avoidDodgingProjectile(Predicate<Entity> whenToDodge) {
        current().avoidDodgingProjectile.add(whenToDodge);
        // Not needed, nothing changes.
        // current().applyState();
    }

    public void addGlobalHeuristic(BiFunction<Double, BlockPos, Double> heuristic) {
        current().globalHeuristics.add(heuristic);
        current().applyState();
    }

    public boolean shouldAvoidDodgingProjectile(Entity entity) {
        if (!current().avoidDodgingProjectile.isEmpty()) {
            for (Predicate<Entity> test : current().avoidDodgingProjectile) {
                if (test.test(entity)) return true;
            }
        }
        return false;
    }

    /// Stack management
    /**
     * Soprano: the bottom state is what pop() writes back into baritone and minecraft, so read it again when a run
     * starts. Otherwise it holds whatever the settings were at startup and a #set in between gets undone.
     */
    public void rebaseline() {
        if (_states.size() == 1) {
            _states.clear();
            push();
        }
    }

    /**
     * Soprano: back to one fresh bottom state, whatever the stack looked like. Called after the runner switched off and
     * the user's settings are back, so the new bottom is read from the real values. A task that threw out of its
     * onStop (or pushed more than it popped) can't leave the stack lopsided for the next run this way.
     */
    public void resetStack() {
        _states.clear();
        // nobody is paused when nobody is running
        _pausedExclusions.clear();
        push();
    }

    // hands back the new level, for a task that wants to keep writing into its own (see reserveProtectedItems). the callers
    // that do not care just ignore it
    public State push() {
        if (_states.isEmpty()) {
            _states.push(new State());
        } else {
            // Make copy and push that
            _states.push(new State(current()));
        }
        return _states.peek();
    }

    public void push(State customState) {
        _states.push(customState);
    }

    public boolean contains(State level) {
        return _states.contains(level);
    }

    // takes a level out from under whatever sits above it, for an owner that can't pop because somebody pushed after it. the
    // ones above copied its numbers already and pop on their own. never the bottom, that one is the user's
    public void remove(State level) {
        if (_states.peekLast() == level || !_states.removeIf(s -> s == level)) {
            return;
        }
        current().applyState();
    }

    // for a task that wants to pop only its own level and never somebody's above it (SchematicBuildTask)
    public boolean isTop(State level) {
        return !_states.isEmpty() && _states.peek() == level;
    }

    public State pop() {
        if (_states.isEmpty()) {
            Debug.logError("State stack is empty. This shouldn't be happening.");
            return null;
        }
        State popped = _states.pop();
        if (_states.isEmpty()) {
            Debug.logError("State stack is empty after pop. This shouldn't be happening.");
            return null;
        }
        // popping down to the bottom state is giving the user's settings back, and that is what the registry's
        // restoreAll does (the runner calls it right after), so the bottom only has to put the extras back
        _states.peek().applyState();
        return popped;
    }

    // what the pathing side gets to see: every level's saving added up, then no lower than the biggest floor, minus any item
    // some level protected without a number (that one is spoken for in full, and absent from the map means exactly that).
    // the floor is max'd in and not added because the task reserves under it are pieces of the same need. pure so it can be
    // tested without a game. sums stop at MAX_VALUE, which is "all of it" anyway
    static <T> Map<T, Integer> sumReserves(Collection<Map<T, Integer>> levels, Collection<Map<T, Integer>> floors, Collection<Set<T>> wholeLevels) {
        Map<T, Integer> out = new HashMap<>();
        for (Map<T, Integer> level : levels) {
            for (Map.Entry<T, Integer> entry : level.entrySet()) {
                out.merge(entry.getKey(), entry.getValue(), (a, b) -> (int) Math.min((long) a + b, Integer.MAX_VALUE));
            }
        }
        for (Map<T, Integer> floor : floors) {
            for (Map.Entry<T, Integer> entry : floor.entrySet()) {
                out.merge(entry.getKey(), entry.getValue(), Math::max);
            }
        }
        for (Set<T> whole : wholeLevels) {
            out.keySet().removeAll(whole);
        }
        return out;
    }

    static <T> Map<T, Integer> sumReserves(Collection<Map<T, Integer>> levels, Collection<Set<T>> wholeLevels) {
        return sumReserves(levels, List.of(), wholeLevels);
    }

    private Map<Item, Integer> effectiveReserve() {
        List<Map<Item, Integer>> levels = new ArrayList<>();
        List<Map<Item, Integer>> floors = new ArrayList<>();
        List<Set<Item>> wholeLevels = new ArrayList<>();
        for (State state : _states) {
            levels.add(state.reserve);
            floors.add(state.floor);
            wholeLevels.add(state.wholeProtected);
        }
        return sumReserves(levels, floors, wholeLevels);
    }

    // every clear() or addAll() on a watched collection throws the pathing snapshot away, so one that already says the
    // right thing gets left alone. lists compare in order, sets don't care about order
    static <T> void sync(Collection<T> target, Collection<T> want) {
        if (target.size() == want.size()) {
            boolean same = true;
            if (target instanceof Set) {
                same = target.containsAll(want);
            } else {
                Iterator<T> a = target.iterator();
                Iterator<T> b = want.iterator();
                while (same && a.hasNext()) {
                    same = Objects.equals(a.next(), b.next());
                }
            }
            if (same) {
                return;
            }
        }
        target.clear();
        target.addAll(want);
    }

    private State current() {
        if (_states.isEmpty()) {
            Debug.logError("STATE EMPTY, UNEMPTIED!");
            push();
        }
        return _states.peek();
    }

    public class State {
        /// Baritone Params
        public double followOffsetDistance;
        // insertion order, no repeats. isProtected is a contains and the pathing side asks a lot
        public Set<Item> protectedItems = new LinkedHashSet<>();
        // this level's own say about protectedItems, which a pushed state inherits whole from the one under it. these two
        // are not inherited (the levels get added up instead, see effectiveReserve): the items this level protected with
        // a count, and the ones it protected without
        public Map<Item, Integer> reserve = new HashMap<>();
        public Set<Item> wholeProtected = new HashSet<>();
        // the least this level keeps spoken for whatever the levels above it reserve (max'd, not added), and the items the
        // floor itself put in protectedItems, so letting go of it can tell them from the ones somebody else asked for
        public Map<Item, Integer> floor = new HashMap<>();
        public Set<Item> floorAdded = new HashSet<>();
        public boolean mineScanDroppedItems;
        public boolean swimThroughLava;
        public boolean allowDiagonalAscend;
        //public boolean preferredStairs;
        public double blockPlacePenalty;
        public double experimentalBlockPlacePenalty;
        public double blockBreakAdditionalPenalty;

        // Alto Clef params
        public boolean exclusivelyMineLogs;
        public boolean forceFieldPlayers;
        public boolean noFireballShield;
        public List<Predicate<Entity>> avoidDodgingProjectile = new ArrayList<>();
        public List<Predicate<Entity>> excludeFromForceField = new ArrayList<>();
        public List<Predicate<Entity>> excludeFromMobDefense = new ArrayList<>();
        public List<Pair<Slot, Predicate<ItemStack>>> conversionSlots = new ArrayList<>();

        // Extra Baritone Settings
        public HashSet<BlockPos> blocksToAvoidBreaking = new HashSet<>();
        public List<Predicate<BlockPos>> toAvoidBreaking = new ArrayList<>();
        public List<Predicate<BlockPos>> toAvoidPlacing = new ArrayList<>();
        public List<Predicate<BlockPos>> allowWalking = new ArrayList<>();
        public List<Predicate<BlockPos>> avoidWalkingThrough = new ArrayList<>();
        public List<BiPredicate<BlockState, ItemStack>> forceUseTools = new ArrayList<>();
        public List<BiFunction<Double, BlockPos, Double>> globalHeuristics = new ArrayList<>();
        public boolean _allowWalkThroughFlowingWater = false;

        // Minecraft config
        public boolean pauseOnLostFocus = true;

        // Hard coded stuff
        public ClipContext.Fluid rayFluidHandling;

        // Other necessary stuff
        public boolean escapeLava = true;

        public State() {
            this(null);
        }

        public State(State toCopy) {
            // Read in current state
            readState(_mod.getClientBaritoneSettings());

            readExtraState(_mod.getExtraBaritoneSettings());

            readMinecraftState();

            if (toCopy != null) {
                // Copy over stuff from old one
                exclusivelyMineLogs = toCopy.exclusivelyMineLogs;
                avoidDodgingProjectile.addAll(toCopy.avoidDodgingProjectile);
                excludeFromForceField.addAll(toCopy.excludeFromForceField);
                excludeFromMobDefense.addAll(toCopy.excludeFromMobDefense);
                conversionSlots.addAll(toCopy.conversionSlots);
                forceFieldPlayers = toCopy.forceFieldPlayers;
                noFireballShield = toCopy.noFireballShield;
                escapeLava = toCopy.escapeLava;
            }
        }

        /**
         * Make the current state match our copy
         */
        public void applyState() {
            applyState(_mod.getClientBaritoneSettings(), _mod.getExtraBaritoneSettings());
        }

        /**
         * Read in a copy of the current state
         */
        private void readState(Settings s) {
            followOffsetDistance = s.followOffsetDistance.value;
            mineScanDroppedItems = s.mineScanDroppedItems.value;
            allowDiagonalAscend = s.allowDiagonalAscend.value;
            blockPlacePenalty = s.blockPlacementPenalty.value;
            experimentalBlockPlacePenalty = s.experimentalBlockPlacementPenalty.value;
            blockBreakAdditionalPenalty = s.blockBreakAdditionalPenalty.value;
            //preferredStairs = s.allowDownward.value;
        }

        private void readExtraState(AltoClefSettings settings) {
            synchronized (settings.getBreakMutex()) {
                synchronized (settings.getPlaceMutex()) {
                    blocksToAvoidBreaking = new HashSet<>(settings.getBlocksToAvoidBreaking());
                    toAvoidBreaking = new ArrayList<>(settings.getBreakAvoiders());
                    toAvoidPlacing = new ArrayList<>(settings.getPlaceAvoiders());
                    protectedItems = new LinkedHashSet<>(settings.getProtectedItems());
                    synchronized (settings.getPropertiesMutex()) {
                        allowWalking = new ArrayList<>(settings.getForceWalkOnPredicates());
                        avoidWalkingThrough = new ArrayList<>(settings.getForceAvoidWalkThroughPredicates());
                        forceUseTools = new ArrayList<>(settings.getForceUseToolPredicates());
                    }
                }
            }
            synchronized (settings.getGlobalHeuristicMutex()) {
                globalHeuristics = new ArrayList<>(settings.getGlobalHeuristics());
            }
            _allowWalkThroughFlowingWater = settings.isFlowingWaterPassAllowed();
            // the lava toggle is ours, it is not baritone's assumeWalkOnLava. reading that one here made a user's
            // `#set assumeWalkOnLava true` turn swimming through lava on for good after the first task
            swimThroughLava = settings.canSwimThroughLava();

            rayFluidHandling = RayTraceUtils.fluidHandling;
        }

        private void readMinecraftState() {
            pauseOnLostFocus = Minecraft.getInstance().options.pauseOnLostFocus;
        }

        /**
         * Make the current state match our copy
         */
        private void applyState(Settings s, AltoClefSettings sa) {
            // the bottom state is the user's own settings. it never writes them (they are already there, or the
            // registry is about to put them back), everything above it goes through the registry so a save can swap
            // the user's values back in
            boolean bottom = _states.peekLast() == this;
            if (!bottom) {
                SettingsOverrides.put(s.followOffsetDistance, followOffsetDistance);
                SettingsOverrides.put(s.mineScanDroppedItems, mineScanDroppedItems);
                SettingsOverrides.put(s.allowDiagonalAscend, allowDiagonalAscend);
                SettingsOverrides.put(s.blockPlacementPenalty, blockPlacePenalty);
                SettingsOverrides.put(s.experimentalBlockPlacementPenalty, experimentalBlockPlacePenalty);
                SettingsOverrides.put(s.blockBreakAdditionalPenalty, blockBreakAdditionalPenalty);
            }

            // We need an alternrative method to handle this, this method makes navigation much less reliable.
            //s.allowDownward.value = preferredStairs;

            // Kinda jank but it works.
            synchronized (sa.getBreakMutex()) {
                synchronized (sa.getPlaceMutex()) {
                    sync(sa.getBreakAvoiders(), toAvoidBreaking);
                    sync(sa.getBlocksToAvoidBreaking(), blocksToAvoidBreaking);
                    sync(sa.getPlaceAvoiders(), toAvoidPlacing);
                    sync(sa.getProtectedItems(), protectedItems);
                    sa.setProtectedReserve(effectiveReserve());
                    synchronized (sa.getPropertiesMutex()) {
                        sync(sa.getForceWalkOnPredicates(), allowWalking);
                        sync(sa.getForceAvoidWalkThroughPredicates(), avoidWalkingThrough);
                        sync(sa.getForceUseToolPredicates(), forceUseTools);
                    }
                }
            }
            synchronized (sa.getGlobalHeuristicMutex()) {
                sync(sa.getGlobalHeuristics(), globalHeuristics);
            }


            sa.setFlowingWaterPass(_allowWalkThroughFlowingWater);
            sa.allowSwimThroughLava(swimThroughLava);

            // Extra / hard coded
            RayTraceUtils.fluidHandling = rayFluidHandling;

            // Minecraft. options.txt must never see ours, see MixinAltoClefOptions
            if (!bottom) {
                Options options = Minecraft.getInstance().options;
                SettingsOverrides.put("options.pauseOnLostFocus", () -> options.pauseOnLostFocus, v -> options.pauseOnLostFocus = v, pauseOnLostFocus);
            }
        }
    }
}
