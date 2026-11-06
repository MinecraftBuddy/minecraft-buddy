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

package baritone.pathing.timing;

import java.nio.file.Path;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

// where samples go between the game thread and the disk. the game thread only ever calls offer(): a lock free queue add
// and an atomic flag. the string building and the file append happen on one daemon thread, a few seconds after the
// first sample of a burst, so it is one write per burst instead of one per movement and nothing runs while idle
public final class TimingSink {

    private static final Map<Path, TimingSink> SHARED = new ConcurrentHashMap<>();
    private static final long FLUSH_DELAY_MS = 3000;
    // if the disk is somehow stuck we drop samples instead of eating memory
    private static final int MAX_QUEUED = 20_000;

    public static TimingSink shared(Path dir) {
        return SHARED.computeIfAbsent(dir.toAbsolutePath().normalize(), d -> {
            TimingSink sink = new TimingSink(new TimingFile(d, TimingFile.DEFAULT_CAP));
            // the last few seconds of a session would otherwise die with the jvm
            Runtime.getRuntime().addShutdownHook(new Thread(sink::flush, "soprano-timings-flush"));
            return sink;
        });
    }

    private final TimingFile file;
    private final Queue<TimingSample> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private volatile ScheduledExecutorService executor;

    public TimingSink(TimingFile file) {
        this.file = file;
    }

    public TimingFile file() {
        return file;
    }

    public void offer(TimingSample sample) {
        if (queued.incrementAndGet() > MAX_QUEUED) {
            queued.decrementAndGet();
            return;
        }
        queue.add(sample);
        if (flushScheduled.compareAndSet(false, true)) {
            executor().schedule(this::flush, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    private ScheduledExecutorService executor() {
        ScheduledExecutorService e = executor;
        if (e == null) {
            synchronized (this) {
                e = executor;
                if (e == null) {
                    e = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "soprano-timings");
                        t.setDaemon(true);
                        return t;
                    });
                    executor = e;
                }
            }
        }
        return e;
    }

    // drains whatever is queued into one append. safe from any thread, the command calls it before reading
    public synchronized void flush() {
        flushScheduled.set(false);
        StringBuilder out = new StringBuilder();
        TimingSample s;
        while ((s = queue.poll()) != null) {
            queued.decrementAndGet();
            s.appendCsv(out);
            out.append('\n');
        }
        file.append(out);
    }

    // clear also has to forget what is still waiting, or it would show up in the "empty" file a second later
    public synchronized void clear() {
        while (queue.poll() != null) {
            queued.decrementAndGet();
        }
        file.clear();
    }
}
