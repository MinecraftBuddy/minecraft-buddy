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

package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.pathing.timing.TimingSink;
import baritone.pathing.timing.TimingStats;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class TimingsCommand extends Command {

    public TimingsCommand(IBaritone baritone) {
        super(baritone, "timings");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(1);
        boolean clear = false;
        int minSamples = TimingStats.DEFAULT_MIN_SAMPLES;
        if (args.hasAny()) {
            String arg = args.getString();
            if (arg.equalsIgnoreCase("clear")) {
                clear = true;
            } else {
                try {
                    minSamples = Math.max(1, Integer.parseInt(arg));
                } catch (NumberFormatException e) {
                    throw new CommandInvalidStateException("expected clear or a minimum sample count, not " + arg);
                }
            }
        }
        TimingSink sink = TimingSink.shared(((Baritone) baritone).getDirectory());
        final boolean wipe = clear;
        final int min = minSamples;
        // the file can be a few megabytes and the sink may be mid write, so the game thread doesn't wait for either
        // (clear included). logDirect hops back by itself
        Thread reader = new Thread(() -> {
            if (wipe) {
                sink.clear();
                logDirect("movement timings cleared");
                return;
            }
            sink.flush();
            TimingStats stats = new TimingStats();
            sink.file().read(stats::add);
            if (!Baritone.settings().recordMovementTimings.value) {
                logDirect("recordMovementTimings is off, this is only what was recorded before");
            }
            TimingStats.format(stats, min, TimingStats.DEFAULT_TOP).forEach(this::logDirect);
        }, "soprano-timings-report");
        reader.setDaemon(true);
        reader.start();
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasAtMost(1)) {
            return Stream.of("clear");
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Show which movements take longer than planned";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Reads the movement timings that recordMovementTimings collects and prints the movements whose planned time is furthest off.",
                "Each row is one kind of movement and offset, split by whether it broke blocks, was in water, or was already sprinting when it started: how many times it ran, the mean planned and real ticks, real over planned, the 10th to 90th percentile of the real ticks, and how many ticks off it is in total.",
                "The worst 15 are shown, and groups with fewer than 8 samples are left out unless you give a different minimum. 'rechecked n' means n of the samples cost something else when they started than when they were planned (the world changed, or the plan reused an older path), and those are measured against the price at the start.",
                "Only movements that ran from start to finish without a pause are counted, so the ones the sprint logic skips over (a descend flowing straight into the next movement, say) are missing and the fast cases of those kinds are under-represented.",
                "This only measures, nothing changes how paths are planned.",
                "",
                "Usage:",
                "> timings - Print the report.",
                "> timings <min samples> - Print the report, hiding groups with fewer samples than that.",
                "> timings clear - Delete all the recorded timings."
        );
    }
}
