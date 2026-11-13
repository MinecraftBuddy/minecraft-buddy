package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import baritone.Baritone;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.container.AsyncSmelting;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.container.SmeltInSmokerTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.helpers.CropRules;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StationHook;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.SmokerSlot;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.fish.Cod;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.fish.Salmon;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

public class CollectFoodTask extends Task {

    // Represents order of preferred mobs to least preferred
    // (the order is only for the smelting and pickup loops now, FoodHunt decides who gets chased). fish are here so a
    // dropped one still gets picked up and cooked, but they have no hunt kind: nobody chases a cod
    private static final CookableFoodTarget[] COOKABLE_FOODS = new CookableFoodTarget[]{
            new CookableFoodTarget("porkchop", Pig.class, FoodHunt.Kind.PIG),
            new CookableFoodTarget("beef", Cow.class, FoodHunt.Kind.COW),
            new CookableFoodTarget("chicken", Chicken.class, FoodHunt.Kind.CHICKEN),
            new CookableFoodTarget("mutton", Sheep.class, FoodHunt.Kind.SHEEP),
            new CookableFoodTarget("rabbit", Rabbit.class, FoodHunt.Kind.RABBIT),
            new CookableFoodTargetFish("cod", Cod.class),
            new CookableFoodTargetFish("salmon", Salmon.class)
    };

    private static final Item[] ITEMS_TO_PICK_UP = new Item[]{
            Items.ENCHANTED_GOLDEN_APPLE,
            Items.GOLDEN_APPLE,
            Items.GOLDEN_CARROT,
            Items.BREAD,
            Items.BAKED_POTATO
    };

    private static final CropTarget[] CROPS = new CropTarget[]{
            // unit is the food one item is worth, for how many to ask for. wheat is bread, 5 for 3 wheat
            new CropTarget(Items.WHEAT, Blocks.WHEAT, Items.WHEAT_SEEDS, 1.6),
            new CropTarget(Items.CARROT, Blocks.CARROTS, Items.CARROT, 3),
            // the buddy's fork: a village's other fields. a potato is baked (5), a beetroot eaten as it is
            new CropTarget(Items.POTATO, Blocks.POTATOES, Items.POTATO, 5),
            new CropTarget(Items.BEETROOT, Blocks.BEETROOTS, Items.BEETROOT_SEEDS, 1)
    };

    // every hoe, so whichever one we end up holding is protected and counts as "have one"
    private static final Item[] HOES = new Item[]{
            Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE, Items.GOLDEN_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE
    };

    // how many ticks any of these has run, ever. the gamer's food count compares it between two looks (FoodPlan.leftover): a smoker
    // screen open while the food task is the one running is ours, and what was already in it must not satisfy the need that walked
    // up to it. a counter and not the world clock (the server's time packets yank that around), and not started minus stopped
    // either: a finished main task that the idle chain drops is never stopped, that count would stay up for good
    private static volatile long ticks;

    public static long ticks() {
        return ticks;
    }

    // the last time the task fell through to "Searching..." (wall clock, the ticks above stand still while another task runs).
    // the gamer reads it to tell a dry spell from a hunt that is just slow (FoodDry)
    private static volatile long lastSearchMs = 0;
    private static final long SEARCH_FRESH_MS = 3000;

    // the last time a kill's loot was still on its way (lootComing). the gamer keeps food at the front while this is fresh, or the
    // head would switch to the starter with the meat on the ground (same wall clock idea as searching)
    private static volatile long lastLootMs = 0;

    public static boolean lootPending() {
        return System.currentTimeMillis() - lastLootMs < SEARCH_FRESH_MS;
    }

    // read only: true while the task is out wandering for food with no animal, crop or hay to go after
    public static boolean searching() {
        return System.currentTimeMillis() - lastSearchMs < SEARCH_FRESH_MS;
    }

    private final double _unitsNeeded;
    private final TimerGame _checkNewOptionsTimer = new TimerGame(10);
    private adris.altoclef.tasks.ResourceTask _smeltTask = null;
    private Task _currentResourceTask = null;

    // the animal we are after, kept so the next pick can stay on it (see FoodHunt.STICKY_FACTOR). _huntTask is the task
    // made for it, to tell "the hunt lost its animal" apart from every other cached task
    private Entity _hunted = null;
    private CookableFoodTarget _huntedFood = null;
    private Task _huntTask = null;

    // the hay sweep: null timer = not started, over = never again for this task. the sweep task is remembered so the
    // cached resource task can be dropped the moment the pile is gone instead of up to 10 s later
    private TimerGame _sweepTimer = null;
    private boolean _sweepOver = false;
    private Task _sweepTask = null;
    private int _hayLeft = 0;
    private final TimerGame _hoeCheckTimer = new TimerGame(HaySweep.HOE_CHECK_SECONDS);
    private Task _hoeTask = null;
    private TimerGame _hoeTimer = null;
    private boolean _hoeDone = false;

    // the buddy's fork: food on the way (see nearby)
    private final boolean _nearbyOnly;
    private boolean _nearbyDone = false;

    public CollectFoodTask(double unitsNeeded) {
        this(unitsNeeded, false);
    }

    private CollectFoodTask(double unitsNeeded, boolean nearbyOnly) {
        _unitsNeeded = unitsNeeded;
        _nearbyOnly = nearbyOnly;
    }

    /**
     * The buddy's fork: food found close by is taken along, more than is needed. Only the animals and melons within
     * EXTRA_RANGE, until the bag holds cap points of food, then done. No hay, no fields, no cooking and
     * no walk to new ground, so it is a detour of a few steps in the middle of other work.
     */
    public static CollectFoodTask nearby(double cap) {
        return new CollectFoodTask(cap, true);
    }

    /**
     * For the buddy, whether nearby would take anything: an animal that is food within EXTRA_RANGE, at most four blocks
     * higher or lower and in sight (not one on the surface over a cave), while the bag holds less than cap points of food
     * and has an empty slot.
     */
    public static boolean foodNearby(AltoClef mod, double cap) {
        if (mod.getPlayer() == null || !mod.getItemStorage().hasEmptyInventorySlot() || calculateFoodPotential(mod) >= cap) {
            return false;
        }
        Vec3 me = mod.getPlayer().position();
        for (CookableFoodTarget c : COOKABLE_FOODS) {
            if (c.kind == null) continue;
            Optional<Entity> e = mod.getEntityTracker().getClosestEntity(CollectFoodTask::edible, c.mobToKill);
            if (e.isEmpty() || e.get().position().distanceToSqr(me) >= EXTRA_RANGE * EXTRA_RANGE) continue;
            if (Math.abs(e.get().getY() - me.y) > 4 || !mod.getPlayer().hasLineOfSight(e.get())) continue;
            if (mod.getEntityTracker().isEntityReachable(e.get())) return true;
        }
        return false;
    }

    private static double getFoodPotential(ItemStack food) {
        if (food == null) return 0;
        int count = food.getCount();
        if (count <= 0) return 0;
        for (CookableFoodTarget cookable : COOKABLE_FOODS) {
            if (food.getItem() == cookable.getRaw()) {
                assert cookable.getCooked().components().get(DataComponents.FOOD) != null;
                return count * Objects.requireNonNull(cookable.getCooked().components().get(DataComponents.FOOD)).nutrition();
            }
        }
        // We're just an ordinary item.
        if (food.getItem().components().has(DataComponents.FOOD)) {
            assert food.getItem().components().get(DataComponents.FOOD) != null;
            return count * Objects.requireNonNull(food.getItem().components().get(DataComponents.FOOD)).nutrition();
        }
        return 0;
    }

    // Gets the units of food if we were to convert all of our raw resources to food.
    @SuppressWarnings("RedundantCast")
    // the buddy's fork: the bag's food as this task counts it (raw meat at what it is cooked), for the take-along's food wish
    public static double foodPotential(AltoClef mod) {
        return calculateFoodPotential(mod);
    }

    private static double calculateFoodPotential(AltoClef mod) {
        double potentialFood = 0;
        for (ItemStack food : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            potentialFood += getFoodPotential(food);
        }
        int potentialBread = (int) (mod.getItemStorage().getItemCount(Items.WHEAT) / 3) + mod.getItemStorage().getItemCount(Items.HAY_BLOCK) * 3;
        potentialFood += Objects.requireNonNull(Items.BREAD.components().get(DataComponents.FOOD)).nutrition() * potentialBread;
        // Check smelting
        AbstractContainerMenu screen = mod.getPlayer().containerMenu;
        if (screen instanceof SmokerMenu) {
            potentialFood += getFoodPotential(StorageHelper.getItemStackInSlot(SmokerSlot.INPUT_SLOT_MATERIALS));
            potentialFood += getFoodPotential(StorageHelper.getItemStackInSlot(SmokerSlot.OUTPUT_SLOT));
        }
        // meat cooking in a smoker we walked away from (async cooking, only the gamer ever has any). it is food on the way,
        // not food we have: foodUnits() stays honest and this only stops the hunt from doubling up
        potentialFood += AsyncSmelting.pendingFoodUnits();
        // the buddy's fork: a raw potato is a baked one to come (5 where it counted 1), kelp a dried one (1)
        potentialFood += 4 * mod.getItemStorage().getItemCount(Items.POTATO) + mod.getItemStorage().getItemCount(Items.KELP);
        return potentialFood;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBehaviour().push();
        // Protect ALL food
        mod.getBehaviour().addProtectedItems(ITEMS_TO_PICK_UP);
        for (CropTarget crop : CROPS) {
            mod.getBlockTracker().trackBlock(crop.cropBlock);
        }

        // Allow us to consume food.
        /*
        for (CookableFoodTarget food : COOKABLE_FOODS)
            mod.getBehaviour().addProtectedItems(food.getRaw(), food.getCooked());
            mod.getBehaviour().addProtectedItems(crop.cropItem);
        }
         */
        mod.getBehaviour().addProtectedItems(Items.HAY_BLOCK, Items.SWEET_BERRIES);
        // altoThrowAwayUnusedItems would happily toss a hoe the second the bag gets full. this frame pops on stop so
        // it is fair game again once the food is done
        mod.getBehaviour().addProtectedItems(HOES);
        // a restart is a new pile, new budget
        _sweepTimer = null;
        _sweepOver = false;
        _sweepTask = null;
        _hoeTask = null;
        _hoeTimer = null;
        _hoeDone = false;
        _hunted = null;
        _huntedFood = null;
        _huntTask = null;

        mod.getBlockTracker().trackBlock(Blocks.HAY_BLOCK);
        mod.getBlockTracker().trackBlock(Blocks.SWEET_BERRY_BUSH);
        // the buddy's fork: a dark forest's huge mushrooms are stew, a jungle's melons are slices
        mod.getBlockTracker().trackBlock(MUSHROOMS_RED);
        mod.getBlockTracker().trackBlock(MUSHROOMS_BROWN);
        mod.getBlockTracker().trackBlock(Blocks.MELON);
        mod.getBlockTracker().trackBlock(GLOW_BERRY_VINES);
        mod.getBlockTracker().trackBlock(KELP);
        _waterPickupWas = null;
        _extraSince = -1;
    }

    private static final Block[] GLOW_BERRY_VINES = {Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT};
    private static final Block[] KELP = {Blocks.KELP, Blocks.KELP_PLANT};
    // kelp falls in water: picking items up out of water is allowed while it is gathered, and put back after
    private Boolean _waterPickupWas;
    // the buddy's fork: the way to land from open water (LandFinder)
    private final adris.altoclef.util.helpers.LandFinder.Steer _toLand = new adris.altoclef.util.helpers.LandFinder.Steer();

    private static final Block[] MUSHROOMS_RED = {Blocks.RED_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM};
    private static final Block[] MUSHROOMS_BROWN = {Blocks.BROWN_MUSHROOM_BLOCK, Blocks.BROWN_MUSHROOM};

    // a red and a brown mushroom (a huge mushroom's block drops them) within this, both kinds: stew, six points a bowl
    private static final double STEW_RANGE = 64;

    private static boolean berries(AltoClef mod, BlockPos pos) {
        var st = mod.getWorld().getBlockState(pos);
        return st.hasProperty(net.minecraft.world.level.block.CaveVines.BERRIES) && st.getValue(net.minecraft.world.level.block.CaveVines.BERRIES);
    }

    private static boolean near(AltoClef mod, Block[] blocks, double range) {
        Optional<BlockPos> at = mod.getBlockTracker().getNearestTracking(blocks);
        return at.isPresent() && at.get().closerToCenterThan(mod.getPlayer().position(), range);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        ticks++;
        if (mod.getEntityTracker().entityFound(Chicken.class)) {
            Optional<Entity> chickens = mod.getEntityTracker().getClosestEntity(Chicken.class);
            if (chickens.isPresent()) {
                Iterable<Entity> entities = mod.getWorld().entitiesForRendering();
                for (Entity entity : entities) {
                    if (entity instanceof Monster || entity instanceof Slime) {
                        if (chickens.get().hasPassenger(entity)) {
                            if (mod.getEntityTracker().isEntityReachable(entity)) {
                                Debug.logMessage("Blacklisting chicken jockey.");
                                mod.getEntityTracker().requestEntityUnreachable(chickens.get());
                            }
                        }
                    }
                }
            }
        }
        if (mod.getBlockTracker().isTracking(Blocks.HAY_BLOCK)) {
            Optional<BlockPos> hay = mod.getBlockTracker().getNearestTracking(Blocks.HAY_BLOCK);
            if (hay.isPresent()) {
                if (isOutpostHay(mod, hay.get())) {
                    Debug.logMessage("Blacklisting pillage hay bales.");
                    mod.getBlockTracker().requestBlockUnreachable(hay.get(), 0);
                }
            }
        }
        // the buddy's fork: food on the way takes what is within a few steps and nothing else (see nearby)
        if (_nearbyOnly) {
            if (_currentResourceTask != null && _currentResourceTask.isActive() && !_currentResourceTask.isFinished(mod)
                    && !_currentResourceTask.thisOrChildAreTimedOut()) {
                return _currentResourceTask;
            }
            Task extra = null;
            if (cheapExtra(mod)) {
                extra = nearbyPreyOrNull(mod, EXTRA_RANGE);
                if (extra == null) extra = pickupBlockTaskOrNull(mod, Blocks.MELON, Items.MELON_SLICE, EXTRA_RANGE);
            }
            _currentResourceTask = extra;
            if (extra == null) {
                _nearbyDone = true;
                return null;
            }
            setDebugState("Taking food on the way");
            return extra;
        }
        // If we were previously smelting, keep on smelting.
        if (_smeltTask != null && _smeltTask.isActive() && !_smeltTask.isFinished(mod)) {
            // TODO: If we don't have cooking materials, cancel.
            setDebugState("Cooking...");
            return _smeltTask;
        }

        // the hoe is made once, nothing else gets a say until it is done (or fed up)
        if (_hoeTask != null) {
            if (_hoeTask.isActive() && !_hoeTask.isFinished(mod) && !_hoeTask.thisOrChildAreTimedOut() && !_hoeTimer.elapsed()) {
                setDebugState("Making a hoe for the hay");
                return _hoeTask;
            }
            _hoeTask = null;
            // crafting time is not pile time
            if (_sweepTimer != null) {
                _sweepTimer.reset();
            }
        }

        if (_checkNewOptionsTimer.elapsed()) {
            // Try a new resource task
            _checkNewOptionsTimer.reset();
            // the timer used to wipe a half dead pig off the list the moment a hay bale came into view
            // same for a field in progress: a fresh CollectCropTask has a new wheat count, loses its list of holes, and nothing
            // it broke before the swap ever gets replanted
            boolean fieldInProgress = _currentResourceTask instanceof CollectCropTask crop && crop.isActive() && crop.hasReplantWork();
            if (!fieldInProgress && !(_huntTask != null && _currentResourceTask == _huntTask && (huntCommitted(mod) || lootComing(mod)))) {
                _currentResourceTask = null;
            }
        }

        // the sweep ends when the pile does, not on the next 10 s check
        if (_sweepTask != null && _currentResourceTask == _sweepTask && !sweepWanted(mod)) {
            _currentResourceTask = null;
        }

        // a hunt whose animal died or got blacklisted has nothing to chase, no point idling until the 10 s check
        if (_huntTask != null && _currentResourceTask == _huntTask && !huntStillOn(mod) && !lootComing(mod)) {
            _currentResourceTask = null;
        }

        if (_currentResourceTask != null && _currentResourceTask.isActive() && !_currentResourceTask.isFinished(mod) && !_currentResourceTask.thisOrChildAreTimedOut()) {
            if (_currentResourceTask == _sweepTask) {
                setDebugState("Sweeping the hay pile (" + _hayLeft + " left)");
            }
            return _currentResourceTask;
        }

        // Calculate potential
        double potentialFood = calculateFoodPotential(mod);
        if (potentialFood >= _unitsNeeded) {
            // we could stop here, but the rest of the pile is one step away and the next bale costs a few ticks
            Task sweep = sweepHayOrNull(mod);
            if (sweep != null) {
                return sweep;
            }
            // the buddy's fork: and an animal or a melon a few steps away too, past the need (see cheapExtra)
            if (cheapExtra(mod)) {
                Task extra = nearbyPreyOrNull(mod, EXTRA_RANGE);
                if (extra == null) extra = pickupBlockTaskOrNull(mod, Blocks.MELON, Items.MELON_SLICE, EXTRA_RANGE);
                if (extra != null) {
                    setDebugState("Taking cheap extra food");
                    return extra;
                }
            }
            // Convert our raw foods
            // PLAN:
            // - If we have hay/wheat, make it into bread
            // - If we have raw foods, smelt all of them

            // Convert Hay+Wheat -> Bread
            if (mod.getItemStorage().getItemCount(Items.WHEAT) >= 3) {
                setDebugState("Crafting Bread");
                Item[] w = new Item[]{Items.WHEAT};
                Item[] o = null;
                // jank
                _currentResourceTask = new CraftInTableTask(new RecipeTarget(Items.BREAD, 99999999, CraftingRecipe.newShapedRecipe("bread", new Item[][]{w, w, w, o, o, o, o, o, o}, 1)), false, false);
                return _currentResourceTask;
            }
            if (mod.getItemStorage().getItemCount(Items.HAY_BLOCK) >= 1) {
                setDebugState("Crafting Wheat");
                Item[] o = null;
                _currentResourceTask = new CraftInInventoryTask(new RecipeTarget(Items.WHEAT, 99999999, CraftingRecipe.newShapedRecipe("wheat", new Item[][]{new Item[]{Items.HAY_BLOCK}, o, o, o}, 9)), false, false);
                return _currentResourceTask;
            }
            // Convert raw foods -> cooked foods

            for (CookableFoodTarget cookable : COOKABLE_FOODS) {
                int rawCount = mod.getItemStorage().getItemCount(cookable.getRaw());
                // one smoker, one input slot: the next batch waits for the one already cooking to be collected, loading it
                // on top would swap the first batch back out (the gamer collects, then asks for this food again)
                if (rawCount > 0 && AsyncSmelting.pendingFoodUnits() == 0) {
                    //Debug.logMessage("STARTING COOK OF " + cookable.getRaw().getTranslationKey());
                    int toSmelt = rawCount + mod.getItemStorage().getItemCount(cookable.getCooked());
                    _smeltTask = cook(mod, new SmeltTarget(new ItemTarget(cookable.cookedFood, toSmelt), new ItemTarget(cookable.rawFood, rawCount)));
                    return _smeltTask;
                }
            }
            // the buddy's fork: potatoes baked and kelp dried in the same smoker
            for (String[] rawCooked : new String[][]{{"potato", "baked_potato"}, {"kelp", "dried_kelp"}}) {
                Item raw = rawCooked[0].equals("potato") ? Items.POTATO : Items.KELP;
                Item cooked = rawCooked[0].equals("potato") ? Items.BAKED_POTATO : Items.DRIED_KELP;
                int rawCount = mod.getItemStorage().getItemCount(raw);
                if (rawCount > 0 && AsyncSmelting.pendingFoodUnits() == 0) {
                    int toSmelt = rawCount + mod.getItemStorage().getItemCount(cooked);
                    _smeltTask = cook(mod, new SmeltTarget(new ItemTarget(rawCooked[1], toSmelt), new ItemTarget(rawCooked[0], rawCount)));
                    return _smeltTask;
                }
            }
        } else {
            // Pick up food items from ground
            for (Item item : ITEMS_TO_PICK_UP) {
                Task t = this.pickupTaskOrNull(mod, item);
                if (t != null) {
                    setDebugState("Picking up Food: " + item.getDescriptionId());
                    _currentResourceTask = t;
                    return _currentResourceTask;
                }
            }
            // Pick up raw/cooked foods on ground
            for (CookableFoodTarget cookable : COOKABLE_FOODS) {
                Task t = this.pickupTaskOrNull(mod, cookable.getRaw(), 20);
                if (t == null) t = this.pickupTaskOrNull(mod, cookable.getCooked(), 40);
                if (t != null) {
                    setDebugState("Picking up Cookable food");
                    _currentResourceTask = t;
                    return _currentResourceTask;
                }
            }
            // the animal we are already fighting beats any pile of hay, and one that wanders by is a few swings
            Task prey = huntCommitted(mod) ? huntTaskOrNull(mod) : nearbyPreyOrNull(mod, FoodHunt.ALONG_THE_WAY_RADIUS);
            if (prey != null) {
                _currentResourceTask = prey;
                return _currentResourceTask;
            }
            // Hay blocks. past ~96 the walk costs more than a hunt, and we have a hunt branch now (it was 300, which
            // sent the bot 84 blocks away for hay mid chest)
            Task hayTaskBlock = this.pickupBlockTaskOrNull(mod, Blocks.HAY_BLOCK, Items.HAY_BLOCK, 96);
            if (hayTaskBlock != null) {
                Task hoe = hoeTaskOrNull(mod, hayInReach(mod).size());
                if (hoe != null) {
                    return hoe;
                }
                setDebugState("Collecting Hay");
                _currentResourceTask = hayTaskBlock;
                return _currentResourceTask;
            }
            // Crops. only the grown ones count as food, for every kind: this used to ask wheat and let young carrots
            // through, so a farm of seedlings was "food" the bot stood in breaking one carrot back at a time
            for (CropTarget target : CROPS) {
                Task t = pickupBlockTaskOrNull(mod, target.cropBlock, target.cropItem, blockPos -> ripeAndBreakable(mod, blockPos), 96);
                if (t != null) {
                    setDebugState("Harvesting " + target.cropItem.getDescriptionId());
                    // a drop already lying there is just a pickup. the field itself goes through CollectCropTask, which
                    // knows to wait for the drop and to replant what it breaks
                    _currentResourceTask = t instanceof PickupDroppedItemTask ? t : cropTask(mod, target, potentialFood);
                    return _currentResourceTask;
                }
            }
            // Cooked foods
            Task hunt = huntTaskOrNull(mod);
            if (hunt != null) {
                _currentResourceTask = hunt;
                return _currentResourceTask;
            }

            // the buddy's fork (1 Nov 2026: it walked past the huge mushrooms of a dark forest hungry): mushroom stew
            // from a red and a brown mushroom and a bowl, when both kinds are near. One stew at a time, they do not stack
            if (near(mod, MUSHROOMS_RED, STEW_RANGE) && near(mod, MUSHROOMS_BROWN, STEW_RANGE)) {
                setDebugState("Making mushroom stew (huge mushrooms near)");
                _currentResourceTask = TaskCatalogue.getItemTask("mushroom_stew", mod.getItemStorage().getItemCount(Items.MUSHROOM_STEW) + 1);
                return _currentResourceTask;
            }
            // and melons: a block gives three to seven slices of two points
            Task melonPickup = pickupBlockTaskOrNull(mod, Blocks.MELON, Items.MELON_SLICE, 96);
            if (melonPickup != null) {
                setDebugState("Getting melon slices");
                _currentResourceTask = melonPickup;
                return _currentResourceTask;
            }

            // glow berries on a lush cave's vines (only a vine that carries them)
            Task glow = pickupBlockTaskOrNull(mod, Blocks.CAVE_VINES_PLANT, Items.GLOW_BERRIES, pos -> berries(mod, pos), 64);
            if (glow == null) glow = pickupBlockTaskOrNull(mod, Blocks.CAVE_VINES, Items.GLOW_BERRIES, pos -> berries(mod, pos), 64);
            if (glow != null) {
                setDebugState("Getting glow berries");
                _currentResourceTask = glow;
                return _currentResourceTask;
            }

            // Sweet berries (separate from crops because they should have a lower priority than everything else cause they suck)
            Task berryPickup = pickupBlockTaskOrNull(mod, Blocks.SWEET_BERRY_BUSH, Items.SWEET_BERRIES, 96);
            if (berryPickup != null) {
                setDebugState("Getting sweet berries (no better foods are present)");
                _currentResourceTask = berryPickup;
                return _currentResourceTask;
            }
            // the buddy's fork: out on open water there is nothing to hunt and nothing to dry kelp with, so land first. a fresh
            // world dropped it in the sea and the wander below swam it about for three game days, with one chicken to show for
            // it (the runner, 2 Nov 2026)
            Task land = toLand(mod);
            if (land != null) return land;
            // the buddy's fork (1 Nov 2026: it walked on along the sea past the kelp it could have dried): kelp, a
            // point a piece once dried, but a sea holds plenty. Last, when nothing better is near
            if (near(mod, KELP, 48)) {
                setDebugState("Getting kelp to dry");
                if (_waterPickupWas == null) {
                    _waterPickupWas = Baritone.settings().altoPickupItemsInWater.value;
                    Baritone.settings().altoPickupItemsInWater.value = true;
                }
                int want = Math.max(8, (int) Math.ceil(_unitsNeeded - potentialFood));
                _currentResourceTask = TaskCatalogue.getItemTask("kelp", mod.getItemStorage().getItemCount(Items.KELP) + Math.min(want, 64));
                return _currentResourceTask;
            }
        }

        // Look for food.
        Task land = toLand(mod);
        if (land != null) return land;
        setDebugState("Searching...");
        // (only a real search: with enough food in hand and the smoker busy this is just waiting, not a dry spell)
        if (potentialFood < _unitsNeeded) {
            lastSearchMs = System.currentTimeMillis();
        }
        return new TimeoutWanderTask();
    }

    // the cook of one batch: in a smoker, as ever, or (the buddy's fork, Tuning bag_keep_stations, a fix of 13 Nov 2026, on) in the
    // bag's furnace while the bag holds one and no smoker. a smoker is made of a furnace and four logs: the food run of M3.3's
    // smoke 5 made one of the gold batch's furnace, and the next gold batch had no furnace and no wood for a table to make one.
    // a furnace cooks half as fast and costs nothing, and goes back in the bag after (StationsBack)
    private static adris.altoclef.tasks.ResourceTask cook(AltoClef mod, SmeltTarget target) {
        if (adris.altoclef.util.helpers.GarbageRules.keepStationsOn() && mod.getItemStorage().getItemCount(Items.FURNACE) > 0
                && mod.getItemStorage().getItemCount(Items.SMOKER) == 0) {
            Debug.logInternal("food: cooked in the bag's furnace, no smoker made of it (bag_keep_stations)");
            adris.altoclef.tasks.container.SmeltInFurnaceTask furnace = new adris.altoclef.tasks.container.SmeltInFurnaceTask(target);
            furnace.ignoreMaterials();
            return furnace;
        }
        SmeltInSmokerTask smoker = new SmeltInSmokerTask(target);
        smoker.ignoreMaterials();
        return smoker;
    }

    // out on open water for half a game minute: to land (LandFinder.Steer). null on land
    private Task toLand(AltoClef mod) {
        Task t = _toLand.next(mod);
        if (t == null) t = _toLand.inland(mod);
        if (t != null) setDebugState(_toLand.what());
        return t;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // the cook we started may be cut off with the meat already in the smoker (a mob, a death): leave the job for it, or the
        // meat sits there with nobody knowing. a no-op when it handed off or never loaded anything
        if (_smeltTask instanceof SmeltInSmokerTask smoker && smoker.isActive()) {
            smoker.recordLeftBehind(mod);
        } else if (_smeltTask instanceof adris.altoclef.tasks.container.SmeltInFurnaceTask furnace && furnace.isActive()) {
            furnace.recordLeftBehind(mod);
        }
        mod.getBehaviour().pop();
        mod.getBlockTracker().stopTracking(Blocks.HAY_BLOCK);
        mod.getBlockTracker().stopTracking(Blocks.SWEET_BERRY_BUSH);
        mod.getBlockTracker().stopTracking(MUSHROOMS_RED);
        mod.getBlockTracker().stopTracking(MUSHROOMS_BROWN);
        mod.getBlockTracker().stopTracking(Blocks.MELON);
        mod.getBlockTracker().stopTracking(GLOW_BERRY_VINES);
        mod.getBlockTracker().stopTracking(KELP);
        if (_waterPickupWas != null) {
            Baritone.settings().altoPickupItemsInWater.value = _waterPickupWas;
            _waterPickupWas = null;
        }
        for (CropTarget crop : CROPS) {
            mod.getBlockTracker().stopTracking(crop.cropBlock);
        }
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (_nearbyOnly) {
            // done when nothing more is within reach, but not before the last kill's meat is picked up
            boolean busy = _currentResourceTask != null && _currentResourceTask.isActive() && !_currentResourceTask.isFinished(mod);
            return !busy && (_nearbyDone || !cheapExtra(mod));
        }
        return StorageHelper.calculateInventoryFoodScore(mod) >= _unitsNeeded && !cheapExtra(mod);
    }

    // the buddy's fork (1 Nov 2026: when extra food is cheap to get, say a few more cows or melons close by, it is taken
    // along): with the need met, an animal that is
    // food or a melon within a few steps is taken too, up to twice the need (EXTRA_CAP points at most), for a minute at
    // most, so an animal across a river holds nothing up. food on the way (nearby) goes up to its own cap
    private static final double EXTRA_RANGE = 12;
    private static final long EXTRA_MS = 60_000;
    // (it was 128: with the buddy's 160 points before the nether the extra never came in)
    private static final double EXTRA_CAP = 256;
    private long _extraSince = -1;

    private boolean cheapExtra(AltoClef mod) {
        if (mod.getPlayer() == null) return false;
        double cap = _nearbyOnly ? _unitsNeeded : Math.min(Math.max(_unitsNeeded * 2, _unitsNeeded + 20), EXTRA_CAP);
        if (calculateFoodPotential(mod) >= cap) return false;
        long now = System.currentTimeMillis();
        if (_extraSince < 0) _extraSince = now;
        if (now - _extraSince > EXTRA_MS) return false;
        if (!mod.getItemStorage().hasEmptyInventorySlot()) return false;
        Vec3 me = mod.getPlayer().position();
        for (CookableFoodTarget c : COOKABLE_FOODS) {
            if (c.kind == null) continue;
            Optional<Entity> e = mod.getEntityTracker().getClosestEntity(CollectFoodTask::edible, c.mobToKill);
            if (e.isPresent() && e.get().position().distanceToSqr(me) < EXTRA_RANGE * EXTRA_RANGE) return true;
        }
        return near(mod, new Block[]{Blocks.MELON}, EXTRA_RANGE);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof CollectFoodTask task) {
            return task._unitsNeeded == _unitsNeeded && task._nearbyOnly == _nearbyOnly;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return _nearbyOnly ? "Take food on the way, up to " + _unitsNeeded + " units." : "Collect " + _unitsNeeded + " units of food.";
    }

    @Override
    protected String toHudString() {
        return "Getting food";
    }

    /**
     * Returns a task that mines a block and picks up its output.
     * Returns null if task cannot reasonably run.
     */
    private Task pickupBlockTaskOrNull(AltoClef mod, Block blockToCheck, Item itemToGrab, Predicate<BlockPos> accept, double maxRange) {
        return pickupBlockTaskOrNull(mod, blockToCheck, itemToGrab, accept, maxRange, Double.POSITIVE_INFINITY);
    }

    // dropRange is for the sweep, which must not wander off after a bale somebody dropped a chunk away
    private Task pickupBlockTaskOrNull(AltoClef mod, Block blockToCheck, Item itemToGrab, Predicate<BlockPos> accept, double maxRange, double dropRange) {
        Predicate<BlockPos> acceptPlus = (blockPos) -> {
            if (!WorldHelper.canBreak(mod, blockPos)) return false;
            return accept.test(blockPos);
        };
        Optional<BlockPos> nearestBlock = mod.getBlockTracker().getNearestTracking(mod.getPlayer().position(), acceptPlus, blockToCheck);

        if (nearestBlock.isPresent() && !nearestBlock.get().closerToCenterThan(mod.getPlayer().position(), maxRange)) {
            nearestBlock = Optional.empty();
        }

        Optional<ItemEntity> nearestDrop = Optional.empty();
        if (mod.getEntityTracker().itemDropped(itemToGrab)) {
            nearestDrop = mod.getEntityTracker().getClosestItemDrop(mod.getPlayer().position(), itemToGrab)
                    .filter(drop -> drop.closerThan(mod.getPlayer(), dropRange));
        }
        boolean spotted = nearestBlock.isPresent() || nearestDrop.isPresent();
        // Collect hay until we have enough.
        if (spotted) {
            if (nearestDrop.isPresent()) {
                return new PickupDroppedItemTask(itemToGrab, Integer.MAX_VALUE);
            } else {
                DoToClosestBlockTask dig = new DoToClosestBlockTask(DestroyBlockTask::new, acceptPlus, blockToCheck);
                // a bale always drops itself, so the next one waits for it. (berries don't always, and crops go through
                // CollectCropTask)
                return itemToGrab == Items.HAY_BLOCK ? dig.expectDrops() : dig;
            }
        }
        return null;
    }

    // grown and in a chunk we can read (the age of an unloaded one is a guess) and ours to break
    private static boolean ripeAndBreakable(AltoClef mod, BlockPos pos) {
        return mod.getChunkTracker().isChunkLoaded(pos) && CropRules.ripe(mod.getWorld().getBlockState(pos)) && WorldHelper.canBreak(mod, pos);
    }

    // the food still wanted, in items of this crop. one more than we hold at the very least, a farm run for nothing is not a run
    private Task cropTask(AltoClef mod, CropTarget target, double potentialFood) {
        int held = mod.getItemStorage().getItemCount(target.cropItem);
        int more = (int) Math.max(1, Math.ceil((_unitsNeeded - potentialFood) / target.unitFood));
        return new CollectCropTask(new ItemTarget(target.cropItem, held + more), new Block[]{target.cropBlock}, new Item[]{target.seed},
                pos -> WorldHelper.canBreak(mod, pos));
    }

    // the hunted animal is dead but the loot of the kill is still on its way or lying there: the hunt is not over (see
    // KillAndLootTask.awaitingDrop)
    private boolean lootComing(AltoClef mod) {
        boolean coming = _huntTask instanceof KillAndLootTask kill && kill.awaitingDrop(mod);
        if (coming) {
            lastLootMs = System.currentTimeMillis();
        }
        return coming;
    }

    private Task pickupBlockTaskOrNull(AltoClef mod, Block blockToCheck, Item itemToGrab, double maxRange) {
        return pickupBlockTaskOrNull(mod, blockToCheck, itemToGrab, toAccept -> true, maxRange);
    }

    // the pillager outpost bales have a carved pumpkin on top and a pillager nearby. no thanks
    private static boolean isOutpostHay(AltoClef mod, BlockPos hay) {
        return mod.getWorld().getBlockState(hay.above()).getBlock() == Blocks.CARVED_PUMPKIN;
    }

    private static boolean haySweepable(AltoClef mod, BlockPos pos) {
        return pos.closerToCenterThan(mod.getPlayer().position(), HaySweep.RADIUS) && !isOutpostHay(mod, pos);
    }

    // the bales the sweep would still take: near us, not blacklisted (blockIsValid says no to those), not an outpost's
    private List<BlockPos> hayInReach(AltoClef mod) {
        List<BlockPos> reach = new ArrayList<>();
        if (!mod.getBlockTracker().isTracking(Blocks.HAY_BLOCK)) {
            return reach;
        }
        for (BlockPos pos : mod.getBlockTracker().getKnownLocations(Blocks.HAY_BLOCK)) {
            if (haySweepable(mod, pos) && mod.getBlockTracker().blockIsValid(pos, Blocks.HAY_BLOCK) && WorldHelper.canBreak(mod, pos)) {
                reach.add(pos);
            }
        }
        return reach;
    }

    private boolean hayDropNearby(AltoClef mod) {
        return mod.getEntityTracker().itemDropped(Items.HAY_BLOCK)
                && mod.getEntityTracker().getClosestItemDrop(mod.getPlayer().position(), Items.HAY_BLOCK)
                .filter(drop -> drop.closerThan(mod.getPlayer(), HaySweep.RADIUS)).isPresent();
    }

    // bales in reach plus one for a dropped one, so the sweep does not quit with a bale lying at our feet
    private int hayLeft(AltoClef mod) {
        return hayInReach(mod).size() + (hayDropNearby(mod) ? 1 : 0);
    }

    private boolean sweepWanted(AltoClef mod) {
        _hayLeft = hayLeft(mod);
        boolean keep = HaySweep.keepSweeping(true, _hayLeft, mod.getItemStorage().getItemCountInventoryOnly(Items.HAY_BLOCK), _sweepTimer.getDuration());
        if (!keep) {
            _sweepOver = true;
        }
        return keep;
    }

    // potential says we have enough, but the pile is right here. null when there is nothing (left) to sweep
    private Task sweepHayOrNull(AltoClef mod) {
        if (_sweepOver) {
            return null;
        }
        int held = mod.getItemStorage().getItemCountInventoryOnly(Items.HAY_BLOCK);
        _hayLeft = hayLeft(mod);
        double elapsed = _sweepTimer == null ? 0 : _sweepTimer.getDuration();
        if (!HaySweep.keepSweeping(true, _hayLeft, held, elapsed)) {
            // not having started yet is not the end, the pile might just not be loaded in
            _sweepOver = _sweepTimer != null;
            return null;
        }
        if (_sweepTimer == null) {
            _sweepTimer = new TimerGame(HaySweep.BUDGET_SECONDS);
            _sweepTimer.reset();
        }
        Task hoe = hoeTaskOrNull(mod, _hayLeft);
        if (hoe != null) {
            return hoe;
        }
        Task sweep = pickupBlockTaskOrNull(mod, Blocks.HAY_BLOCK, Items.HAY_BLOCK, pos -> haySweepable(mod, pos), HaySweep.RADIUS, HaySweep.RADIUS);
        if (sweep == null) {
            _sweepOver = true;
            return null;
        }
        setDebugState("Sweeping the hay pile (" + _hayLeft + " left)");
        _sweepTask = sweep;
        _currentResourceTask = sweep;
        return sweep;
    }

    // a hoe is ~2x the swing speed on hay, worth a craft for a pile but only from what we already carry and a table we
    // already have: nobody goes tree hunting for a hoe. once per task, win or lose
    private Task hoeTaskOrNull(AltoClef mod, int pile) {
        if (_hoeDone || pile < HaySweep.HOE_PILE_MIN || !_hoeCheckTimer.elapsed()) {
            return null;
        }
        _hoeCheckTimer.reset();
        boolean hasHoe = mod.getItemStorage().hasItemInventoryOnly(HOES);
        var me = mod.getPlayer().position();
        // ours within NEAR is a table the craft would walk to anyway (StationChoice), so it counts from as far as that. a world one
        // only from the small cube scan, a 21 block cube every few seconds is 80k cells for a hoe
        boolean tableHandy = mod.getItemStorage().hasItemInventoryOnly(Items.CRAFTING_TABLE)
                || StationHook.standingNear(StationHook.Kind.TABLE, me.x, me.y, me.z) != null
                || mod.getBlockTracker().getNearestWithinRange(me, HaySweep.HOE_TABLE_RANGE, Blocks.CRAFTING_TABLE).isPresent();
        int cobble = mod.getItemStorage().getItemCountInventoryOnly(Items.COBBLESTONE);
        int planks = mod.getItemStorage().getItemCountInventoryOnly(ItemHelper.PLANKS);
        int sticks = mod.getItemStorage().getItemCountInventoryOnly(Items.STICK);
        HaySweep.Hoe hoe = HaySweep.pickHoe(pile, hasHoe, tableHandy, cobble, planks, sticks);
        if (hoe == HaySweep.Hoe.NONE) {
            return null;
        }
        _hoeDone = true;
        _hoeTimer = new TimerGame(HaySweep.HOE_BUDGET_SECONDS);
        _hoeTimer.reset();
        _hoeTask = TaskCatalogue.getItemTask(hoe == HaySweep.Hoe.STONE ? "stone_hoe" : "wooden_hoe", 1);
        setDebugState("Making a hoe for the hay");
        return _hoeTask;
    }

    private record Prey(Entity entity, CookableFoodTarget food) {
    }

    // not a baby (no meat), not carrying a passenger (chicken jockey, the blacklist in onTick only knows the closest one)
    private static boolean edible(Entity entity) {
        return PreyScan.edible(entity);
    }

    // the nearest animal of EVERY kind goes into the pot, plus the one we are already after (it might not be the nearest
    // of its kind any more), and FoodHunt picks. null when nothing edible is loaded
    private Task huntTaskOrNull(AltoClef mod) {
        Task hunt = killTaskOrNull(mod, Double.POSITIVE_INFINITY);
        if (hunt == null) {
            _hunted = null;
        }
        return hunt;
    }

    // same pot as huntTaskOrNull but only what is within radius, and it leaves the current hunt alone when nothing
    // qualifies. a hit sets the hunt fields, so the commit rule protects this kill too
    private Task nearbyPreyOrNull(AltoClef mod, double radius) {
        return killTaskOrNull(mod, radius);
    }

    private Task killTaskOrNull(AltoClef mod, double radius) {
        Vec3 me = mod.getPlayer().position();
        Map<Integer, Prey> prey = new HashMap<>();
        List<FoodHunt.Candidate> candidates = new ArrayList<>();
        for (CookableFoodTarget cookable : COOKABLE_FOODS) {
            if (cookable.kind == null) {
                continue;
            }
            Optional<Entity> nearest = mod.getEntityTracker().getClosestEntity(CollectFoodTask::edible, cookable.mobToKill);
            nearest.ifPresent(e -> addPrey(me, e, cookable, prey, candidates));
        }
        // dead or blacklisted is not a candidate, so it can not be sticky either
        if (_hunted != null && huntedOk(mod) && !prey.containsKey(_hunted.getId())) {
            addPrey(me, _hunted, _huntedFood, prey, candidates);
        }
        List<FoodHunt.Candidate> pot = radius == Double.POSITIVE_INFINITY ? candidates : FoodHunt.within(candidates, radius);
        FoodHunt.Candidate pick = FoodHunt.choose(pot, _hunted == null ? -1 : _hunted.getId(), FoodHunt.isWoolWanted());
        if (pick == null) {
            return null;
        }
        Prey chosen = prey.get(pick.id());
        _hunted = chosen.entity();
        _huntedFood = chosen.food();
        setDebugState("Killing " + chosen.entity().getType().getDescriptionId());
        // one more than we hold: with the old flat 1 a bag that already had a mutton called the sheep hunt finished before it began
        int held = mod.getItemStorage().getItemCount(chosen.food().getRaw());
        _huntTask = new KillAndLootTask(chosen.entity(), new ItemTarget(chosen.food().getRaw(), held + 1));
        return _huntTask;
    }

    // mid fight and close: finish it. the hay will still be there in thirty seconds
    private boolean huntCommitted(AltoClef mod) {
        if (_hunted == null) {
            return false;
        }
        Vec3 me = mod.getPlayer().position();
        Vec3 at = _hunted.position();
        double distance = FoodHunt.distance(at.x - me.x, at.y - me.y, at.z - me.z);
        return FoodHunt.keepHunting(huntedOk(mod), distance);
    }

    private boolean huntedOk(AltoClef mod) {
        return _hunted.isAlive() && edible(_hunted) && mod.getEntityTracker().isEntityReachable(_hunted);
    }

    private boolean huntStillOn(AltoClef mod) {
        return _hunted != null && huntedOk(mod);
    }

    private static void addPrey(Vec3 me, Entity e, CookableFoodTarget food, Map<Integer, Prey> prey, List<FoodHunt.Candidate> candidates) {
        Vec3 at = e.position();
        double distance = FoodHunt.distance(at.x - me.x, at.y - me.y, at.z - me.z);
        prey.put(e.getId(), new Prey(e, food));
        candidates.add(new FoodHunt.Candidate(e.getId(), food.kind, distance));
    }

    /**
     * Returns a task that picks up a dropped item.
     * Returns null if task cannot reasonably run.
     */
    private Task pickupTaskOrNull(AltoClef mod, Item itemToGrab, double maxRange) {
        Optional<ItemEntity> nearestDrop = Optional.empty();
        if (mod.getEntityTracker().itemDropped(itemToGrab)) {
            nearestDrop = mod.getEntityTracker().getClosestItemDrop(mod.getPlayer().position(), itemToGrab);
        }
        if (nearestDrop.isPresent()) {
            if (nearestDrop.get().closerThan(mod.getPlayer(), maxRange)) {
                return new PickupDroppedItemTask(new ItemTarget(itemToGrab), true);
            }
            //return new GetToBlockTask(nearestDrop.getBlockPos(), false);
        }
        return null;
    }

    private Task pickupTaskOrNull(AltoClef mod, Item itemToGrab) {
        return pickupTaskOrNull(mod, itemToGrab, Double.POSITIVE_INFINITY);
    }

    @SuppressWarnings("rawtypes")
    private static class CookableFoodTarget {
        public String rawFood;
        public String cookedFood;
        public Class mobToKill;
        public FoodHunt.Kind kind;

        public CookableFoodTarget(String rawFood, String cookedFood, Class mobToKill, FoodHunt.Kind kind) {
            this.rawFood = rawFood;
            this.cookedFood = cookedFood;
            this.mobToKill = mobToKill;
            this.kind = kind;
        }

        public CookableFoodTarget(String rawFood, Class mobToKill, FoodHunt.Kind kind) {
            this(rawFood, "cooked_" + rawFood, mobToKill, kind);
        }

        private Item getRaw() {
            return Objects.requireNonNull(TaskCatalogue.getItemMatches(rawFood))[0];
        }

        private Item getCooked() {
            return Objects.requireNonNull(TaskCatalogue.getItemMatches(cookedFood))[0];
        }

        public int getCookedUnits() {
            assert getCooked().components().get(DataComponents.FOOD) != null;
            return Objects.requireNonNull(getCooked().components().get(DataComponents.FOOD)).nutrition();
        }

        public boolean isFish() {
            return false;
        }
    }

    @SuppressWarnings("rawtypes")
    private static class CookableFoodTargetFish extends CookableFoodTarget {

        public CookableFoodTargetFish(String rawFood, Class mobToKill) {
            super(rawFood, mobToKill, null);
        }

        @Override
        public boolean isFish() {
            return true;
        }
    }

    private static class CropTarget {
        public Item cropItem;
        public Block cropBlock;
        public Item seed;
        public double unitFood;

        public CropTarget(Item cropItem, Block cropBlock, Item seed, double unitFood) {
            this.cropItem = cropItem;
            this.cropBlock = cropBlock;
            this.seed = seed;
            this.unitFood = unitFood;
        }
    }
}
