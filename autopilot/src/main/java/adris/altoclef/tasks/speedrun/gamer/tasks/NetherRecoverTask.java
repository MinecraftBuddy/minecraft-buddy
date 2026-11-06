package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.movement.HealUpTask;
import adris.altoclef.tasks.resources.CollectFoodTask;
import adris.altoclef.tasks.resources.SatisfyMiningRequirementTask;
import adris.altoclef.tasks.speedrun.gamer.FoodPickup;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.KitPlanner;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.phases.NetherRegress;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules.Stage;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.CombatRules;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.MobReachability;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

// we died in the nether and woke up at home with an empty bag. the pile is still down there, so: dirt by hand (nothing else
// comes without a pickaxe), the home portal, across the nether, pick it up. the stage lives in RunState.netherTrip so a relog
// carries on and the rules for what comes next are NetherTripRules. when it ends, one way or another, state.netherTrip is
// null and GamerTask hands the wheel back to the phase machine (which rebuilds the kit if the trip did not bring it back)
public class NetherRecoverTask extends Task {
    // at the pile, the pile task gets this long on its own (the walk there is the trip's budget, not this one's)
    private static final double PILE_SECONDS = 90;
    // a shooter this far out with a line on us is one the hold would stand in front of (ghasts shoot from way out, so no tracker: it
    // only lists what is angry within 16)
    private static final double SHOOTER_RANGE = 64;

    private final RunState state;
    private final GamerFacts facts;
    private final Supplier<GamerConfig> cfg;
    private final Consumer<String> say;
    private final Runnable save;
    private final GamerContext ctx;
    // the smoker food waiting at home is collected before the portal (pickUpFood)
    private final FoodPickup foodPickup = new FoodPickup();
    private long lastFoodPickupTick = Long.MIN_VALUE;
    private final HomePortalWalk walk = new HomePortalWalk();
    private Task blocks;
    private Task pick;
    private Task food;
    private final HealUpTask heal = new HealUpTask();
    // the walk stands still to heal (NetherTripRules.walkHold): how long it has in total, when it last did, and if it is doing it now.
    // the time is paid back to the trip budget like a dig, and the no progress clock stands still for it too
    private long heldTicks;
    private long lastHoldTick = Long.MIN_VALUE;
    private boolean holding;
    // how far the portal was when the walk last made real headway, and when. MIN_VALUE = not set for this stage yet
    private double portalMark;
    private long portalMarkTick = Long.MIN_VALUE;
    // last tick the mark was asked about: a fight or a meal that held the wheel is not time the walk spent not getting closer
    private long portalSeenTick;
    private Task toPile;
    // standing on one spot with no path executing (NetherTripRules.WALK_STILL_SECONDS): where we stood, since when, and when it
    // was last asked
    private double stillX;
    private double stillZ;
    private long stillTick = Long.MIN_VALUE;
    private long stillSeen;
    private Task sword;
    // the walk's dig for blocks (same driver the sweep uses). the first nether tick asks for a full bag: it comes through the
    // portal with the 24 the dirt stage got and netherrack is right there, the long walk is where they run out
    private final NetherBlockRestock blockRestock = new NetherBlockRestock();
    private boolean portalRestockAsked;
    // the dig held the wheel this tick, and the last tick it did (the trip budget is paid back for that time)
    private boolean digging;
    private long lastDigTick = Long.MIN_VALUE;
    private RecoverItemsTask pile;
    private Task home;
    // what the bag held at its lowest since the pile task started, and the most it ever climbed from there. the bag shrinks
    // while the pile task runs (dirt placed to reach it, food eaten), so the net change says nothing about what came back
    private int lowItems;
    private int peakItems;
    private int lowStacks;
    private int peakStacks;

    public NetherRecoverTask(RunState state, GamerFacts facts, Supplier<GamerConfig> cfg, Consumer<String> say, Runnable save, GamerContext ctx) {
        this.ctx = ctx;
        this.state = state;
        this.facts = facts;
        this.cfg = cfg;
        this.say = say;
        this.save = save;
    }

    @Override
    protected void onStart(AltoClef mod) {
    }

    @Override
    protected Task onTick(AltoClef mod) {
        try {
            return tickTrip(mod);
        } catch (RuntimeException e) {
            // the phase machine is not ticking while we have the wheel, so nobody else would ever clear a trip that throws
            say.accept(giveUpLine("the trip hit an error (" + e.getClass().getSimpleName() + ")"));
            end(mod, null);
            return null;
        }
    }

    private Task tickTrip(AltoClef mod) {
        RunState.NetherTrip trip = state.netherTrip;
        if (trip == null) {
            return null;
        }
        Stage stage = Stage.parse(trip.stage);
        if (stage == null) {
            end(mod, "the saved trip made no sense");
            return null;
        }
        long now = facts.gameTime();
        // the budget starts here, the pile does not age while the nether is unloaded
        if (trip.netherTick <= 0 && facts.dimension() == Dimension.NETHER) {
            trip.netherTick = now;
            save.run();
        }
        Task cooked = pickUpFood(mod, trip, stage, now);
        if (cooked != null) {
            return cooked;
        }
        // PORTAL asks the walk first: whether the portal is gone is only known once it has had a look this tick
        Task child = childFor(mod, stage);
        NetherTripRules.Step step = NetherTripRules.next(inputs(mod, trip, stage, now));
        if (step.giveUp() == null && !step.recovered() && step.stage() == stage) {
            return child;
        }
        if (step.recovered()) {
            say.accept("recovered " + gained());
            end(mod, null);
            return null;
        }
        if (step.giveUp() != null) {
            say.accept(giveUpLine(step.giveUp()));
        } else if (step.stage() != null) {
            say.accept(entered(step.stage()));
        }
        if (step.stage() == null) {
            if (stage == Stage.HOME && facts.dimension() == Dimension.NETHER) say.accept("walk home ran out of time, still in the nether");
            end(mod, null);
            return null;
        }
        trip.stage = step.stage().name();
        trip.stageTick = now;
        portalMarkTick = Long.MIN_VALUE;
        save.run();
        return childFor(mod, step.stage());
    }

    // the food we loaded in a smoker before the nether is not in the bag, so the meal rule would send us hunting while it sits at
    // home (and the portal stage would walk past it). one visit first (FoodPickup). the pile sits in an unloaded chunk, its
    // despawn clock is stopped, so a short detour costs it nothing, and it is not the stage's time either
    private Task pickUpFood(AltoClef mod, RunState.NetherTrip trip, Stage stage, long now) {
        if (facts.dimension() != Dimension.OVERWORLD || !NetherTripRules.collectsFoodIn(stage)) {
            return null;
        }
        Task next = foodPickup.tick(mod, ctx);
        if (next == null) {
            return null;
        }
        trip.stageTick += NetherTripRules.restockCredit(lastFoodPickupTick, now);
        lastFoodPickupTick = now;
        setDebugState("Collecting the food we cooked first.", "Collecting our food");
        return next;
    }

    private NetherTripRules.Inputs inputs(AltoClef mod, RunState.NetherTrip trip, Stage stage, long now) {
        GamerConfig.Death d = cfg.get().death;
        BlockPos at = new BlockPos(trip.pile.x, trip.pile.y, trip.pile.z);
        double dist = Math.sqrt(mod.getPlayer().blockPosition().distSqr(at));
        boolean homeGone = walk.gone() || state.overworldPortal == null;
        boolean finished = pile != null && pile.isFinished(mod);
        int gained = pile == null ? 0 : trackGain(mod);
        NetherTripRules.Limits limits = NetherTripRules.Limits.of(d.netherTripSeconds, d.netherBlocks, d.netherBlocksSeconds);
        // what WearFromBag would put on right now, so WEAR never waits on a piece it would not try for
        int toWear = stage == Stage.RECOVER || stage == Stage.WEAR ? WearFromBag.held(mod, toEquip()).size() : 0;
        boolean hasPick = StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.STONE);
        return new NetherTripRules.Inputs(stage, facts.dimension(), NetherTripRules.netherTicks(trip.netherTick, now),
                now - trip.stageTick, facts.buildBlocks(), homeGone, dist, finished, gained,
                NetherRegress.kitShort(facts, cfg.get()), toWear, limits, hasPick, stuckTicks(mod, stage, now), facts.foodUnits(),
                mod.getPlayer().getHealth(), mod.getFoodChain().ticksSinceBodyHit(mod), hasWeapon(mod), stillTicks(mod, stage, now), canRegen(mod));
    }

    private static boolean canRegen(AltoClef mod) {
        return CombatRules.canRegen(mod.getPlayer().getFoodData().getFoodLevel(), mod.getFoodChain().hasHealFood());
    }

    // a sword or an axe in hand or in the bag (a pickaxe swings, but the fights down there want a real weapon)
    private static boolean hasWeapon(AltoClef mod) {
        if (isWeapon(StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()))) {
            return true;
        }
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            if (isWeapon(stack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWeapon(ItemStack stack) {
        return ItemHelper.isSword(stack.getItem()) || ItemHelper.isAxe(stack.getItem());
    }

    // ticks stood on one spot in the WALK with nothing executing and no heal hold or dig going on (those are standing on purpose).
    // any real step, a path running, or the walk not being ticked for a while starts it over
    private long stillTicks(AltoClef mod, Stage stage, long now) {
        double x = mod.getPlayer().getX();
        double z = mod.getPlayer().getZ();
        boolean waiting = stage == Stage.WALK && !digging && !holding && !mod.getClientBaritone().getPathingBehavior().isPathing();
        boolean away = NetherTripRules.markStale(stillSeen, now);
        stillSeen = now;
        if (!waiting || away || stillTick == Long.MIN_VALUE || Math.hypot(x - stillX, z - stillZ) >= NetherTripRules.STILL_BLOCKS) {
            stillX = x;
            stillZ = z;
            stillTick = now;
        }
        return waiting ? now - stillTick : 0;
    }

    // ticks since the walk last got 8 blocks closer. PORTAL: straight line to the portal, the horizontal number closes fast
    // and then sits there when the portal is a shaft under our feet. WALK: horizontal to the pile, and a dig for blocks is not
    // a walk that failed to get closer, so the clock pauses for it
    private long stuckTicks(AltoClef mod, Stage stage, long now) {
        RunState.Pos home = state.overworldPortal;
        double dist;
        if (stage == Stage.PORTAL && home != null && facts.dimension() == Dimension.OVERWORLD) {
            dist = Math.sqrt(mod.getPlayer().blockPosition().distSqr(new BlockPos(home.x, home.y, home.z)));
        } else if (stage == Stage.WALK && facts.dimension() == Dimension.NETHER) {
            RunState.Pos pile = state.netherTrip.pile;
            double dx = mod.getPlayer().getX() - pile.x;
            double dz = mod.getPlayer().getZ() - pile.z;
            dist = Math.sqrt(dx * dx + dz * dz);
        } else {
            portalMarkTick = Long.MIN_VALUE;
            return 0;
        }
        if (stage == Stage.WALK && (digging || holding) && portalMarkTick != Long.MIN_VALUE) {
            // the clock stands still while we dig: resetting it instead would let a dig that finds nothing, then its cooldown
            // and another empty dig, keep the mark fresh forever
            portalMarkTick += NetherTripRules.restockCredit(portalSeenTick, now);
            portalSeenTick = now;
            return now - portalMarkTick;
        }
        boolean away = NetherTripRules.markStale(portalSeenTick, now);
        portalSeenTick = now;
        if (portalMarkTick == Long.MIN_VALUE || away || NetherTripRules.closedIn(portalMark, dist)) {
            portalMark = dist;
            portalMarkTick = now;
        }
        return now - portalMarkTick;
    }

    private Task childFor(AltoClef mod, Stage stage) {
        RunState.NetherTrip trip = state.netherTrip;
        BlockPos at = new BlockPos(trip.pile.x, trip.pile.y, trip.pile.z);
        if (stage != Stage.WALK) {
            digging = false;
            holding = false;
        }
        switch (stage) {
            case BLOCKS -> {
                if (blocks == null) {
                    // dirt is the one throwaway block that comes out of the ground without a tool. the count is the gap, the
                    // blocks we already hold (a stray cobble) are part of the total
                    int gap = Math.max(1, cfg.get().death.netherBlocks - facts.buildBlocks());
                    blocks = TaskCatalogue.getItemTask(Items.DIRT, gap + facts.count(Items.DIRT));
                }
                setDebugState("Getting blocks for the walk.", "Getting building blocks");
                return blocks;
            }
            case PICK -> {
                // the pick first, the stone sword is made right after it off the same cobble and table
                if (!StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.STONE)) {
                    if (pick == null) {
                        // the wooden one on the way comes with it, and stone is what the shaft and the netherrack both want
                        pick = new SatisfyMiningRequirementTask(MiningRequirement.STONE);
                    }
                    setDebugState("Getting a pickaxe for the way down.", "Making a pickaxe");
                    return pick;
                }
                if (sword == null) {
                    sword = TaskCatalogue.getItemTask(Items.STONE_SWORD, 1);
                }
                setDebugState("Making a sword for the way down.", "Making a sword");
                return sword;
            }
            case FOOD -> {
                if (food == null) {
                    // junk on top, same as FoodPlan.collect: the task counts rotten flesh and our facts do not
                    food = new CollectFoodTask(NetherTripRules.FOOD_WANTED + facts.junkFoodUnits());
                }
                setDebugState("Getting food for the walk.", "Getting food");
                return food;
            }
            case HEAL -> {
                setDebugState("Healing up before the walk.", "Healing up");
                return heal;
            }
            case PORTAL -> {
                setDebugState("Back to the portal.", "Going back for our stuff");
                return walk.toTheNether(mod, state, save);
            }
            case WALK -> {
                walk.stopTracking(mod);
                if (toPile == null) {
                    toPile = new GetWithinRangeOfBlockTask(at, NetherTripRules.WALK_RANGE);
                }
                if (walkHolds(mod, trip)) {
                    setDebugState("Healing up before the walk goes on.", "Healing up");
                    return heal;
                }
                Task dig = blockRestock.tick(mod, portalRestockAsked ? NetherRestock.LOW : NetherRestock.TARGET,
                        facts.gameTime() / 20.0);
                portalRestockAsked = true;
                if (dig != null) {
                    long t = facts.gameTime();
                    // digging is not walking: the budget does not run for it
                    trip.netherTick = NetherTripRules.paidBack(trip.netherTick, NetherTripRules.restockCredit(lastDigTick, t), t);
                    lastDigTick = t;
                    digging = true;
                    setDebugState("Restocking building blocks.", "Restocking building blocks");
                    return dig;
                }
                if (digging) {
                    digging = false;
                    save.run();
                }
                setDebugState("Walking to where we died.", "Going back for our stuff");
                return toPile;
            }
            case RECOVER -> {
                if (pile == null) {
                    pile = new RecoverItemsTask(at, PILE_SECONDS, this::toEquip);
                    lowItems = itemTotal(mod);
                    lowStacks = stackTotal(mod);
                    peakItems = 0;
                    peakStacks = 0;
                }
                setDebugState("Picking up our stuff.", "Getting our stuff back");
                return pile;
            }
            case WEAR -> {
                // rebuilt every tick from what is in the bag: a list fixed on the way in sent EquipArmorTask off to gather a piece
                // that was thrown or never came back
                setDebugState("Putting our armor back on.", "Putting on armor");
                return WearFromBag.task(mod, toEquip());
            }
            default -> {
                if (home == null) {
                    home = new DefaultGoToDimensionTask(Dimension.OVERWORLD);
                }
                setDebugState("Going home empty handed.", "Heading home");
                return home;
            }
        }
    }

    // hurt on the way, nobody shooting: stand still and let it heal instead of walking up to the pile at half a bar. the standing
    // is not walking, so the budget is paid back for it the way a dig's is
    private boolean walkHolds(AltoClef mod, RunState.NetherTrip trip) {
        long t = facts.gameTime();
        boolean hold = NetherTripRules.walkHold(mod.getPlayer().getHealth(), shooterSees(mod), canRegen(mod), holding, heldTicks);
        if (hold) {
            digging = false;
            long gap = NetherTripRules.restockCredit(lastHoldTick, t);
            trip.netherTick = NetherTripRules.paidBack(trip.netherTick, gap, t);
            heldTicks += gap;
            lastHoldTick = t;
        }
        // (the payback above is only worth anything if a relog sees it)
        if (holding && !hold) {
            save.run();
        }
        holding = hold;
        return hold;
    }

    // anything that shoots, has a line to us and is close enough to matter. a standing target against these is a dead one
    public static boolean shooterSees(AltoClef mod) {
        for (Entity entity : mod.getWorld().entitiesForRendering()) {
            if (!(entity instanceof Mob mob) || !mob.isAlive() || mob.distanceTo(mod.getPlayer()) > SHOOTER_RANGE) {
                continue;
            }
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath();
            boolean shoots = (MobReachability.isRanged(mob) && !type.equals("wither_skeleton")) || type.equals("blaze") || type.equals("ghast");
            if (shoots && mob.hasLineOfSight(mod.getPlayer())) {
                return true;
            }
        }
        return false;
    }

    private List<Item> toEquip() {
        return KitPlanner.toEquip(facts, cfg.get().overworld);
    }

    private String entered(Stage next) {
        return switch (next) {
            case PICK -> "no pickaxe or sword, getting them first";
            case FOOD -> "only " + facts.foodUnits() + " food, getting some first";
            case HEAL -> "healing up before the portal";
            case PORTAL -> facts.buildBlocks() >= cfg.get().death.netherBlocks ? "got " + facts.buildBlocks() + " blocks"
                    : "going with " + facts.buildBlocks() + " blocks";
            case WALK -> "through the portal";
            case RECOVER -> "at the pile";
            case WEAR -> "putting the armor on";
            default -> "heading home";
        };
    }

    private int trackGain(AltoClef mod) {
        int items = itemTotal(mod);
        int stacks = stackTotal(mod);
        lowItems = Math.min(lowItems, items);
        lowStacks = Math.min(lowStacks, stacks);
        peakItems = Math.max(peakItems, items - lowItems);
        peakStacks = Math.max(peakStacks, stacks - lowStacks);
        return peakItems;
    }

    // "rebuilding" only when there is something to rebuild: a pile that gave the kit back and then ran out of time is not
    private String giveUpLine(String why) {
        return "gave up: " + why + (NetherRegress.kitShort(facts, cfg.get()) ? ", rebuilding" : ", carrying on");
    }

    private String gained() {
        int stacks = peakStacks;
        int items = peakItems;
        // a pile that only topped up stacks we already held has no new stack to count
        return stacks > 0 ? stacks + (stacks == 1 ? " stack" : " stacks") : items + (items == 1 ? " item" : " items");
    }

    // trip over, any way: the next tick belongs to the phase machine
    private void end(AltoClef mod, String why) {
        if (why != null) {
            say.accept(giveUpLine(why));
        }
        state.netherTrip = null;
        walk.stopTracking(mod);
        save.run();
    }

    private static int itemTotal(AltoClef mod) {
        int total = 0;
        Inventory inv = mod.getPlayer().getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            total += inv.getItem(i).getCount();
        }
        return total;
    }

    private static int stackTotal(AltoClef mod) {
        int total = 0;
        Inventory inv = mod.getPlayer().getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                total++;
            }
        }
        return total;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // the walk tracks portals while it runs, an interrupt must not leave that scan on for the rest of the run
        walk.stopTracking(mod);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return state.netherTrip == null;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof NetherRecoverTask;
    }

    @Override
    protected String toDebugString() {
        RunState.NetherTrip trip = state.netherTrip;
        return "Nether death trip" + (trip == null ? "" : " (" + trip.stage + ")");
    }

    @Override
    protected String toHudString() {
        RunState.NetherTrip trip = state.netherTrip;
        return trip == null ? "Getting our stuff back" : "Getting our stuff back near " + HudText.pos(new BlockPos(trip.pile.x, trip.pile.y, trip.pile.z));
    }
}
