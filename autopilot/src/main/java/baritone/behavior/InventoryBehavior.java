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

package baritone.behavior;

import adris.altoclef.trace.Trace;
import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.event.events.TickEvent;
import baritone.api.utils.Helper;
import baritone.process.PlaceRules;
import baritone.utils.ExperimentalMovement;
import baritone.utils.ToolSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public final class InventoryBehavior extends Behavior implements Helper {

    private static final int THROWAWAY_SLOT = 7;

    int ticksSinceLastInventoryMove;
    int[] lastTickRequestedMove; // not everything asks every tick, so remember the request while coming to a halt
    private String lastTickRequestedWho; // who asked for it, so the replay in the trace says whose it was
    // the item throwaway() selected slot 7 for while its swap was throttled, and when. lastTickRequestedMove can't answer
    // this: the pick and the throwaway refill overwrite it every tick before the click is looked at
    private Item awaitedItem;
    private long awaitedAt;
    private long tickCount;
    // what was in hand last tick, for the trace. a flickering hotbar is invisible in a log otherwise
    private int lastSelected = -1;
    private Item lastHeld;

    public InventoryBehavior(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.OUT) {
            tickCount++;
            traceHand();
        }
        boolean shuffle = Baritone.settings().allowInventory.value;
        // the clutch item only works from the hotbar (nobody shuffles the inventory mid fall), so that one move is allowed
        // even when the user keeps allowInventory off
        boolean clutch = ExperimentalMovement.allowLadderClutch();
        if (!(shuffle || clutch) || AltoClefSettings.getInstance().isInteractionPaused()) {
            return; // paused means the hotbar is somebody else's problem right now
        }
        if (event.getType() == TickEvent.Type.OUT) {
            return;
        }
        if (ctx.player().containerMenu != ctx.player().inventoryMenu) {
            // we have a crafting table or a chest or something open
            return;
        }
        ticksSinceLastInventoryMove++;
        boolean creativeBuild = creativeBuild();
        if (clutch && !creativeBuild && baritone.getPathingBehavior().isPathing()) {
            keepClutchItemOnHotbar();
        }
        if (shuffle && !creativeBuild && baritone.getPathingBehavior().isPathing()) {
            if (firstValidThrowaway() >= 9) { // aka there are none on the hotbar, but there are some in main inventory
                requestSwapWithHotBar(firstValidThrowaway(), 8, "tool");
            }
            int pick = bestToolAgainst(Blocks.STONE);
            if (pick >= 9) {
                requestSwapWithHotBar(pick, 0, "tool");
            }
        }
        if (lastTickRequestedMove != null) {
            logDebug("Remembering to move " + lastTickRequestedMove[0] + " " + lastTickRequestedMove[1] + " from a previous tick");
            requestSwapWithHotBar(lastTickRequestedMove[0], lastTickRequestedMove[1], lastTickRequestedWho, true);
        }
    }

    private void traceHand() {
        LocalPlayer p = ctx.player();
        if (p == null) {
            return;
        }
        int slot = p.getInventory().getSelectedSlot();
        Item held = p.getInventory().getNonEquipmentItems().get(slot).getItem();
        if (lastSelected >= 0 && (slot != lastSelected || held != lastHeld)) {
            Trace.record("held " + lastHeld + "->" + held + " slot " + lastSelected + "->" + slot);
        }
        lastSelected = slot;
        lastHeld = held;
    }

    // a creative build hands itself every block it places into the hotbar (BuilderProcess.creativeSlot), breaks anything in
    // one hit and can't take fall damage. a pickaxe, a clutch ladder or a bag swap into slot 7 would just land on one of
    // its blocks and it'd hand it back next click, so while it runs the hotbar is the builder's
    private boolean creativeBuild() {
        return ctx.player() != null && ctx.player().hasInfiniteMaterials() && baritone.getBuilderProcess().isActive();
    }

    // ladders crafted or picked up land wherever there was room, and the clutch only looks at the hotbar
    private void keepClutchItemOnHotbar() {
        if (pickClutchItem(false) != null) {
            return;
        }
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 9; i < 36; i++) {
            if (inv.get(i).is(Items.VINE) || inv.get(i).is(Items.LADDER)) {
                // fetchClutchItemNow is the one for an actual fall, this is the calm version
                attemptToPutOnHotbar(i, slot -> false, "clutch");
                return;
            }
        }
    }

    // a fall doesn't wait for the move delay or for us to stand still (we're not going to be, that's the whole problem), so
    // this just does the swap. altoclef's unplanned fall calls it when the ladder is still in the main inventory
    public boolean fetchClutchItemNow() {
        if (AltoClefSettings.getInstance().isInteractionPaused() || ctx.player().containerMenu != ctx.player().inventoryMenu || !ctx.player().containerMenu.getCarried().isEmpty()) {
            return false;
        }
        if (pickClutchItem(false) != null) {
            return true; // already there
        }
        if (creativeBuild()) {
            return false; // no fall damage to clutch against, and the slot it'd take is one of the builder's
        }
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 9; i < 36; i++) {
            if (inv.get(i).is(Items.VINE) || inv.get(i).is(Items.LADDER)) {
                OptionalInt slot = getTempHotbarSlot(s -> false);
                if (slot.isEmpty()) {
                    return false;
                }
                ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, i, slot.getAsInt(), ContainerInput.SWAP, ctx.player());
                ticksSinceLastInventoryMove = 0;
                Trace.record("swap inv" + i + "<->hot" + slot.getAsInt() + " by clutch");
                return true;
            }
        }
        return false;
    }

    public boolean attemptToPutOnHotbar(int inMainInvy, Predicate<Integer> disallowedHotbar) {
        return attemptToPutOnHotbar(inMainInvy, disallowedHotbar, "other");
    }

    public boolean attemptToPutOnHotbar(int inMainInvy, Predicate<Integer> disallowedHotbar, String who) {
        if (AltoClefSettings.getInstance().isInteractionPaused()) {
            return false;
        }
        OptionalInt destination = getTempHotbarSlot(disallowedHotbar);
        if (destination.isEmpty()) {
            // nowhere to put it is not "it's on the hotbar". this said true and the builder went on to stand in the goal
            // with nothing to click. movePending tells this apart from a swap that's only throttled
            return false;
        }
        return requestSwapWithHotBar(inMainInvy, destination.getAsInt(), who);
    }

    // a swap somebody asked for that the move delay is still holding back. the builder's stall check counts it as
    // something happening
    public boolean movePending() {
        return lastTickRequestedMove != null;
    }

    // the one place a placement click is vetted (see InputOverrideHandler): throwaway() selects slot 7 even when the swap into
    // it was throttled, and a right click with a bucket or a steak in hand empties the bucket or eats the steak
    public boolean handWaitingOnSwap() {
        if (awaitedItem == null) {
            return false;
        }
        LocalPlayer p = ctx.player();
        return ThrowawayPicks.handWaiting(awaitedItem, p.getInventory().getNonEquipmentItems().get(THROWAWAY_SLOT).getItem(), THROWAWAY_SLOT,
                p.getInventory().getSelectedSlot(), awaitedAt, tickCount);
    }

    // put a hotbar slot in hand for someone who is borrowing it (SelfDefenseProcess swaps to a sword and back). nothing
    // is clicked, so the throwaway wait above stays true: picking slot 7 back up while its swap is pending is still
    // "waiting on the swap", and handWaitingOnSwap keeps saying so. false when the hand is somebody else's right now
    public boolean selectHotbar(int slot, String who) {
        LocalPlayer p = ctx.player();
        if (p == null || slot < 0 || slot > 8 || AltoClefSettings.getInstance().isInteractionPaused()) {
            return false;
        }
        if (p.getInventory().getSelectedSlot() != slot) {
            p.getInventory().setSelectedSlot(slot);
            Trace.record("select hot" + slot + " by " + who);
        }
        return true;
    }

    public OptionalInt getTempHotbarSlot(Predicate<Integer> disallowedHotbar) {
        // we're using 0 and 8 for pickaxe and throwaway. the rest goes through ThrowawayPicks so a temp swap can't stomp
        // the slot in hand or the one the next placement is about to pull from (that one was a fun afternoon)
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        boolean[] empty = new boolean[9];
        int throwawaySlot = -1;
        for (int i = 0; i < 9; i++) {
            empty[i] = inv.get(i).isEmpty();
            if (throwawaySlot < 0 && Baritone.settings().acceptableThrowawayItems.value.contains(inv.get(i).getItem())) {
                throwawaySlot = i;
            }
        }
        int pending = lastTickRequestedMove == null ? -1 : lastTickRequestedMove[1];
        Random rng = new Random();
        return ThrowawayPicks.tempHotbarSlot(empty, disallowedHotbar::test, ctx.player().getInventory().getSelectedSlot(), throwawaySlot, pending, rng::nextInt);
    }

    private boolean requestSwapWithHotBar(int inInventory, int inHotbar, String who) {
        return requestSwapWithHotBar(inInventory, inHotbar, who, false);
    }

    private boolean requestSwapWithHotBar(int inInventory, int inHotbar, String who, boolean replay) {
        if (AltoClefSettings.getInstance().isInteractionPaused()) {
            return false; // and don't remember it either, it'd go off the second the pause ended
        }
        lastTickRequestedMove = new int[]{inInventory, inHotbar};
        lastTickRequestedWho = who;
        if (!moveReady()) {
            return false;
        }
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, inInventory < 9 ? inInventory + 36 : inInventory, inHotbar, ContainerInput.SWAP, ctx.player());
        ticksSinceLastInventoryMove = 0;
        lastTickRequestedMove = null;
        lastTickRequestedWho = null;
        Trace.record("swap inv" + inInventory + "<->hot" + inHotbar + " by " + who + (replay ? "/replay" : ""));
        return true;
    }

    // the move delay first, so a throttled move doesn't also stop us to wait for it (stationaryForInventoryMove asks for a pause)
    private boolean moveReady() {
        if (ticksSinceLastInventoryMove < Baritone.settings().ticksBetweenInventoryMoves.value) {
            logDebug("Inventory move requested but delaying " + ticksSinceLastInventoryMove + " " + Baritone.settings().ticksBetweenInventoryMoves.value);
            return false;
        }
        if (Baritone.settings().inventoryMoveOnlyIfStationary.value && !baritone.getInventoryPauserProcess().stationaryForInventoryMove()) {
            logDebug("Inventory move requested but delaying until stationary");
            return false;
        }
        return true;
    }

    // someone else's inventory click, held to the same rules as ours: allowInventory, the move delay, standing still. the click
    // itself stays theirs (altoclef's SlotHandler has its own cooldown) and only counts as a move if it went out. no
    // remembering a throttled one like requestSwapWithHotBar does, callers ask again next tick and their slots may have moved
    public boolean inventoryMoveIfAllowed(BooleanSupplier click) {
        if (!Baritone.settings().allowInventory.value || AltoClefSettings.getInstance().isInteractionPaused()
                || ctx.player().containerMenu != ctx.player().inventoryMenu || !moveReady()) {
            return false;
        }
        if (!click.getAsBoolean()) {
            return false;
        }
        ticksSinceLastInventoryMove = 0;
        Trace.record("inventory click by hotbartools");
        return true;
    }

    private int firstValidThrowaway() { // TODO offhand idk
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        // same list the movements place from, so the hotbar never gets a stack that has nothing above its reserve
        List<Item> usable = movementThrowaways(true);
        for (int i = 0; i < invy.size(); i++) {
            if (usable.contains(invy.get(i).getItem())) {
                return i;
            }
        }
        return -1;
    }

    private int bestToolAgainst(Block against) {
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        int bestInd = -1;
        double bestSpeed = -1;
        for (int i = 0; i < invy.size(); i++) {
            ItemStack stack = invy.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (Baritone.settings().itemSaver.value && (stack.getDamageValue() + Baritone.settings().itemSaverThreshold.value) >= stack.getMaxDamage() && stack.getMaxDamage() > 1) {
                continue;
            }
            if (stack.is(ItemTags.PICKAXES)) {
                double speed = ToolSet.calculateSpeedVsBlock(stack, against.defaultBlockState()); // takes into account enchants
                if (speed > bestSpeed) {
                    bestSpeed = speed;
                    bestInd = i;
                }
            }
        }
        return bestInd;
    }

    // movement scope: the planner and the movements both ask this, so they can't disagree about what's in the bag.
    // altoclef saving cobble for a recipe doesn't make a parkour any less in need of a block under its feet, but only the
    // cobble above what it's saving counts. (the planner asks once when the context is built and the movements ask again
    // every placement, so a path planned on a surplus that's since been spent fails in the movement and replans)
    public boolean hasGenericThrowaway() {
        for (Item item : movementThrowaways(true)) {
            if (throwaway(false, stack -> item.equals(stack.getItem()))) {
                return true;
            }
        }
        return false;
    }

    // how many blocks a path could actually burn right now, the same spare-above-the-reserve count the movements use.
    // the trip ask takes this off what a what-if path needs before it goes collecting
    public int spareThrowawayCount() {
        AltoClefSettings alto = AltoClefSettings.getInstance();
        int total = 0;
        for (Item item : new LinkedHashSet<>(movementThrowaways(true))) {
            total += ThrowawayPicks.spare(heldCount(item), alto.reservedCount(item));
        }
        return total;
    }

    private List<Item> movementThrowaways(boolean allowProtected) {
        AltoClefSettings alto = AltoClefSettings.getInstance();
        return ThrowawayPicks.order(Baritone.settings().acceptableThrowawayItems.value, alto::isItemProtected, item -> ThrowawayPicks.spare(heldCount(item), alto.reservedCount(item)), allowProtected);
    }

    // the same spare-above-the-reserve rule movementThrowaways applies, for the lookups that match a stack by what the builder
    // wants there. those skipped it and would grab a cobble the recipe was saving as long as it was the right block
    private boolean mayBurn(Item item, boolean allowProtected) {
        AltoClefSettings alto = AltoClefSettings.getInstance();
        boolean isProtected = alto.isItemProtected(item);
        int spare = isProtected ? ThrowawayPicks.spare(heldCount(item), alto.reservedCount(item)) : 0;
        return ThrowawayPicks.mayBurn(isProtected, allowProtected, spare);
    }

    // everywhere it could be, a stack in the offhand is just as placeable. read fresh every ask: a placement takes one off
    // the stack, and the next ask has to see that or we'd spend the reserve one block at a time
    private int heldCount(Item item) {
        int total = 0;
        for (ItemStack stack : ctx.player().getInventory().getNonEquipmentItems()) {
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        ItemStack offhand = ctx.player().getItemBySlot(EquipmentSlot.OFFHAND);
        return offhand.is(item) ? total + offhand.getCount() : total;
    }

    // why the lookups came back empty, for the log. same inputs as the real thing, just asked a different way
    public String whyNoThrowaway(boolean allowProtected, int x, int y, int z) {
        AltoClefSettings alto = AltoClefSettings.getInstance();
        List<Item> acceptable = Baritone.settings().acceptableThrowawayItems.value;
        List<Item> usable = movementThrowaways(allowProtected);
        boolean haveAny = false;
        boolean haveUsable = false;
        boolean usableOnHotbar = false;
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i <= inv.size(); i++) {
            boolean offhand = i == inv.size();
            Item item = (offhand ? ctx.player().getItemBySlot(EquipmentSlot.OFFHAND) : inv.get(i)).getItem();
            haveAny |= acceptable.contains(item);
            if (usable.contains(item)) {
                haveUsable = true;
                usableOnHotbar |= i < 9 || offhand;
            }
        }
        return ThrowawayPicks.classify(alto.isInteractionPaused(), alto.shouldAvoidPlacingAt(x, y, z), haveAny, haveUsable, usableOnHotbar).why;
    }

    // a clutch is a parachute for fall damage. creative and friends take none, and the builder's ladders are schematic
    // blocks, a clutch would hang one where the file wants air and the builder would break it again right under us
    public boolean clutchAllowed() {
        return ExperimentalMovement.allowLadderClutch() && clutchWanted();
    }

    // the half of that altoclef's water bucket clutch asks too (MLGBucketFallChain), its bucket or hay bale is just as
    // much a block in the build as our ladder
    public boolean clutchWanted() {
        LocalPlayer player = ctx.player();
        return player != null && !player.getAbilities().invulnerable
                && baritone.getPathingControlManager().mostRecentInControl().orElse(null) != baritone.getBuilderProcess();
    }

    // vine first, it has no collision box so we can't land on top of it or get the placement refused for standing in it
    // hotbar only, same as the water bucket: there's no time to shuffle the inventory in the middle of a fall
    public Item pickClutchItem(boolean select) {
        if (throwaway(select, stack -> stack.is(Items.VINE), false)) {
            return Items.VINE;
        }
        if (throwaway(select, stack -> stack.is(Items.LADDER), false)) {
            return Items.LADDER;
        }
        return null;
    }

    // allowProtected is the movement scope: a path that needs a block under it right now beats a recipe's stash.
    // anything that isn't mid-movement (backfill) passes false and leaves altoclef's items alone
    public boolean selectThrowawayForLocation(boolean select, int x, int y, int z, boolean allowProtected) {
        AltoClefSettings alto = AltoClefSettings.getInstance();
        if (alto.isInteractionPaused() || alto.shouldAvoidPlacingAt(x, y, z)) {
            return false;
        }
        BlockState maybe = baritone.getBuilderProcess().placeAt(x, y, z, baritone.bsi.get0(x, y, z));
        // only on the builder's own paths, its costOfPlacingAt already went around these cells. anybody else's planner
        // priced them like any other cell and would just fail the movement over and over
        boolean builderPath = maybe != null && baritone.getPathingControlManager().mostRecentInControl().orElse(null) == baritone.getBuilderProcess();
        if (builderPath && !PlaceRules.aimProof(maybe)) {
            // anything short of aim proof: a stair or a slab from a movement comes out however it's aimed, and a fence
            // isn't a full cube so it isn't aim proof either. the builder does all of those itself
            return false;
        }
        // somebody else's movement placing an aimed block puts it down however it happened to be looking (a ladder facing
        // wherever we were turned, the guess below fakes the click), so those get a plain throwaway and the builder sorts
        // the cell out later. slabs count as aimed too (type, see PlaceRules.AIMED). a fence or a wall has nothing to
        // aim and still gets the real thing
        boolean aimed = maybe != null && PlaceRules.aimed(maybe);
        if (maybe != null && !aimed && throwaway(select, stack -> stack.getItem() instanceof BlockItem && mayBurn(stack.getItem(), allowProtected) && maybe.equals(((BlockItem) stack.getItem()).getBlock().getStateForPlacement(new BlockPlaceContext(new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z), Direction.UP, ctx.playerFeet(), false)) {}))))) {
            return true; // gotem
        }
        if (maybe != null && !aimed && throwaway(select, stack -> stack.getItem() instanceof BlockItem && mayBurn(stack.getItem(), allowProtected) && ((BlockItem) stack.getItem()).getBlock().equals(maybe.getBlock()))) {
            return true;
        }
        // creative and not on the hotbar yet: the builder hands it over like one of its own clicks. its costOfPlacingAt
        // already priced this cell as placeable, so saying no here would be the recalc forever from above
        if (builderPath && baritone.getBuilderProcess().creativeHand(maybe, select)) {
            return true;
        }
        if (builderPath) {
            // no cobble in a cell the build wants filled: it got kept as scaffolding and broken back out at cleanup
            return false;
        }
        for (Item item : movementThrowaways(allowProtected)) {
            if (throwaway(select, stack -> item.equals(stack.getItem()))) {
                return true;
            }
        }
        return false;
    }

    public boolean throwaway(boolean select, Predicate<? super ItemStack> desired) {
        // no bag swaps into slot 7 during a creative build, see creativeBuild
        return throwaway(select, desired, Baritone.settings().allowInventory.value && !creativeBuild());
    }

    public boolean throwaway(boolean select, Predicate<? super ItemStack> desired, boolean allowInventory) {
        if (AltoClefSettings.getInstance().isInteractionPaused()) {
            return false; // no selecting, no swapping, and nothing counts as being in the hotbar
        }
        LocalPlayer p = ctx.player();
        NonNullList<ItemStack> inv = p.getInventory().getNonEquipmentItems();
        if (select) {
            awaitedItem = null; // whatever we pick now is picked fresh, an old wait is not about it
        }
        for (int i = 0; i < 9; i++) {
            ItemStack item = inv.get(i);
            // this usage of settings() is okay because it's only called once during pathing
            // (while creating the CalculationContext at the very beginning)
            // and then it's called during execution
            // since this function is never called during cost calculation, we don't need to migrate
            // acceptableThrowawayItems to the CalculationContext
            if (desired.test(item)) {
                if (select) {
                    p.getInventory().setSelectedSlot(i);
                }
                return true;
            }
        }
        if (desired.test(p.getItemBySlot(EquipmentSlot.OFFHAND))) {
            // main hand takes precedence over off hand
            // that means that if we have block A selected in main hand and block B in off hand, right clicking places block B
            // we've already checked above ^ and the main hand can't possible have an acceptablethrowawayitem
            // so we need to select in the main hand something that doesn't right click
            // so not a shovel, not a hoe, not a block, etc
            for (int i = 0; i < 9; i++) {
                ItemStack item = inv.get(i);
                if (item.isEmpty() || item.is(ItemTags.PICKAXES)) {
                    if (select) {
                        p.getInventory().setSelectedSlot(i);
                    }
                    return true;
                }
            }
        }

        if (allowInventory) {
            for (int i = 9; i < 36; i++) {
                if (desired.test(inv.get(i))) {
                    if (select) {
                        // a throttled swap stays pending and lands on a later tick, slot 7 is selected so it lands in hand.
                        // until then nothing may click, see handWaitingOnSwap
                        boolean landed = requestSwapWithHotBar(i, THROWAWAY_SLOT, "throwaway");
                        p.getInventory().setSelectedSlot(THROWAWAY_SLOT);
                        awaitedItem = landed ? null : inv.get(i).getItem();
                        awaitedAt = tickCount;
                    }
                    return true;
                }
            }
        }

        return false;
    }
}
