package adris.altoclef.butler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

// edits the plain text list files in place. the file is the owner's, so comments and anything else they wrote stay
// exactly where they were, we only add a line at the end or take a name's line out
public final class WhitelistEditor {

    // what a server will let through the door: ascii without spaces, 16 long at most. # is left out on purpose, it would
    // turn the rest of the line into a comment and the name into nothing
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.]{1,16}");

    public enum Outcome {
        ADDED, ALREADY_THERE, REMOVED, NOT_THERE
    }

    public record Edit(List<String> lines, Outcome outcome) {
    }

    private WhitelistEditor() {
    }

    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    // the name a line stands for, null for comments and blanks. same cut as ConfigHelper's list reader
    static String entryOf(String line) {
        int pound = line.indexOf('#');
        String entry = (pound == -1 ? line : line.substring(0, pound)).trim();
        return entry.isEmpty() ? null : entry;
    }

    public static List<String> names(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            String entry = entryOf(line);
            if (entry != null) {
                out.add(entry);
            }
        }
        return out;
    }

    public static Edit add(List<String> lines, String name) {
        for (String line : lines) {
            String entry = entryOf(line);
            if (entry != null && entry.equalsIgnoreCase(name)) {
                return new Edit(lines, Outcome.ALREADY_THERE);
            }
        }
        List<String> out = new ArrayList<>(lines);
        out.add(name);
        return new Edit(out, Outcome.ADDED);
    }

    // a line that carries the name goes whole, trailing comment and all: "bob # my friend" is about bob
    public static Edit remove(List<String> lines, String name) {
        List<String> out = new ArrayList<>();
        boolean found = false;
        for (String line : lines) {
            String entry = entryOf(line);
            if (entry != null && entry.equalsIgnoreCase(name)) {
                found = true;
            } else {
                out.add(line);
            }
        }
        return new Edit(found ? out : lines, found ? Outcome.REMOVED : Outcome.NOT_THERE);
    }

    public static List<String> read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    public static void write(Path file, List<String> lines) throws IOException {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.write(file, text.toString().getBytes(StandardCharsets.UTF_8));
    }

    // reads, edits and writes back. nothing is written when nothing changed, so a duplicate add can't touch the file
    public static Outcome apply(Path file, String name, boolean adding) throws IOException {
        List<String> lines = read(file);
        Edit edit = adding ? add(lines, name) : remove(lines, name);
        if (edit.outcome() == Outcome.ADDED || edit.outcome() == Outcome.REMOVED) {
            write(file, edit.lines());
        }
        return edit.outcome();
    }
}
