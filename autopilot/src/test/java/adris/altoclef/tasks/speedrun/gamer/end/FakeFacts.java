package adris.altoclef.tasks.speedrun.gamer.end;

import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import baritone.api.utils.Dimension;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// a player in a map. Items need Bootstrap, the tests that use this call it in @BeforeClass
class FakeFacts implements GamerFacts {
    Dimension dimension = Dimension.OVERWORLD;
    final Map<Item, Integer> items = new HashMap<>();
    int bedsOutOfBag;
    final Set<Item> worn = new HashSet<>();
    int armorPoints;
    int food = 100;
    int blocks = 64;
    int x;
    int z;
    long gameTime = 100_000;
    boolean credits;
    // a smoker of ours standing next to us, and the batches cooking in stations of ours
    boolean smokerPlaced;
    final List<RunState.FurnaceJob> jobs = new ArrayList<>();

    FakeFacts with(Item item, int count) {
        items.put(item, count);
        return this;
    }

    // meat in a smoker, `count` items worth `unitsEach` nutrition apiece once done
    FakeFacts cookingFood(String output, int count, int unitsEach) {
        RunState.FurnaceJob job = new RunState.FurnaceJob(new RunState.Pos(5, 64, 0), dimension.name(), "smoker", "porkchop", count,
                output, gameTime, gameTime + 400);
        job.unitsEach = unitsEach;
        jobs.add(job);
        return this;
    }

    @Override
    public List<RunState.FurnaceJob> furnaceJobs() {
        return jobs;
    }

    @Override
    public boolean smokerPlacedNearby() {
        return smokerPlaced;
    }

    // worn pieces count as owned, like the real facts do
    FakeFacts wear(Item item) {
        worn.add(item);
        items.merge(item, 1, Integer::sum);
        return this;
    }

    @Override
    public Dimension dimension() {
        return dimension;
    }

    @Override
    public int count(Item item) {
        return items.getOrDefault(item, 0);
    }

    @Override
    public boolean armorEquipped(Item item) {
        return worn.contains(item);
    }

    @Override
    public int armorPoints() {
        return armorPoints;
    }

    @Override
    public int foodUnits() {
        return food;
    }

    @Override
    public int buildBlocks() {
        return blocks;
    }

    @Override
    public int x() {
        return x;
    }

    @Override
    public int y() {
        return 64;
    }

    @Override
    public int z() {
        return z;
    }

    @Override
    public int bedsInBag() {
        return count(adris.altoclef.util.helpers.ItemHelper.BED) - bedsOutOfBag;
    }

    @Override
    public long gameTime() {
        return gameTime;
    }

    @Override
    public boolean creditsShown() {
        return credits;
    }
}
