package adris.altoclef.util.helpers;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

// every static of the fork that can hold state from one order to the next (StateStatics) is a memory of the run, registered
// in RunMemory, or on run-memory-allowed.txt with a reason. a new one that is neither fails here: register it (and it is
// measured and cleared on a fresh run), or allow it with the reason it is no memory of the world. Baritone's own packages are
// not scanned: its cached world is registered whole (baritone_cache), the rest of it is per path and per process
public class RunMemoryGuardTest {
    private static Set<String> found;
    private static Map<String, String> allowed;

    @BeforeClass
    public static void scan() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path root = Path.of(RunMemory.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        found = StateStatics.scan(root, "adris.altoclef", RunMemoryGuardTest.class.getClassLoader());
        allowed = allowed();
    }

    // "key reason": a field (a.b.C#f, or a.b.C#f,g), a class (a.b.C, its own fields), or a package (a.b., everything below it)
    static Map<String, String> allowed() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream in = RunMemoryGuardTest.class.getResourceAsStream("run-memory-allowed.txt")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int sp = line.indexOf(' ');
                assertTrue("an allowed static without a reason: " + line, sp > 0 && !line.substring(sp).isBlank());
                String key = line.substring(0, sp), reason = line.substring(sp + 1).strip();
                int hash = key.indexOf('#');
                if (hash < 0) {
                    out.put(key, reason);
                    continue;
                }
                for (String f : key.substring(hash + 1).split(",")) out.put(key.substring(0, hash + 1) + f, reason);
            }
        }
        return out;
    }

    static boolean allows(Map<String, String> allowed, String key) {
        if (allowed.containsKey(key)) return true;
        String cls = key.substring(0, key.indexOf('#'));
        if (allowed.containsKey(cls)) return true;
        for (String k : allowed.keySet()) {
            if (k.endsWith(".") && cls.startsWith(k)) return true;
        }
        return false;
    }

    private static Set<String> registered() {
        Set<String> out = new HashSet<>();
        for (RunMemory.Memory m : RunMemory.all()) out.addAll(m.fields());
        return out;
    }

    @Test
    public void everyStateStaticIsRegisteredOrAllowed() {
        Set<String> reg = registered();
        List<String> missing = new ArrayList<>();
        for (String f : found) {
            if (!reg.contains(f) && !allows(allowed, f)) missing.add(f);
        }
        if (!missing.isEmpty()) {
            fail(missing.size() + " static(s) can hold state between orders and are neither registered in RunMemory nor on "
                    + "run-memory-allowed.txt with a reason:\n" + String.join("\n", missing));
        }
    }

    // a registration or an allowed field names a static that is there: a rename leaves no stale name behind
    @Test
    public void everyNamedFieldExists() throws Exception {
        List<String> names = new ArrayList<>(registered());
        for (String k : allowed.keySet()) if (k.contains("#")) names.add(k);
        List<String> gone = new ArrayList<>();
        for (String n : names) {
            try {
                Class<?> c = Class.forName(n.substring(0, n.indexOf('#')), false, getClass().getClassLoader());
                if (!Modifier.isStatic(c.getDeclaredField(n.substring(n.indexOf('#') + 1)).getModifiers())) gone.add(n + " (not static)");
            } catch (ClassNotFoundException | NoSuchFieldException e) {
                gone.add(n);
            }
        }
        if (!gone.isEmpty()) fail("named but not found:\n" + String.join("\n", gone));
    }

    // a class or package on the list still has a static the scan finds: the list does not keep a stale entry
    @Test
    public void everyAllowedClassStillHasOne() {
        List<String> stale = new ArrayList<>();
        for (String k : allowed.keySet()) {
            if (k.contains("#")) continue;
            boolean any = false;
            for (String f : found) {
                String cls = f.substring(0, f.indexOf('#'));
                if (k.endsWith(".") ? cls.startsWith(k) : cls.equals(k)) {
                    any = true;
                    break;
                }
            }
            if (!any) stale.add(k);
        }
        if (!stale.isEmpty()) fail("allowed, but nothing there holds state now:\n" + String.join("\n", stale));
    }
}
