package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.FuelPolicy;
import baritone.api.utils.Dimension;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

// the gather chops the whole wood budget up front (WoodBudget), and a furnace with logs in the bag burned the batch it just cut.
// the budget works out how many logs and planks are surplus and this tells FuelPolicy to keep the rest. FuelPolicy is the only
// side effect in here, the rest is helpers for the search
public final class WoodReserve {
    private WoodReserve() {
    }

    public record Keep(int logs, int planks) {
    }

    // the engine hands the furnaces the tick's budget (GamerTask), so the planner and the fuel policy read the same numbers
    public static void apply(WoodBudget wood) {
        Keep keep = wood.fuelKeep();
        FuelPolicy.set(keep.logs(), keep.planks(), true);
    }

    // largest k in [0, max] the predicate holds for, given it holds for 0 and only gets harder as k grows
    static int most(int max, IntPredicate ok) {
        int lo = 0;
        int hi = max;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (ok.test(mid)) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    // the same facts with k fewer of a wood group, taken from the species in list order
    static GamerFacts without(GamerFacts base, Item[] group, int k) {
        Map<Item, Integer> reduced = new HashMap<>();
        int left = k;
        for (Item item : group) {
            int have = base.count(item);
            int take = Math.min(have, left);
            left -= take;
            reduced.put(item, have - take);
        }
        return new Reduced(base, reduced);
    }

    private record Reduced(GamerFacts base, Map<Item, Integer> counts) implements GamerFacts {
        @Override
        public Dimension dimension() {
            return base.dimension();
        }

        @Override
        public int count(Item item) {
            Integer c = counts.get(item);
            return c != null ? c : base.count(item);
        }

        @Override
        public int spent(Item item) {
            return base.spent(item);
        }

        @Override
        public boolean armorEquipped(Item item) {
            return base.armorEquipped(item);
        }

        @Override
        public int armorPoints() {
            return base.armorPoints();
        }

        @Override
        public int foodUnits() {
            return base.foodUnits();
        }

        @Override
        public int junkFoodUnits() {
            return base.junkFoodUnits();
        }

        @Override
        public int buildBlocks() {
            return base.buildBlocks();
        }

        @Override
        public int x() {
            return base.x();
        }

        @Override
        public int y() {
            return base.y();
        }

        @Override
        public int z() {
            return base.z();
        }

        @Override
        public long gameTime() {
            return base.gameTime();
        }

        @Override
        public List<RunState.FurnaceJob> furnaceJobs() {
            return base.furnaceJobs();
        }

        @Override
        public int pendingOutput(Item item) {
            return base.pendingOutput(item);
        }

        @Override
        public StationVerdict stations() {
            return base.stations();
        }

        @Override
        public boolean tablePlacedNearby() {
            return base.tablePlacedNearby();
        }

        @Override
        public boolean furnacePlacedNearby() {
            return base.furnacePlacedNearby();
        }

        @Override
        public boolean smokerPlacedNearby() {
            return base.smokerPlacedNearby();
        }

        @Override
        public boolean creditsShown() {
            return base.creditsShown();
        }

        @Override
        public int inventoryFingerprint() {
            return base.inventoryFingerprint();
        }

        @Override
        public java.util.Map<net.minecraft.world.item.Item, Integer> inventoryCounts() {
            return base.inventoryCounts();
        }
    }
}
