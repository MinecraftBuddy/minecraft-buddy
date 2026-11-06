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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

// the csv on disk: one current file and at most one .old, each capped, so the most this can ever take is twice the cap.
// every method is synchronized because the writer thread and the timings command both come here, and none of them
// ever throw, losing a few samples beats crashing the pathing thread over a read only folder
public final class TimingFile {

    public static final String NAME = "movement-timings.csv";
    // about 45 bytes a line, so this is something like 90k movements per file
    public static final long DEFAULT_CAP = 4L * 1024 * 1024;

    private final Path current;
    private final Path old;
    private final long cap;

    public TimingFile(Path dir, long cap) {
        this.current = dir.resolve(NAME);
        this.old = dir.resolve(NAME + ".old");
        this.cap = cap;
    }

    public Path path() {
        return current;
    }

    // lines is the whole batch, already newline terminated. a batch that wouldn't fit rolls the file first, so a batch
    // is never split across the two files
    public synchronized boolean append(CharSequence lines) {
        if (lines.length() == 0) {
            return true;
        }
        try {
            Files.createDirectories(current.getParent());
            long size = Files.exists(current) ? Files.size(current) : 0;
            byte[] body = lines.toString().getBytes(StandardCharsets.UTF_8);
            if (size > 0 && size + body.length > cap) {
                Files.move(current, old, StandardCopyOption.REPLACE_EXISTING);
                size = 0;
            }
            if (size == 0) {
                body = (TimingSample.HEADER + "\n" + lines).getBytes(StandardCharsets.UTF_8);
            }
            Files.write(current, body, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // oldest first. a bad line is skipped, a missing file is just empty
    public synchronized int read(Consumer<TimingSample> out) {
        return readOne(old, out) + readOne(current, out);
    }

    private static int readOne(Path file, Consumer<TimingSample> out) {
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        int count = 0;
        // InputStreamReader swaps bad bytes for U+FFFD where Files.newBufferedReader would throw halfway through
        try (BufferedReader in = new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                TimingSample sample = TimingSample.parse(line);
                if (sample != null) {
                    out.accept(sample);
                    count++;
                }
            }
        } catch (IOException | RuntimeException e) {
            // whatever we got before it broke is still good
        }
        return count;
    }

    public synchronized void clear() {
        try {
            Files.deleteIfExists(current);
            Files.deleteIfExists(old);
        } catch (IOException ignored) {
            // a file we can't delete is a file we also couldn't have been writing
        }
    }
}
