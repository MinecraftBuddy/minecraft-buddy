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

package baritone.utils;

import adris.altoclef.Debug;
import adris.altoclef.trace.Trace;
import baritone.Baritone;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntSupplier;

public class BlockPlaceHelper {
    // base ticks between places caused by tick logic
    private static final int BASE_PLACE_DELAY = 1;
    // a throwaway click the world hasn't shown yet is given up on after this many ticks. the client predicts the block
    // the same tick, so this is only for a laggy server putting it back late
    private static final int CONFIRM_TICKS = 10;

    private final IPlayerContext ctx;
    private int rightClickTimer;
    // who hears about throwaway blocks we put down, null while nobody cares (then nothing gets tracked at all)
    private PlaceListener listener;
    private final List<Pending> pending = new ArrayList<>(2);
    // every click while building, for the trace: what landed once the world shows it
    private final List<Traced> traced = new ArrayList<>(2);
    // what the build wants at a cell, for the same trace. null answers are air or outside the file
    private Function<BlockPos, BlockState> want = pos -> null;
    // which build's ledger the click went out under, handed back with the confirm. see ScaffoldLedger.current
    private IntSupplier generation = () -> 0;

    BlockPlaceHelper(IPlayerContext playerContext) {
        this.ctx = playerContext;
    }

    // a throwaway block click went through and the block is really there now. game thread
    public interface PlaceListener {
        void placed(BlockPos pos, BlockState state);
    }

    // any traced click (so only while building) that landed, whose it was, and the ledger generation it went out under.
    // game thread
    public interface TracedPlaceListener {
        void placed(BlockPos pos, BlockState state, String who, int generation);
    }

    private TracedPlaceListener tracedListener;

    void setTracedPlaceListener(TracedPlaceListener tracedListener) {
        this.tracedListener = tracedListener;
    }

    private static final class Pending {
        final BlockPos pos;
        final Block block;
        int age;

        Pending(BlockPos pos, Block block) {
            this.pos = pos;
            this.block = block;
        }
    }

    private static final class Traced {
        final BlockPos pos;
        final BlockState before;
        final String who;
        final int generation;
        int age;

        Traced(BlockPos pos, BlockState before, String who, int generation) {
            this.pos = pos;
            this.before = before;
            this.who = who;
            this.generation = generation;
        }
    }

    void setWant(Function<BlockPos, BlockState> want) {
        this.want = want;
    }

    void setGeneration(IntSupplier generation) {
        this.generation = generation;
    }

    public void setPlaceListener(PlaceListener listener) {
        this.listener = listener;
        pending.clear();
    }

    public PlaceListener placeListener() {
        return listener;
    }

    // the list baritone pillars and bridges with. altoclef merges its own throwaways into it while a task runs, so this
    // is the same list the movements picked the block from
    public static boolean throwaway(Item item) {
        return item instanceof BlockItem && Baritone.settings().acceptableThrowawayItems.value.contains(item);
    }

    // would a click requested right now go out. same two checks as tick, so a movement can plan around the throttle
    public boolean ready() {
        return rightClickTimer <= 0 && !ctx.player().isHandsBusy();
    }

    public void tick(boolean rightClickRequested) {
        tick(rightClickRequested, null);
    }

    // who is the trace's name for the click, null for no trace
    public void tick(boolean rightClickRequested, String who) {
        confirm();
        confirmTraced();
        if (rightClickTimer > 0) {
            rightClickTimer--;
            return;
        }
        HitResult mouseOver = ctx.objectMouseOver();
        if (!rightClickRequested || ctx.player().isHandsBusy() || mouseOver == null || mouseOver.getType() != HitResult.Type.BLOCK) {
            return;
        }
        rightClickTimer = Baritone.settings().rightClickSpeed.value - BASE_PLACE_DELAY;
        for (InteractionHand hand : InteractionHand.values()) {
            // asked before the click: the last block of a stack leaves the hand empty, and empty stacks say air
            Pending scaffold = scaffoldClick(hand, (BlockHitResult) mouseOver);
            Traced trace = traceClick(who, hand, (BlockHitResult) mouseOver);
            InteractionResult result = ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), hand, (BlockHitResult) mouseOver);
            // a real place comes back as a fresh Success carrying the shrunk stack (heldItemTransformedTo), so == SUCCESS
            // never matched one. no swing, no scaffold, no trace, and then the off hand clicked too. same split as
            // vanilla startUseItem: success is done, fail is done, only a pass goes on to the item and the other hand
            if (result instanceof InteractionResult.Success success) {
                if (success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
                    ctx.player().swing(hand, SwingAnimation.DEFAULT, false);
                }
                if (scaffold != null) {
                    pending.add(scaffold);
                }
                if (trace != null) {
                    traced.add(trace);
                }
                return;
            }
            if (result instanceof InteractionResult.Fail) {
                return; // something in the way. trying the off hand on the same spot just puts the wrong block there
            }
            // a bucket says success with the emptied bucket attached, which isn't == SUCCESS, so the off hand used to
            // fire right after every pour
            if (!ctx.player().getItemInHand(hand).isEmpty() && ctx.playerController().processRightClick(ctx.player(), ctx.world(), hand) instanceof InteractionResult.Success) {
                return;
            }
        }
    }

    // where a throwaway in this hand would land. BlockPlaceContext knows the replaceable rule (tall grass, snow), so
    // clicking a flower lands in the flower's cell and not the one past it
    private Pending scaffoldClick(InteractionHand hand, BlockHitResult hit) {
        if (listener == null) {
            return null;
        }
        ItemStack held = ctx.player().getItemInHand(hand);
        if (held.isEmpty() || !throwaway(held.getItem())) {
            return null;
        }
        BlockPos at = new BlockPlaceContext(new UseOnContext(ctx.world(), ctx.player(), hand, held, hit) {}).getClickedPos().immutable();
        Block block = ((BlockItem) held.getItem()).getBlock();
        if (ctx.world().getBlockState(at).is(block)) {
            return null; // already there, whatever happens next isn't ours
        }
        return new Pending(at, block);
    }

    // asked before the click like scaffoldClick, the stack can be gone after it
    private Traced traceClick(String who, InteractionHand hand, BlockHitResult hit) {
        if (who == null) {
            return null;
        }
        ItemStack held = ctx.player().getItemInHand(hand);
        if (held.isEmpty() || !(held.getItem() instanceof BlockItem)) {
            return null; // a door or a chest getting opened isn't a placement
        }
        if (!ctx.player().isSecondaryUseActive() && MovementHelper.opensWhenClicked(ctx.world().getBlockState(hit.getBlockPos()))) {
            return null; // unsneaked that's a use, the gate swings and nothing gets placed. same rule vanilla's click goes by
        }
        BlockPos at = new BlockPlaceContext(new UseOnContext(ctx.world(), ctx.player(), hand, held, hit) {}).getClickedPos().immutable();
        BlockState before = ctx.world().getBlockState(at);
        // every click, ring only. a stuck place clicks five times a second, the log file gets the ones that landed
        Trace.record("place at " + at.toShortString() + " by " + who + " want " + want.apply(at) + " held " + held.getItem() + " (was " + before + ")");
        return new Traced(at, before, who, generation.getAsInt());
    }

    // same patience as confirm, a click that never shows is worth a line too
    private void confirmTraced() {
        if (traced.isEmpty()) {
            return;
        }
        if (ctx.world() == null) {
            traced.clear();
            return;
        }
        Iterator<Traced> it = traced.iterator();
        while (it.hasNext()) {
            Traced t = it.next();
            BlockState now = ctx.world().getBlockState(t.pos);
            if (!now.equals(t.before)) {
                it.remove();
                // the log file too, not just the trace ring, so a play test log shows place/break pairs without a dump.
                // only while building (who is null otherwise), the same rule that keeps these out of everything else
                Debug.logInternal("placed " + now + " at " + t.pos.toShortString() + " by " + t.who + " want " + want.apply(t.pos));
                if (tracedListener != null) {
                    tracedListener.placed(t.pos, now, t.who, t.generation);
                }
            } else if (++t.age > CONFIRM_TICKS) {
                it.remove();
                Debug.logInternal("place at " + t.pos.toShortString() + " by " + t.who + " never showed up");
            }
        }
    }

    // the click going out isn't the block being there, a full cell or an entity in the way eats it
    private void confirm() {
        if (pending.isEmpty()) {
            return;
        }
        if (listener == null || ctx.world() == null) {
            pending.clear();
            return;
        }
        Iterator<Pending> it = pending.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            BlockState state = ctx.world().getBlockState(p.pos);
            if (state.is(p.block)) {
                it.remove();
                listener.placed(p.pos, state);
            } else if (++p.age > CONFIRM_TICKS) {
                it.remove();
            }
        }
    }
}
