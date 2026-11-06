package adris.altoclef.ui.gui.model;

import java.util.ArrayList;
import java.util.List;

// up arrow in the command line walks back through what you ran, down walks forward and ends on whatever you had half
// typed. same as a terminal, minus the part where it saves to a file
public final class HelpHistory {

    public static final int MAX = 20;

    private final List<String> lines = new ArrayList<>();
    private int cursor;
    private String draft = "";

    public int size() {
        return lines.size();
    }

    public List<String> lines() {
        return List.copyOf(lines);
    }

    // a repeat of the last line is not worth a slot
    public void add(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        if (lines.isEmpty() || !lines.get(lines.size() - 1).equals(line)) {
            lines.add(line);
            if (lines.size() > MAX) {
                lines.remove(0);
            }
        }
        reset();
    }

    public void reset() {
        cursor = lines.size();
        draft = "";
    }

    // current is what the field holds right now, remembered the first time you step off the bottom
    public String back(String current) {
        if (lines.isEmpty()) {
            return current;
        }
        if (cursor >= lines.size()) {
            draft = current == null ? "" : current;
            cursor = lines.size();
        }
        cursor = Math.max(0, cursor - 1);
        return lines.get(cursor);
    }

    public String forward(String current) {
        if (cursor >= lines.size()) {
            return current;
        }
        cursor++;
        return cursor >= lines.size() ? draft : lines.get(cursor);
    }

    public boolean browsing() {
        return cursor < lines.size();
    }
}
