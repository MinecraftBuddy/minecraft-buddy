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
import baritone.api.BaritoneAPI;
import baritone.api.utils.IPlayerContext;
import baritone.behavior.LegacyCombat;
import baritone.utils.accessor.IPlayerControllerMP;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;
import java.util.function.Function;

/**
 * @author Brady
 * @since 8/25/2018
 */
public final class BlockBreakHelper {
    // base ticks between block breaks caused by tick logic
    private static final int BASE_BREAK_DELAY = 1;

    private final IPlayerContext ctx;
    private boolean wasHitting;
    private int breakDelayTimer = 0;
    // what the build wants at a cell, same as BlockPlaceHelper's. null answers are air or outside the file
    private Function<BlockPos, BlockState> want = pos -> null;
    private BreakListener breakListener;

    BlockBreakHelper(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    // a traced break landed (the builder is running and somebody's click took a block out). game thread
    public interface BreakListener {
        void broke(BlockPos pos, BlockState before, String who);
    }

    void setWant(Function<BlockPos, BlockState> want) {
        this.want = want;
    }

    void setBreakListener(BreakListener breakListener) {
        this.breakListener = breakListener;
    }

    // the twin of the place line. a movement's dig and the builder's both land here, so a place then break of the same
    // cell shows up as a pair in the log file without waiting for a dump. who is only set while building
    private void traceBroke(String who, BlockPos pos, BlockState before) {
        if (who == null) {
            return;
        }
        Debug.logInternal("break at " + pos.toShortString() + " by " + who + " was " + before + " want " + want.apply(pos) + " held " + ctx.player().getMainHandItem().getItem());
        if (breakListener != null) {
            breakListener.broke(pos, before, who);
        }
    }

    public void stopBreakingBlock() {
        // The player controller will never be null, but the player can be
        if (ctx.player() != null && wasHitting) {
            ctx.playerController().setHittingBlock(false);
            ctx.playerController().resetBlockRemoving();
            wasHitting = false;
        }
    }

    // type only, on purpose. the executor breaks plenty of blocks the planner already ok'd with its fancier rules
    // (next to water and so on), only the never break list is a hard no at click time
    static boolean neverBreak(BlockState aimed, List<Block> disallowed) {
        return disallowed.contains(aimed.getBlock());
    }

    private void refuse() {
        // stopDestroyBlock does nothing unless it thinks we're mid break, and we told it we aren't at the end of last
        // tick. so say yes for a moment and let it send the abort, no half cracked log turning into a half cracked nest
        if (ctx.player() != null && wasHitting) {
            ctx.playerController().setHittingBlock(true);
            ctx.playerController().resetBlockRemoving();
        }
        ctx.playerController().setHittingBlock(false);
        wasHitting = false;
    }

    public void tick(boolean isLeftClick) {
        tick(isLeftClick, null);
    }

    // who is the trace's name for the click, null for no trace
    public void tick(boolean isLeftClick, String who) {
        if (breakDelayTimer > 0) {
            breakDelayTimer--;
            return;
        }
        HitResult trace = ctx.objectMouseOver();
        boolean isBlockTrace = trace != null && trace.getType() == HitResult.Type.BLOCK;

        if (isLeftClick && isBlockTrace && neverBreak(ctx.world().getBlockState(((BlockHitResult) trace).getBlockPos()), BaritoneAPI.getSettings().blocksToDisallowBreaking.value)) {
            // a few callers just hold the button and break whatever the crosshair lands on, plan or no plan. so ask every
            // tick, not just on the first click: the aim can slide off a log onto the nest mid swing
            refuse();
            return;
        }
        if (isLeftClick && isBlockTrace) {
            ctx.playerController().setHittingBlock(wasHitting);
            if (ctx.playerController().hasBrokenBlock()) {
                BlockPos pos = ((BlockHitResult) trace).getBlockPos();
                BlockState before = who == null ? null : ctx.world().getBlockState(pos);
                ctx.playerController().syncHeldItem();
                ctx.playerController().clickBlock(pos, ((BlockHitResult) trace).getDirection());
                LegacyCombat.swing(ctx.player(), InteractionHand.MAIN_HAND);
                // an insta mine (creative, or a good enough tool) is gone on the first click and never goes through the
                // damage branch below, which is where scaffold usually dies
                if (before != null && !before.isAir() && ctx.playerController().hasBrokenBlock() && !ctx.world().getBlockState(pos).equals(before)) {
                    traceBroke(who, pos, before);
                }
            } else {
                BlockState before = who == null ? null : ctx.world().getBlockState(((BlockHitResult) trace).getBlockPos());
                if (ctx.playerController().onPlayerDamageBlock(((BlockHitResult) trace).getBlockPos(), ((BlockHitResult) trace).getDirection())) {
                    LegacyCombat.swing(ctx.player(), InteractionHand.MAIN_HAND);
                }
                if (ctx.playerController().hasBrokenBlock()) { // block broken this tick
                    traceBroke(who, ((BlockHitResult) trace).getBlockPos(), before);
                    // break delay timer only applies for multi-tick block breaks like vanilla
                    breakDelayTimer = BaritoneAPI.getSettings().blockBreakSpeed.value - BASE_BREAK_DELAY;
                    // must reset controller's destroy delay to prevent the client from delaying itself unnecessarily
                    ((IPlayerControllerMP) ctx.minecraft().gameMode).setDestroyDelay(0);
                }
            }
            // if true, we're breaking a block. if false, we broke the block this tick
            wasHitting = !ctx.playerController().hasBrokenBlock();
            // this value will be reset by the MC client handling mouse keys
            // since we're not spoofing the click keybind to the client, the client will stop the break if isDestroyingBlock is true
            // we store and restore this value on the next tick to determine if we're breaking a block
            ctx.playerController().setHittingBlock(false);
        } else {
            wasHitting = false;
        }
    }
}
