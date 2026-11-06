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

package baritone.altoclef;

import adris.altoclef.AltoSettings;
import adris.altoclef.AltoClef;
import baritone.Baritone;
import baritone.api.Settings;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// what AltoClef.initializeBaritoneSettings used to do once at startup, forever. that turned parkour off for
// everybody, so now it is applied when a task starts and every value gets put back when the task stops. the values
// themselves live in SettingsOverrides (the one place that remembers what the user had, and what the save swaps back)
public final class BaritoneSettingsScope {

    private final AltoClef mod;
    private final List<Runnable> undo = new ArrayList<>();
    private boolean applied;
    private List<Item> savedThrowaway;
    // the altoThrowawayItems list the merged baritone list was built from, to notice a #set of it
    private List<Item> mergedFrom;
    private List<Item> lastMerged;

    public BaritoneSettingsScope(AltoClef mod) {
        this.mod = mod;
    }

    // safe to call again while applied (a settings reload does), it only refreshes the throwaway list then
    public void apply() {
        Settings s = Baritone.settings();
        if (!applied) {
            applied = true;
            AltoClefSettings extra = AltoClefSettings.getInstance();

            boolean walkOnEndPortal = extra.isCanWalkOnEndPortal();
            undo.add(() -> extra.canWalkOnEndPortal(walkOnEndPortal));
            extra.canWalkOnEndPortal(false);

            // frames are not walkable/breakable in stock soprano, only while we are driving
            boolean frameRules = extra.hasEndPortalFrameRules();
            undo.add(() -> extra.endPortalFrameRules(frameRules));
            extra.endPortalFrameRules(true);

            // freeLook stays whatever the user picked. altoclef aims its own clicks, it never needed this
            set(s.overshootTraverse, false);
            set(s.allowOvershootDiagonalDescend, true);
            set(s.allowInventory, true);
            set(s.allowParkour, false);
            set(s.allowParkourAscend, false);
            set(s.allowParkourPlace, false);
            set(s.allowDiagonalDescend, false);
            set(s.allowDiagonalAscend, false);
            // digging the block under our feet is the only move that goes down through rock. with it off an ore far below is a
            // search that eats the whole failure timeout, and the mining task then bans ore after ore of the same vein for it
            set(s.allowDownward, true);
            set(s.blocksToAvoid, blocksToAvoid());
            // fadePath stays whatever the user picked. forcing it on cut the path off 20 blocks out, for a few fps nobody asked for
            // Don't let baritone scan dropped items, we handle that ourselves.
            set(s.mineScanDroppedItems, false);
            // Don't let baritone wait for drops, we handle that ourselves.
            set(s.mineDropLoiterDurationMSThanksLouca, 0L);

            // Water bucket placement will be handled by us exclusively
            boolean placeBucketButDontFall = extra.shouldNotPlaceBucketButStillFall();
            undo.add(() -> extra.configurePlaceBucketButDontFall(placeBucketButDontFall));
            extra.configurePlaceBucketButDontFall(true);

            // For render smoothing
            set(s.randomLooking, 0.0);
            set(s.randomLooking113, 0.0);

            // Give baritone more time to calculate paths. Sometimes they can be really far away.
            // Was: 2000L
            set(s.failureTimeoutMS, s.failureTimeoutMS.defaultValue);
            // Was: 5000L
            set(s.planAheadFailureTimeoutMS, s.planAheadFailureTimeoutMS.defaultValue);
            // Was 100
            set(s.movementTimeoutTicks, s.movementTimeoutTicks.defaultValue);

            savedThrowaway = s.acceptableThrowawayItems.value;
        }

        // Baritone's `acceptableThrowawayItems` should match our own. A fresh list every time: the setting's own list
        // is also its default, adding to it in place would poison reset
        // somebody #set baritone's own list while we were applied: that is the list to merge into and to give back
        if (lastMerged != null && s.acceptableThrowawayItems.value != lastMerged) {
            savedThrowaway = s.acceptableThrowawayItems.value;
        }
        mergedFrom = s.altoThrowawayItems.value;
        List<Item> baritoneCanPlace = Arrays.stream(AltoSettings.getThrowawayItems(mod, true))
                .filter(item -> item != Items.SOUL_SAND && item != Items.MAGMA_BLOCK && item != Items.SAND && item
                        != Items.GRAVEL).toList();
        // the user's list first, then ours, each item once (a doubled entry doubles every count made from the list)
        List<Item> merged = mergeUnique(savedThrowaway, baritoneCanPlace);
        // the registry keeps the user's list from the first put, so a second apply only swaps what we hand baritone
        set(s.acceptableThrowawayItems, merged);
        lastMerged = merged;
    }

    // nothing climbable goes on this list. the planner reads isClimbable and happily plans the climb, canWalkThrough reads
    // this list and says no, and Movement.prepared swings at whatever fails canWalkThrough. so we'd cut down our own ladder
    // and then wonder where it went. cave vines stay, you can't climb those, they only look like it
    // a method and not a field: ThrowawayMergeTest touches this class without booting the game, Blocks would blow up in there
    static List<Block> blocksToAvoid() {
        return List.of(Blocks.FLOWERING_AZALEA, Blocks.AZALEA,
                Blocks.POWDER_SNOW, Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM, Blocks.CAVE_VINES,
                Blocks.CAVE_VINES_PLANT, Blocks.SWEET_BERRY_BUSH,
                Blocks.WARPED_ROOTS, Blocks.FERN, Blocks.TALL_GRASS, Blocks.LARGE_FERN,
                Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD,
                Blocks.AMETHYST_CLUSTER, Blocks.SCULK, Blocks.SCULK_VEIN, Blocks.SUNFLOWER, Blocks.LILAC,
                Blocks.ROSE_BUSH, Blocks.PEONY);
    }

    static <T> List<T> mergeUnique(List<T> first, List<T> second) {
        java.util.Set<T> unique = new java.util.LinkedHashSet<>(first);
        unique.addAll(second);
        return new ArrayList<>(unique);
    }

    // a #set of altoThrowawayItems while a task runs has to reach baritone's list as well, one reference compare a tick
    public void refreshThrowaway() {
        if (applied && mergedFrom != Baritone.settings().altoThrowawayItems.value) {
            apply();
        }
    }

    // task over, or altoclef is giving up: the user's values come back (all of them, BotBehaviour's too, they live in
    // the same registry) and every toggle goes back to what it was. runs every undo even if one of them throws
    public void restore() {
        if (!applied) {
            // nothing of ours should be held, but a half-finished apply may have gotten as far as a few settings
            SettingsOverrides.restoreAll();
            return;
        }
        applied = false;
        try {
            SettingsOverrides.restoreAll();
        } finally {
            savedThrowaway = null;
            mergedFrom = null;
            lastMerged = null;
            for (int i = undo.size() - 1; i >= 0; i--) {
                try {
                    undo.get(i).run();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
            undo.clear();
            // chains pause baritone's clicking while they eat and so on, a stop in the middle of that must not leave it paused
            AltoClefSettings.getInstance().setInteractionPaused(false);
        }
    }

    private <T> void set(Settings.Setting<T> setting, T value) {
        SettingsOverrides.put(setting, value);
    }
}
