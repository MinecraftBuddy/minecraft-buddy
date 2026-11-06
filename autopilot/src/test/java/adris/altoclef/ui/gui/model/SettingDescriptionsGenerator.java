package adris.altoclef.ui.gui.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// turns the javadoc above every setting into one plain sentence-ish paragraph for the settings tab. lives under test
// sources because it only runs at dev time: the build checks the checked-in properties file is not stale, nothing at
// runtime parses java. run main to rewrite the file
public final class SettingDescriptionsGenerator {

    public record Parsed(String name, boolean javaOnly, String description) {
    }

    public static final String HEADER = "# generated from Settings.java javadoc by SettingDescriptionsGenerator, do not edit";

    private static final Pattern FIELD = Pattern.compile("^ {4}public\\s+final\\s+Setting<.+>\\s+(\\w+)\\s*=.*$");
    private static final Pattern INLINE = Pattern.compile("\\{@(\\w+)\\s*([^}]*)}");
    private static final Pattern HTML_TAG = Pattern.compile("</?[a-zA-Z][^>]*>");
    private static final char OPEN = (char) 0xE000;
    private static final char CLOSE = (char) 0xE001;
    private static final char PARA = (char) 0xE002;

    private SettingDescriptionsGenerator() {
    }

    public static Path settingsJava() {
        String override = System.getProperty("settings.java");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get("src", "api", "java", "baritone", "api", "Settings.java");
    }

    public static Path resource() {
        return Paths.get("src", "main", "resources", "adris", "altoclef", "ui", "gui", "settings-descriptions.properties");
    }

    public static String generate() throws IOException {
        return render(parse(Files.readString(settingsJava(), StandardCharsets.UTF_8)));
    }

    public static void main(String[] args) throws IOException {
        String text = generate();
        Path out = resource();
        Files.createDirectories(out.getParent());
        Files.write(out, text.getBytes(StandardCharsets.US_ASCII));
        System.out.println("wrote " + out + " (" + text.lines().count() + " lines)");
    }

    // every public final Setting<...> field at class level, in file order, with the javadoc right above it. an
    // annotation or a // comment between the two is fine, any other code in between means the javadoc was not theirs
    public static List<Parsed> parse(String source) {
        List<Parsed> out = new ArrayList<>();
        String[] lines = source.split("\r?\n", -1);
        List<String> doc = null;
        boolean inDoc = false;
        boolean javaOnly = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (inDoc) {
                int end = line.indexOf("*/");
                doc.add(starless(end >= 0 ? line.substring(0, end) : line));
                if (end >= 0) {
                    inDoc = false;
                }
                continue;
            }
            if (line.startsWith("/**")) {
                doc = new ArrayList<>();
                String rest = line.substring(3);
                int end = rest.indexOf("*/");
                doc.add(starless(end >= 0 ? rest.substring(0, end) : rest));
                inDoc = end < 0;
                javaOnly = false;
                continue;
            }
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            if (line.startsWith("@")) {
                if (line.startsWith("@JavaOnly")) {
                    javaOnly = true;
                }
                continue;
            }
            Matcher m = FIELD.matcher(raw);
            if (m.matches()) {
                out.add(new Parsed(m.group(1), javaOnly, doc == null ? "" : clean(doc)));
            }
            doc = null;
            javaOnly = false;
        }
        return out;
    }

    private static String starless(String line) {
        String t = line.strip();
        while (t.startsWith("*")) {
            t = t.substring(1);
        }
        return t.strip();
    }

    // javadoc lines to one line of plain text. a block tag (@param, @see...) ends the description
    static String clean(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            if (l.startsWith("@")) {
                break;
            }
            sb.append(l).append(' ');
        }
        String text = sb.toString().replaceAll("(?i)<p\\s*/?>", "" + PARA);
        // code and link text goes into slots first, or the html pass below would eat the <Block> in List<Block>
        List<String> slots = new ArrayList<>();
        Matcher m = INLINE.matcher(text);
        StringBuilder replaced = new StringBuilder();
        while (m.find()) {
            slots.add(inline(m.group(1), m.group(2).trim()));
            m.appendReplacement(replaced, Matcher.quoteReplacement("" + OPEN + (slots.size() - 1) + CLOSE));
        }
        m.appendTail(replaced);
        // block tags are a break, inline ones (b, i, em) vanish without leaving a gap before the colon after them
        text = HTML_TAG.matcher(replaced).replaceAll(t -> t.group().matches("(?i)</?(p|br|li|ul|ol|div|pre|h\\d|tr|td|table)\\b.*") ? " " : "");
        text = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&nbsp;", " ").replace("&amp;", "&");
        for (int i = 0; i < slots.size(); i++) {
            text = text.replace("" + OPEN + i + CLOSE, slots.get(i));
        }
        text = text.replaceAll("\\s+", " ");
        // one paragraph, but a sentence that ran into the next one without a full stop gets one
        text = text.replaceAll("([^.!?:;,\\s" + PARA + "]) ?" + PARA + " ?", "$1. ").replaceAll(" ?" + PARA + " ?", " ");
        return soprano(text.replaceAll("\\s+", " ").trim());
    }

    // the gui says soprano, never altoclef. a file path keeps its real name though: baritone/altoclef/butler.json
    // renamed to soprano would send someone looking for a folder that does not exist
    static String soprano(String text) {
        StringBuilder sb = new StringBuilder();
        for (String word : text.split(" ", -1)) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(word.indexOf('/') >= 0 ? word : Wording.soprano(word));
        }
        return sb.toString();
    }

    private static String inline(String tag, String arg) {
        switch (tag) {
            case "link", "linkplain" -> {
                String[] parts = arg.split("\\s+", 2);
                if (parts.length == 2) {
                    return parts[1];
                }
                String target = parts[0];
                int hash = target.lastIndexOf('#');
                if (hash >= 0) {
                    target = target.substring(hash + 1);
                }
                int paren = target.indexOf('(');
                return paren >= 0 ? target.substring(0, paren) : target;
            }
            default -> {
                // code, literal, value: the text itself
                return arg;
            }
        }
    }

    // name=description, sorted, javadoc-only fields left out, escaped the way Properties.load wants it back
    public static String render(List<Parsed> parsed) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Parsed p : parsed) {
            if (!p.javaOnly()) {
                sorted.put(p.name(), p.description());
            }
        }
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (var e : sorted.entrySet()) {
            sb.append(escape(e.getKey(), true)).append('=').append(escape(e.getValue(), false)).append('\n');
        }
        return sb.toString();
    }

    // Properties.store's rules minus the unicode surprises: everything outside printable ascii becomes \\uXXXX so the
    // file is plain ascii whatever editor or reader touches it
    static String escape(String s, boolean key) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\f' -> sb.append("\\f");
                case '=', ':', '#', '!' -> sb.append('\\').append(c);
                case ' ' -> sb.append(key || i == 0 ? "\\ " : " ");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        sb.append(String.format("\\u%04X", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
