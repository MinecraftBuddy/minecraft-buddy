package adris.altoclef.trackers;

// note is the seam under the block reads, this lets a test in the explore package feed it
public final class LandMemoryTestAccess {

    private LandMemoryTestAccess() {
    }

    public static void note(LandMemory memory, int x, int z, int waterCols, int total) {
        memory.note(x, z, waterCols, total);
    }
}
