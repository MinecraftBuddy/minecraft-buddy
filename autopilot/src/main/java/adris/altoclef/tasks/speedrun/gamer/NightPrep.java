package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.speedrun.gamer.NightSleep.Prep;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.tasks.BedCraft;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.DyeColor;

// the evening before a night: a bed in the bag by the time the window opens, so the trip has something to put down. the rules
// are NightSleep.prep, this is the part that looks at the game and runs the wool trip or the craft. it is ordinary work, not a
// trip: mob defense is on for all of it and each part has its own clock for the evening, after which it quietly stops
public final class NightPrep {
    private static final double TICK_SECONDS = 0.05;
    private static final int COLOURS = DyeColor.values().length;

    // what the engine knows that this does not. busy is everything NightSleep.busy minds apart from the phase
    public record Gate(boolean phaseOk, boolean busy, boolean fighting, boolean lowHealth, boolean nightSpent, boolean windowOpen) {
    }

    private final Supplier<OverworldConfig> cfg;
    private final NightSleep.DayClock woolClock = new NightSleep.DayClock();
    private final NightSleep.DayClock craftClock = new NightSleep.DayClock();
    private final BedCraft bedCraft = new BedCraft();
    private final NightSleep.ReadyLatch readyLatch = new NightSleep.ReadyLatch();
    private final NightSleep.SheepHold sheepHold = new NightSleep.SheepHold();

    private Prep running;
    private Prep last = Prep.TOO_EARLY;
    private int colour = -1;
    private Task woolTask;
    private long saidNoSheep = Long.MIN_VALUE;

    public NightPrep(Supplier<OverworldConfig> cfg) {
        this.cfg = cfg;
    }

    // what the last tick decided, for the one log line GamerTask keeps
    public Prep last() {
        return last;
    }

    // whether the last tick handed back a task, so the engine knows to give the phase its clocks back when it stops
    public boolean running() {
        return running != null;
    }

    // a new player or another dimension: whatever was half done is not ours any more
    public void drop() {
        running = null;
        woolTask = null;
        colour = -1;
        readyLatch.reset();
        sheepHold.reset();
    }

    // the task to run instead of the phase this tick, or null
    public Task tick(AltoClef mod, Gate gate, BooleanSupplier bedReady) {
        OverworldConfig c = cfg.get();
        long dayTime = mod.getWorld().getOverworldClockTime();
        long timeOfDay = Math.floorMod(dayTime, NightSleep.DAY_LENGTH);
        long dayId = NightSleep.nightOf(dayTime);
        woolClock.roll(dayId);
        craftClock.roll(dayId);
        long gameTime = mod.getWorld().getGameTime();
        long craftLead = c.nightCraftLeadTicks;
        long woolLead = c.nightWoolLeadTicks;
        boolean early = timeOfDay < NightSleep.EARLIEST_WINDOW - Math.max(craftLead, woolLead);
        if (early || gate.windowOpen() || timeOfDay >= NightSleep.PREP_END) {
            // (most of the day and all of the night: nothing to look at)
            last = early ? Prep.TOO_EARLY : Prep.TOO_LATE;
            stopped(last);
            return null;
        }
        int wool = BedCraft.bestWoolStack(mod);
        // (the expensive questions wait for the evening: sheep, a bed standing close, the table)
        // (latched: this check flipped yes and no every few steps, and every flip swapped the craft and the phase)
        boolean ready = readyLatch.see(bedReady.getAsBoolean(), gameTime, dayId);
        boolean sheep = false;
        if (!ready && wool < 3 && timeOfDay >= NightSleep.EARLIEST_WINDOW - woolLead) {
            // (a hunt in progress stays a hunt, see wool(); it ends on 3 wool, the clock or the task, not on a sheep drifting out)
            sheep = sheepHold.see(running == Prep.WOOL && woolTask != null, () -> pickColour(mod, c) >= 0, gameTime);
        }
        NightSleep.PrepInputs in = new NightSleep.PrepInputs(true, true, gate.phaseOk(), gate.busy(), gate.fighting(), gate.lowHealth(),
                gate.nightSpent(), false, timeOfDay, ready, wool, BedCraft.wood(mod),
                NightSleep.prepWoodNeeded(ready, running == Prep.WOOL && woolTask != null, () -> BedCraft.woodNeeded(mod)),
                sheep, woolClock.over(c.nightWoolSeconds), craftClock.over(c.nightCraftSeconds));
        Prep p = NightSleep.prep(in, craftLead, woolLead);
        last = p;
        if (p == Prep.NO_SHEEP && saidNoSheep != dayId) {
            saidNoSheep = dayId;
            sayNoSheep(mod);
        }
        Task task = switch (p) {
            case WOOL -> wool(mod, c);
            case CRAFT -> craft(mod, c);
            default -> null;
        };
        // (a craft or a hunt that hands back nothing for a tick is not over, the next tick's answer says so: GAVE_UP and the like)
        if (task == null && p != Prep.CRAFT && p != Prep.WOOL) {
            stopped(p);
        }
        return task;
    }

    private void stopped(Prep why) {
        if (running != null) {
            Debug.logInternal("night prep: " + running + " stopped (" + why + ")");
            if (running == Prep.WOOL) {
                woolTask = null;
                colour = -1;
            }
            running = null;
        }
    }

    private void started(Prep what, String line) {
        if (running != what) {
            Debug.logInternal("night prep: " + what + ", " + line);
            running = what;
        }
    }

    // ---- wool

    // the colour to hunt, kept while it still works so a sheep walking out of range does not swap the task under us
    private int pickColour(AltoClef mod, OverworldConfig c) {
        int[] held = new int[COLOURS];
        int[] near = new int[COLOURS];
        for (DyeColor d : DyeColor.values()) {
            held[d.ordinal()] = mod.getItemStorage().getItemCount(ItemHelper.getColorfulItems(d).wool);
        }
        for (Sheep s : mod.getEntityTracker().getTrackedEntities(Sheep.class)) {
            if (s.isAlive() && !s.isSheared() && !s.isBaby() && mod.getPlayer().distanceTo(s) <= c.nightWoolRadius) {
                near[s.getColor().ordinal()]++;
            }
        }
        if (colour >= 0 && held[colour] + near[colour] >= 3) {
            return colour;
        }
        return NightSleep.woolColour(held, near);
    }

    // once an evening: how far off the sheep really were, so the next games can tell if the wall was the radius or the three of a colour
    private void sayNoSheep(AltoClef mod) {
        int[] within40 = new int[COLOURS];
        int[] within128 = new int[COLOURS];
        double nearest = Double.MAX_VALUE;
        for (Sheep s : mod.getEntityTracker().getTrackedEntities(Sheep.class)) {
            if (!s.isAlive() || s.isBaby()) {
                continue;
            }
            double d = mod.getPlayer().distanceTo(s);
            nearest = Math.min(nearest, d);
            // (a sheared one is no wool, all it gets to say is how far the nearest sheep was)
            if (!s.isSheared() && d <= 128) {
                within128[s.getColor().ordinal()]++;
                if (d <= 40) {
                    within40[s.getColor().ordinal()]++;
                }
            }
        }
        Debug.logInternal("night prep: NO_SHEEP, nearest adult sheep " + (nearest == Double.MAX_VALUE ? "none" : (int) nearest + " blocks")
                + ", best colour " + mostOf(within40) + " within 40, " + mostOf(within128) + " within 128");
    }

    private static int mostOf(int[] counts) {
        int most = 0;
        for (int n : counts) {
            most = Math.max(most, n);
        }
        return most;
    }

    private Task wool(AltoClef mod, OverworldConfig c) {
        // (the colour of a hunt that is running is not picked again either: the sheep it was started for may be past the radius now)
        int pick = running == Prep.WOOL && woolTask != null && colour >= 0 ? colour : pickColour(mod, c);
        if (pick < 0) {
            return null;
        }
        woolClock.spend(TICK_SECONDS);
        if (woolClock.over(c.nightWoolSeconds)) {
            Debug.logInternal("night prep: giving up on the wool for tonight, " + (int) woolClock.seconds() + " s is all it gets");
            woolClock.giveUp();
            return null;
        }
        ItemHelper.ColorfulItems items = ItemHelper.getColorfulItems(DyeColor.values()[pick]);
        if (pick != colour || woolTask == null) {
            colour = pick;
            woolTask = TaskCatalogue.getItemTask(items.colorName + "_wool", 3);
        }
        started(Prep.WOOL, "hunting " + items.colorName + " wool, " + (int) c.nightWoolSeconds + " s at most");
        return woolTask;
    }

    // ---- the bed

    private Task craft(AltoClef mod, OverworldConfig c) {
        craftClock.spend(TICK_SECONDS);
        if (craftClock.over(c.nightCraftSeconds)) {
            Debug.logInternal("night prep: giving up on crafting the bed for tonight");
            craftClock.giveUp();
            return null;
        }
        started(Prep.CRAFT, "making a bed ahead of dusk");
        // wood in logs first: the bed craft does not turn logs into planks (and the table it may have to make eats four)
        Task planks = bedCraft.planksFirst(mod);
        if (planks != null) {
            return planks;
        }
        Task make = bedCraft.next(mod);
        if (make != null) {
            return make;
        }
        return BedCraft.stray(mod);
    }
}
