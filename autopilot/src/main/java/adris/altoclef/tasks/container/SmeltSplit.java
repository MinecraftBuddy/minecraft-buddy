package adris.altoclef.tasks.container;

import net.minecraft.core.BlockPos;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

// a big iron smelt goes in up to three furnaces at once instead of one. 37 ore in one furnace is 370 s of cooking, in three it is
// about 130 s, for 16 more cobble and two more clicks of placing. small batches (the early pick's 3-5, a leftover) stay in one
// furnace, a second furnace for 6 ore is a furnace to pick up for 30 s saved. pure on purpose, CollectIronIngotTask drives the
// loads and holds the decision here so an interrupt (a mob, a collect trip) does not re-split the batch half way through
public final class SmeltSplit {
    // below this it all goes in one furnace
    public static final int MIN_SPLIT = 16;
    // about this many per furnace, so 16-26 is two and 27+ is three
    public static final int PER_LOAD = 13;
    public static final int MAX_LOADS = 3;
    // the ring of 8 (KitPlanner.COBBLE_COST)
    public static final int COBBLE_PER_FURNACE = 8;

    // the batch we decided on, null for none. one at a time: the planner only ever asks for one iron need
    private static volatile Batch current;
    // are we where the smelt happens (the iron phase hands in SmeltSurface.smeltSite). null = nobody says, and then nothing is
    // split: only the phase that took us up knows we are up
    private static volatile BooleanSupplier site;
    // the iron count whose fuel ran out of time with not even two loads covered: it goes the single way, nobody decides it again
    // (the task that decided it is rebuilt on every head change, so this can't live on the task)
    private static volatile int fuelGaveUpFor = -1;

    private SmeltSplit() {
    }

    // how many furnaces this many ingots is worth, before asking what we have
    public static int wanted(int owed) {
        if (owed < MIN_SPLIT) {
            return 1;
        }
        int k = (owed + PER_LOAD - 1) / PER_LOAD;
        return Math.max(2, Math.min(MAX_LOADS, k));
    }

    // how many furnaces this batch actually gets. idle ones of ours (within the walk back) and the ones in the bag are free, every
    // other one is 8 cobble, and only cobble the tools are not owed counts. a short bag means fewer loads, never a cobble trip in
    // the middle of the smelt. the planner asks for none of it: the mine always brings back far more than 16 spare
    public static int loads(int owed, int idleOurs, int inBag, int cobbleSpare) {
        int want = wanted(owed);
        if (want == 1) {
            return 1;
        }
        int have = Math.max(0, idleOurs) + Math.max(0, inBag);
        int craftable = Math.max(0, cobbleSpare) / COBBLE_PER_FURNACE;
        return Math.max(1, Math.min(want, have + craftable));
    }

    // an even split, the odd ones go first: 37 is 13/12/12
    public static int[] sizes(int owed, int k) {
        int n = Math.max(1, k);
        int[] out = new int[n];
        int base = Math.max(0, owed) / n;
        int extra = Math.max(0, owed) % n;
        for (int i = 0; i < n; i++) {
            out[i] = base + (i < extra ? 1 : 0);
        }
        return out;
    }

    // the size of the next load. the last one takes whatever is still owed, so a count that drifted (a visit took output early)
    // never leaves a fourth load. `raw` = raw iron in the bag: a bag that ran short (lava ate some) loads what it has, an empty
    // one is 0 and the batch is over. a load we are coming back to finish (resume) keeps its size, its ore is half in the furnace
    public static int nextLoad(int[] sizes, int issued, int owed, int raw, boolean resume) {
        if (issued >= sizes.length || owed <= 0) {
            return 0;
        }
        int size = issued == sizes.length - 1 ? owed : Math.min(sizes[issued], owed);
        if (resume) {
            return size;
        }
        if (raw <= 0) {
            return 0;
        }
        return Math.min(size, raw);
    }

    // ---- the fuel for the whole batch, fetched before load 1

    // a coal is 8 smelts, and the trip that fetches fuel brings coal (CollectFuelTask)
    public static final int SMELTS_PER_COAL = 8;
    // how long the fetch before load 1 may take (ticks it actually ran), then the split shrinks to what the bag covers
    public static final int FUEL_FETCH_TICKS = 1800;

    // smelts of fuel the loads from `from` on need. a coal does not split between two furnaces, so each load rounds up to whole
    // coal on its own: 13/12/12 is 2+2+2 = 6 coal, not the 5 the total says (same rule as DetourSpec.coalNeed)
    public static int batchFuel(int[] sizes, int from) {
        int smelts = 0;
        for (int i = Math.max(0, from); i < sizes.length; i++) {
            smelts += (Math.max(0, sizes[i]) + SMELTS_PER_COAL - 1) / SMELTS_PER_COAL * SMELTS_PER_COAL;
        }
        return smelts;
    }

    // the most loads, k or fewer, the fuel in the bag covers once re-split evenly. 1 = not even two, it all goes the single way
    public static int loadsFuelCovers(int owed, int k, double fuel) {
        for (int n = Math.max(1, k); n >= 2; n--) {
            if (batchFuel(sizes(owed, n), 0) <= fuel) {
                return n;
            }
        }
        return 1;
    }

    public enum FuelStep {
        // the bag covers every load, start loading
        LOAD,
        // go get the rest before anything goes in
        FETCH,
        // the fetch ran out of time, fewer furnaces (loadsFuelCovers)
        SHRINK
    }

    // before load 1: loading furnace 1 and then finding furnace 2 short is a coal trip in the middle of the smelt with one furnace
    // already cooking. so the bag has to hold the whole batch's fuel first
    public static FuelStep fuelStep(int[] sizes, double bagFuel, int fetchTicks) {
        if (bagFuel >= batchFuel(sizes, 0)) {
            return FuelStep.LOAD;
        }
        return fetchTicks < FUEL_FETCH_TICKS ? FuelStep.FETCH : FuelStep.SHRINK;
    }

    // ---- the held decision

    public static final class Batch {
        // the iron need's count it was decided for, a different count is a different batch
        public final int count;
        private final int[] sizes;
        private int issued;
        // the furnace the load in progress started putting ore in. an interrupt makes a fresh load task, and that one finishes
        // the load here instead of reading the ore it already put in as busy (and making a fourth furnace)
        private BlockPos loadingAt;
        private boolean over;
        // the whole batch's fuel was in the bag before load 1 (fuelStep). load 1 eats into it, so it is asked once
        private boolean fuelReady;
        // ticks the fetch before load 1 actually ran, against FUEL_FETCH_TICKS. on the batch so a rebuilt task keeps the clock
        private int fuelFetchTicks;

        Batch(int count, int[] sizes) {
            this.count = count;
            this.sizes = sizes.clone();
        }

        public boolean splitting() {
            return sizes.length > 1;
        }

        public int loads() {
            return sizes.length;
        }

        public int issued() {
            return issued;
        }

        // loads not handed off yet, the one in progress included
        public int loadsLeft() {
            return over ? 0 : Math.max(0, sizes.length - issued);
        }

        public int[] sizes() {
            return sizes.clone();
        }

        public boolean over() {
            return over || issued >= sizes.length;
        }

        public boolean fuelReady() {
            return fuelReady;
        }

        public void fuelReady(boolean ready) {
            fuelReady = ready;
        }

        public int fuelFetchTicks() {
            return fuelFetchTicks;
        }

        public void fuelFetchTick() {
            fuelFetchTicks++;
        }

        public BlockPos loadingAt() {
            return loadingAt;
        }

        public void loadingAt(BlockPos pos) {
            loadingAt = pos;
        }

        // load `index` (0 based) is in and lit. the load task says it itself, by index, so a second word about the same load (or a
        // late one about an older load) moves nothing
        public void loaded(int index) {
            if (index + 1 > issued) {
                issued = index + 1;
                loadingAt = null;
            }
        }

        // nothing left to load (or no ore left to load it with): whatever is still owed goes the usual way
        public void end() {
            over = true;
            loadingAt = null;
        }

        public int nextLoad(int owed, int raw) {
            return over() ? 0 : SmeltSplit.nextLoad(sizes, issued, owed, raw, loadingAt != null);
        }

        @Override
        public String toString() {
            return Arrays.toString(sizes) + ", " + issued + " loaded" + (over ? ", over" : "");
        }
    }

    // the batch decided for this count that is still loading, null when there is none
    public static Batch held(int count) {
        Batch b = current;
        return b != null && b.count == count && !b.over() ? b : null;
    }

    // the batch still loading, whatever its count, for the planner and the coal (null when none)
    public static Batch active() {
        Batch b = current;
        return b != null && !b.over() ? b : null;
    }

    public static Batch start(int count, int[] sizes) {
        Batch b = new Batch(count, sizes);
        current = b;
        return b;
    }

    // a new run starts with nothing decided (AsyncSmelting.clear)
    public static void clear() {
        current = null;
        site = null;
        fuelGaveUpFor = -1;
    }

    public static void fuelGaveUp(int count) {
        fuelGaveUpFor = count;
    }

    public static boolean fuelGaveUpFor(int count) {
        return fuelGaveUpFor == count;
    }

    // the iron phase wires this in on enter and pulls it on exit
    public static void watchSite(BooleanSupplier s) {
        site = s;
    }

    // the split may be decided now: all the ore is in the bag AND we are at the smelt site, not still in the mine or halfway up
    public static boolean atSite() {
        BooleanSupplier s = site;
        return s != null && s.getAsBoolean();
    }

    // the log line's "13/12/12"
    public static String words(int[] sizes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sizes.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(sizes[i]);
        }
        return sb.toString();
    }
}
