/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.movement;

import adris.altoclef.Debug;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

// costOfPlacingAt only stops the paths. plenty of placers never ask it (lava escape, the mlg, stations, shelters), so this
// says when a place click really goes out next to lava it could wake (LavaWake), and who sent it. every placer ends up in
// useItemOn, so that's where it listens. reads only, and clicks are rare enough that the lookups don't matter
public final class LavaWakeLog {

    private static final long INTERVAL_MS = 5000;
    // game thread only, useItemOn doesn't happen anywhere else
    private static long last = Long.MIN_VALUE;

    private LavaWakeLog() {}

    public static void click(LocalPlayer player, InteractionHand hand, BlockHitResult hit) {
        ItemStack held = player.getItemInHand(hand);
        if (!(held.getItem() instanceof BlockItem)) {
            return;
        }
        Level level = player.level();
        // same as BlockPlaceContext: clicking a replaceable block fills that block, anything else fills the face we hit
        BlockPos at = level.getBlockState(hit.getBlockPos()).canBeReplaced() ? hit.getBlockPos() : hit.getBlockPos().relative(hit.getDirection());
        if (!LavaWake.wakes((x, y, z) -> LavaWake.cell(level.getBlockState(new BlockPos(x, y, z))), at.getX(), at.getY(), at.getZ())) {
            return;
        }
        long now = System.currentTimeMillis();
        if (last != Long.MIN_VALUE && now - last < INTERVAL_MS) {
            return;
        }
        last = now;
        Debug.logInternal("lava wake: place click at " + at.toShortString() + " beside an exposed source, from " + placer() + ", holding " + held.getItem() + ", burning " + player.isOnFire() + ", feet " + player.blockPosition().toShortString());
    }

    // the first of our classes on the stack. the ones pressing the use key (InputControls) aren't on it anymore by the time
    // the game gets around to the click
    private static String placer() {
        return StackWalker.getInstance().walk(frames -> frames
                .map(StackWalker.StackFrame::getClassName)
                .filter(c -> (c.startsWith("adris.") || c.startsWith("baritone.")) && !c.endsWith("LavaWakeLog") && !c.endsWith("BaritonePlayerController"))
                .findFirst()
                .orElse("the use key"));
    }
}
