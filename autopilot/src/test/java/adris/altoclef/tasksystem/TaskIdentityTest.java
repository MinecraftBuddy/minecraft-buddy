package adris.altoclef.tasksystem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

// the task system keeps the running task when the new one isEqual to it. a class that takes constructor args but only
// checks `instanceof` there silently eats every request with different args. this reads the sources (no classpath
// scanning library in tests) and makes you say out loud when that is on purpose
public class TaskIdentityTest {
    private static final Path SRC = Path.of("src/main/java/adris/altoclef");

    // class name -> why class only equality is right. every entry has to still be a hit, so dead ones get cleaned up
    private static final Map<String, String> ALLOWED = new TreeMap<>();

    static {
        // the gamer's ctx/logger taking tasks: the ctx is plumbing, the same run keeps the same ctx
        ALLOWED.put("LocateStrongholdTask", "takes the gamer ctx, plumbing");
        ALLOWED.put("StrongholdRoomSearchTask", "takes the gamer ctx, plumbing");
        ALLOWED.put("NetherRecoverTask", "takes the gamer ctx, plumbing");
        ALLOWED.put("LavaPoolPortalTask", "takes the gamer ctx, plumbing");
        ALLOWED.put("LPortalTask", "the rejected lakes list is plumbing from the phase, the same run keeps the same list");
        ALLOWED.put("GamerTask", "startAt is ignored on purpose, #gamer while one runs is a no-op");
        ALLOWED.put("KillEnderDragonWithBedsTask", "holds a freshly built waiter task, not a request");
        // the lookTimer is state, not identity
        ALLOWED.put("SafeRandomShimmyTask", "randomLookInterval only seeds a timer, every caller wants the shimmy that is already running");
        // the terminator's own helpers, the terminator is a one off that nobody restarts with new args
        ALLOWED.put("TerminatorTask", "center/radius/predicate are set once by the command, callers never vary");
        ALLOWED.put("RunAwayFromPlayersTask", "rebuilt every scared tick for whoever is closest, so it keeps fleeing the first one instead of restarting when the closest changes");
    }

    private record Hit(String file, String className) {
    }

    // ---- the guard ----

    @Test
    public void classOnlyEqualityWithConstructorArgsIsOnTheAllowlist() throws IOException {
        List<Hit> hits = scanTree();
        Map<String, Hit> byName = new HashMap<>();
        for (Hit h : hits) {
            byName.put(h.className(), h);
        }
        // a scan that finds nothing would pass forever, so make sure it can still see the ones we know about
        assertTrue("scan found nothing, wrong working directory or the parser went blind: " + hits, byName.containsKey("GamerTask"));

        StringBuilder bad = new StringBuilder();
        for (Hit h : hits) {
            if (!ALLOWED.containsKey(h.className())) {
                bad.append("\n  ").append(h.file()).append(": ").append(h.className())
                        .append(" isEqual is only an instanceof check but the constructor takes args");
            }
        }
        for (String name : ALLOWED.keySet()) {
            if (!byName.containsKey(name)) {
                bad.append("\n  allowlist entry ").append(name).append(" is no longer a hit, remove it");
            }
        }
        if (bad.length() > 0) {
            throw new AssertionError("compare the constructor args in isEqual, or add it to the allowlist with a reason:" + bad);
        }
    }

    // ---- the scanner, checked on tiny sources so it can't rot unnoticed ----

    @Test
    public void flagsAClassCheckWithConstructorArgs() {
        assertEquals(List.of("Foo"), names(scan("""
                class Foo extends Task {
                    Foo(int x) { }
                    @Override
                    protected boolean isEqual(Task other) {
                        return other instanceof Foo;
                    }
                }
                """)));
    }

    @Test
    public void flagsTheIfShapeAndPatternVariable() {
        assertEquals(List.of("A", "B"), names(scan("""
                class A extends Task {
                    public A(String s) throws Exception { }
                    protected boolean isEqual(Task other) {
                        if (other instanceof A) {
                            return true;
                        }
                        return false;
                    }
                }
                class B extends Task {
                    B(int x) { }
                    protected boolean isEqual(Task t) { return t instanceof B b; }
                }
                """)));
    }

    @Test
    public void leavesClassesThatCompareArgsOrTakeNone() {
        assertEquals(List.of(), names(scan("""
                class Compares extends Task {
                    Compares(int x) { }
                    protected boolean isEqual(Task other) {
                        return other instanceof Compares c && c.x == x;
                    }
                }
                class NoArgs extends Task {
                    NoArgs() { }
                    protected boolean isEqual(Task other) {
                        return other instanceof NoArgs;
                    }
                }
                class NoCtor extends Task {
                    protected boolean isEqual(Task other) {
                        return other instanceof NoCtor;
                    }
                }
                """)));
    }

    @Test
    public void multiClassFilesAttributeToTheClassThatOwnsTheMethod() {
        // Outer takes args and compares them, Inner takes none and does not. neither is a hit, and Inner's constructor
        // must not be mistaken for Outer's or the other way round
        assertEquals(List.of(), names(scan("""
                class Outer extends Task {
                    Outer(int x) { }
                    protected boolean isEqual(Task other) {
                        return other instanceof Outer o && o.x == x;
                    }
                    private class Inner extends Task {
                        Inner() { }
                        protected boolean isEqual(Task other) {
                            return other instanceof Inner;
                        }
                    }
                }
                """)));
        // and the reverse: the nested one has the args
        assertEquals(List.of("Inner"), names(scan("""
                class Outer extends Task {
                    Outer() { }
                    protected boolean isEqual(Task other) {
                        return other instanceof Outer;
                    }
                    private class Inner extends Task {
                        Inner(int y) { }
                        protected boolean isEqual(Task other) {
                            return other instanceof Inner;
                        }
                    }
                }
                """)));
    }

    @Test
    public void seesQualifiedAndFinalTaskParams() {
        assertEquals(List.of("Q", "F"), names(scan("""
                class Q extends Task {
                    Q(int x) { }
                    protected boolean isEqual(adris.altoclef.tasksystem.Task other) { return other instanceof Q; }
                }
                class F extends Task {
                    F(int x) { }
                    protected boolean isEqual(final Task other) { return other instanceof F; }
                }
                """)));
    }

    @Test
    public void ignoresAnonymousTasksCommentsAndStrings() {
        assertEquals(List.of(), names(scan("""
                class Host {
                    Host(int x) { }
                    Task make() {
                        return new Task() {
                            protected boolean isEqual(Task other) {
                                return other instanceof Task;
                            }
                        };
                    }
                    // protected boolean isEqual(Task other) { return other instanceof Host; }
                    String s = "boolean isEqual(Task other) { return other instanceof Host; }";
                }
                """)));
    }

    // ---- the scanner ----

    private static List<String> names(List<Hit> hits) {
        return hits.stream().map(Hit::className).toList();
    }

    private static List<Hit> scanTree() throws IOException {
        List<Hit> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                for (Hit h : scan(Files.readString(file))) {
                    hits.add(new Hit(file.toString().replace('\\', '/'), h.className()));
                }
            }
        }
        return hits;
    }

    private record Frame(String className, int start, int end) {
    }

    private static final Pattern CLASS_DECL = Pattern.compile("(?<![.\\w])(?:class|enum|interface|record)\\s+(\\w+)");
    private static final Pattern IS_EQUAL = Pattern.compile("boolean\\s+isEqual\\s*\\(\\s*(?:final\\s+|@\\w+\\s+)*(?:[\\w.]+\\.)?Task\\s+(\\w+)\\s*\\)\\s*\\{");

    static List<Hit> scan(String source) {
        String code = blank(source);

        // the first { after "class Foo" is its body (extends/implements/generics never have braces)
        Map<Integer, String> classBraces = new HashMap<>();
        Matcher decl = CLASS_DECL.matcher(code);
        while (decl.find()) {
            int brace = code.indexOf('{', decl.end());
            if (brace >= 0) {
                classBraces.put(brace, decl.group(1));
            }
        }

        // every brace pair is a frame, only the ones that are class bodies have a name. methods, lambdas and anonymous
        // classes are nameless, so a method that lives in one of those is not attributed to the class around it
        List<Frame> frames = new ArrayList<>();
        Deque<int[]> open = new ArrayDeque<>();
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                open.push(new int[]{i});
            } else if (c == '}' && !open.isEmpty()) {
                int start = open.pop()[0];
                frames.add(new Frame(classBraces.get(start), start, i));
            }
        }

        List<Hit> hits = new ArrayList<>();
        Matcher m = IS_EQUAL.matcher(code);
        while (m.find()) {
            Frame owner = innermostContaining(frames, m.start());
            if (owner == null || owner.className() == null) {
                continue;
            }
            Frame body = frames.stream().filter(f -> f.start() == m.end() - 1).findFirst().orElse(null);
            if (body == null || !isClassCheckOnly(code.substring(body.start() + 1, body.end()), m.group(1))) {
                continue;
            }
            if (hasConstructorWithArgs(code, frames, owner)) {
                hits.add(new Hit("<source>", owner.className()));
            }
        }
        return hits;
    }

    private static Frame innermostContaining(List<Frame> frames, int pos) {
        Frame best = null;
        for (Frame f : frames) {
            if (f.start() < pos && pos < f.end() && (best == null || f.start() > best.start())) {
                best = f;
            }
        }
        return best;
    }

    private static boolean isClassCheckOnly(String body, String param) {
        String b = body.replaceAll("\\s+", " ").trim();
        String type = "[\\w.]+(?:<[?\\w, ]*>)?(?: \\w+)?";
        String p = Pattern.quote(param);
        return b.matches("return " + p + " instanceof " + type + ";")
                || b.matches("if \\(" + p + " instanceof " + type + "\\) \\{? ?return true; ?\\}? ?return false;");
    }

    private static boolean hasConstructorWithArgs(String code, List<Frame> frames, Frame cls) {
        // only the class's own level, whatever is inside a nested { } is somebody else's constructor
        StringBuilder top = new StringBuilder();
        int i = cls.start() + 1;
        while (i < cls.end()) {
            Frame inner = null;
            for (Frame f : frames) {
                if (f.start() == i) {
                    inner = f;
                    break;
                }
            }
            if (code.charAt(i) == '{' && inner != null) {
                top.append("{}");
                i = inner.end() + 1;
            } else {
                top.append(code.charAt(i));
                i++;
            }
        }
        String level = top.toString();
        Matcher ctor = Pattern.compile("\\b" + Pattern.quote(cls.className()) + "\\s*\\(([^)]*)\\)\\s*(?:throws[\\w.,\\s]+)?\\{").matcher(level);
        while (ctor.find()) {
            // `new Foo(x) { ... }` is an anonymous subclass, not a constructor
            if (level.substring(0, ctor.start()).stripTrailing().endsWith("new")) {
                continue;
            }
            if (!ctor.group(1).isBlank()) {
                return true;
            }
        }
        return false;
    }

    // comments and string/char literals become spaces (newlines kept), so braces and keywords inside them can't confuse the scan
    private static String blank(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (s.startsWith("//", i)) {
                while (i < s.length() && s.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (s.startsWith("/*", i)) {
                int end = s.indexOf("*/", i + 2);
                end = end < 0 ? s.length() : end + 2;
                for (; i < end; i++) {
                    out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                }
            } else if (s.startsWith("\"\"\"", i)) {
                int end = s.indexOf("\"\"\"", i + 3);
                end = end < 0 ? s.length() : end + 3;
                for (; i < end; i++) {
                    out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                }
            } else if (c == '"' || c == '\'') {
                out.append(' ');
                i++;
                while (i < s.length() && s.charAt(i) != c && s.charAt(i) != '\n') {
                    if (s.charAt(i) == '\\') {
                        out.append(' ');
                        i++;
                    }
                    out.append(' ');
                    i++;
                }
                if (i < s.length() && s.charAt(i) == c) {
                    out.append(' ');
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
