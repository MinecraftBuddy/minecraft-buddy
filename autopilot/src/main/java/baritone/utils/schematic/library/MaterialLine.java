package baritone.utils.schematic.library;

// one row of a material list. item is an item id for ITEM rows, and a block id for the other two kinds (there is no
// item to point at). note is empty when there's nothing worth saying
public record MaterialLine(String item, int count, Kind kind, String note) {

    public enum Kind {
        // an item the player has to hold
        ITEM,
        // a block with no item at all (farmland, fire, ...), so it can't be placed
        CANT_PLACE,
        // counted for information only (fluid sources)
        NOT_COUNTED
    }
}
