package baritone.utils.schematic.library;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

// one line of the schematic list. when the file didn't parse, error is set and the numbers are all zero
public record SchematicInfo(
    File file,
    String name,
    String relativePath,
    long fileSize,
    long lastModified,
    String format,
    int version,
    int sizeX,
    int sizeY,
    int sizeZ,
    long blockCount,
    List<UnknownBlock> unknownBlocks,
    String error
) {

    // a block id the parser couldn't find in the registry, and how many positions used it
    public record UnknownBlock(String id, int count) {
    }

    public boolean ok() {
        return error == null;
    }

    public long unknownTotal() {
        long total = 0;
        for (UnknownBlock block : unknownBlocks) {
            total += block.count();
        }
        return total;
    }

    // "12 blocks unrecognized: minecraft:foo (10), minecraft:bar (2)", or null when everything resolved
    public String unknownSummary() {
        if (unknownBlocks.isEmpty()) {
            return null;
        }
        long total = unknownTotal();
        String ids = unknownBlocks.stream().map(b -> b.id() + " (" + b.count() + ")").collect(Collectors.joining(", "));
        return total + (total == 1 ? " block" : " blocks") + " unrecognized: " + ids;
    }
}
