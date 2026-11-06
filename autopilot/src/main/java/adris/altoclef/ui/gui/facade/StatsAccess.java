package adris.altoclef.ui.gui.facade;

import java.util.List;

// the fun numbers the stats tab shows. label is what the player reads ("Blocks mined"), value the already
// formatted number ("1 234" or "12.4 km"), so the game can format distances and times its own way and the tab
// only lays them out
public interface StatsAccess {

    record Stat(String label, String value) {
    }

    // one snapshot, in display order. empty when there is no world or no numbers to speak of
    List<Stat> snapshot();

    StatsAccess NONE = () -> List.of();
}
