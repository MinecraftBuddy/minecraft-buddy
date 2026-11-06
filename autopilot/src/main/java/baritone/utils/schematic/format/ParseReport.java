package baritone.utils.schematic.format;

import java.util.Map;

// what a parser noticed while reading, for the schematic library. the parsers still turn every unknown block into air
// (same as always), this is just so somebody can find out it happened
public interface ParseReport {

    // block id (as written in the file) to how many positions used it. empty when everything resolved
    Map<String, Integer> unknownBlocks();

    // the format's own version number, 0 when the format has none (mcedit)
    int formatVersion();
}
