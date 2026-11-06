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

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static baritone.pathing.timing.TimingSampleTest.sample;
import static org.junit.Assert.*;

public class TimingFileTest {

    private static String line(int actual) {
        return sample("Traverse", 1, 0, 0, 4.6f, actual, 0, false, false) + "\n";
    }

    private static List<Integer> actuals(TimingFile file) {
        List<Integer> out = new ArrayList<>();
        file.read(s -> out.add(s.actual));
        return out;
    }

    // the sample ids of just the current file, no .old
    private static List<Integer> current(TimingFile file) throws Exception {
        List<Integer> out = new ArrayList<>();
        for (String l : Files.readAllLines(file.path())) {
            TimingSample s = TimingSample.parse(l);
            if (s != null) {
                out.add(s.actual);
            }
        }
        return out;
    }

    @Test
    public void firstWriteGetsAHeaderAndLaterOnesDont() throws Exception {
        Path dir = Files.createTempDirectory("timings");
        TimingFile file = new TimingFile(dir.resolve("deeper"), TimingFile.DEFAULT_CAP);
        assertTrue(file.append(line(5) + line(6)));
        assertTrue(file.append(line(7)));
        List<String> lines = Files.readAllLines(file.path());
        assertEquals(TimingSample.HEADER, lines.get(0));
        assertEquals(4, lines.size());
        assertEquals(List.of(5, 6, 7), actuals(file));
    }

    @Test
    public void fullFileRollsToOldAndStartsOver() throws Exception {
        Path dir = Files.createTempDirectory("timings");
        long oneLine = line(1).length();
        long cap = TimingSample.HEADER.length() + 1 + oneLine * 5;
        TimingFile file = new TimingFile(dir, cap);
        for (int i = 1; i <= 12; i++) {
            assertTrue(file.append(line(i)));
            assertTrue(Files.size(file.path()) <= cap);
        }
        Path old = dir.resolve(TimingFile.NAME + ".old");
        assertTrue(Files.exists(old));
        // 1..5 rolled once, then 6..9 rolled over it (the two digit 10 is a byte longer, so it doesn't fit as a
        // fifth line) and 10..12 are the current file: the .old is always just the one before
        assertEquals(List.of(6, 7, 8, 9, 10, 11, 12), actuals(file));
        assertEquals(List.of(10, 11, 12), current(file));
        assertEquals(TimingSample.HEADER, Files.readAllLines(file.path()).get(0));
        assertEquals(TimingSample.HEADER, Files.readAllLines(old).get(0));
        try (var files = Files.list(dir)) {
            assertEquals(2, files.count());
        }
    }

    @Test
    public void aBatchIsNeverSplitAcrossTheRoll() throws Exception {
        Path dir = Files.createTempDirectory("timings");
        long oneLine = line(1).length();
        TimingFile file = new TimingFile(dir, TimingSample.HEADER.length() + 1 + oneLine * 4);
        file.append(line(1) + line(2) + line(3));
        file.append(line(4) + line(5) + line(6));
        assertEquals(List.of(1, 2, 3, 4, 5, 6), actuals(file));
        assertEquals(List.of(4, 5, 6), current(file));
    }

    @Test
    public void clearDeletesBothAndMissingFilesReadEmpty() throws Exception {
        Path dir = Files.createTempDirectory("timings");
        TimingFile file = new TimingFile(dir, 200);
        assertTrue(actuals(file).isEmpty());
        for (int i = 0; i < 10; i++) {
            file.append(line(i));
        }
        assertTrue(Files.exists(dir.resolve(TimingFile.NAME + ".old")));
        file.clear();
        assertFalse(Files.exists(file.path()));
        assertFalse(Files.exists(dir.resolve(TimingFile.NAME + ".old")));
        assertTrue(actuals(file).isEmpty());
        file.clear(); // twice is fine
    }

    @Test
    public void aFolderThatCantBeWrittenIsAFalseNotAnException() throws Exception {
        Path dir = Files.createTempDirectory("timings");
        Path blocker = dir.resolve("file");
        Files.writeString(blocker, "x");
        // the "folder" is a file
        assertFalse(new TimingFile(blocker, TimingFile.DEFAULT_CAP).append(line(1)));
    }

    @Test
    public void sinkBatchesAndCanBeFlushedAndCleared() throws Exception {
        Path dir = Files.createTempDirectory("timings");
        TimingSink sink = new TimingSink(new TimingFile(dir, TimingFile.DEFAULT_CAP));
        for (int i = 0; i < 300; i++) {
            sink.offer(sample("Traverse", 1, 0, 0, 4.6f, i % 9, 0, false, false));
        }
        // nothing is on disk until the writer's turn (or someone asks)
        assertFalse(Files.exists(sink.file().path()));
        sink.flush();
        assertEquals(300, actuals(sink.file()).size());
        // lines are written in the order they were offered
        assertEquals(List.of(0, 1, 2), actuals(sink.file()).subList(0, 3));
        sink.offer(sample("Traverse", 1, 0, 0, 4.6f, 1, 0, false, false));
        sink.clear();
        sink.flush();
        assertFalse(Files.exists(sink.file().path()));
    }
}
