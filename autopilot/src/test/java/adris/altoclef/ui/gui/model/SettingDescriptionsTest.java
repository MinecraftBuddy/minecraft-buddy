package adris.altoclef.ui.gui.model;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SettingDescriptionsTest {

    private static final Pattern DECL = Pattern.compile("^ {4}public\\s+final\\s+Setting<.*$");

    private static boolean regenerate() {
        return "true".equalsIgnoreCase(System.getProperty("regenerate")) || "true".equalsIgnoreCase(System.getenv("REGENERATE"));
    }

    @Test
    public void checkedInFileIsNotStale() throws IOException {
        String fresh = SettingDescriptionsGenerator.generate();
        Path file = SettingDescriptionsGenerator.resource();
        if (regenerate()) {
            Files.createDirectories(file.getParent());
            Files.write(file, fresh.getBytes(StandardCharsets.US_ASCII));
            return;
        }
        assertTrue("settings-descriptions.properties is missing: run SettingDescriptionsGenerator, or regenerate with: REGENERATE=true ./gradlew test --tests SettingDescriptionsTest (the gradle -Dregenerate flag does not reach the test JVM, the environment variable does)",
                Files.exists(file));
        String have = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals("settings-descriptions.properties is stale: run SettingDescriptionsGenerator, or regenerate with: REGENERATE=true ./gradlew test --tests SettingDescriptionsTest (the gradle -Dregenerate flag does not reach the test JVM, the environment variable does)",
                fresh, have);
    }

    @Test
    public void everySettingHasADescription() throws IOException {
        List<SettingDescriptionsGenerator.Parsed> parsed = parsed();
        List<String> bare = new ArrayList<>();
        for (var p : parsed) {
            if (!p.javaOnly() && p.description().isEmpty()) {
                bare.add(p.name());
            }
        }
        assertTrue("settings with no javadoc: " + bare, bare.isEmpty());
    }

    @Test
    public void parsesEveryDeclaration() throws IOException {
        // an independent count, so a field the parser skipped shows up as a number that does not match
        long decls = Files.readAllLines(SettingDescriptionsGenerator.settingsJava(), StandardCharsets.UTF_8).stream()
                .filter(l -> DECL.matcher(l).matches()).count();
        assertEquals(decls, parsed().size());
        assertTrue(parsed().size() > 300);
    }

    @Test
    public void descriptionsAreOneCleanParagraph() throws IOException {
        for (var p : parsed()) {
            String d = p.description();
            assertFalse(p.name() + " has a leftover tag: " + d, d.contains("{@") || d.contains("</") || d.contains("<p>"));
            assertFalse(p.name() + " has doubled spaces: " + d, d.contains("  "));
            assertFalse(p.name() + " has a newline: " + d, d.contains("\n"));
        }
    }

    @Test
    public void descriptionsSaySopranoNotAltoclef() throws IOException {
        for (var p : parsed()) {
            // a file path is the one place the real folder name has to stay
            String prose = String.join(" ", java.util.Arrays.stream(p.description().split(" ")).filter(w -> !w.contains("/")).toList());
            assertFalse(p.name() + ": " + p.description(), prose.toLowerCase().contains("altoclef"));
        }
        String src = String.join("\n",
                "public final class Settings {",
                "    /** Show the task list of AltoClef while AltoClef's brain runs, see {@link #altoIdleCommand}. */",
                "    public final Setting<Boolean> one = new Setting<>(true);",
                "}");
        assertEquals("Show the task list of Soprano while Soprano's brain runs, see altoIdleCommand.",
                SettingDescriptionsGenerator.parse(src).get(0).description());
    }

    @Test
    public void javaOnlyFieldsAreLeftOut() throws IOException {
        Properties props = new Properties();
        props.load(new StringReader(SettingDescriptionsGenerator.generate()));
        for (var p : parsed()) {
            assertEquals(p.name(), !p.javaOnly(), props.containsKey(p.name()));
        }
        assertFalse(props.containsKey("logger"));
        assertTrue(props.containsKey("allowBreak"));
    }

    @Test
    public void guiKeySettingsAreThere() throws IOException {
        Properties props = new Properties();
        props.load(new StringReader(SettingDescriptionsGenerator.generate()));
        assertTrue(props.getProperty("guiKey", "").length() > 10);
        assertTrue(props.getProperty("guiKeyEnabled", "").length() > 10);
    }

    @Test
    public void javadocBecomesPlainText() {
        String src = String.join("\n",
                "public final class Settings {",
                "    /**",
                "     * Keep {@link #altoIdleCommand} and {@link Setting#isJavaOnly() Java-only} going, {@code List<Block>}",
                "     * &lt;fast&gt; &amp; loose.",
                "     * <p>",
                "     * Second <b>paragraph</b>: a=b",
                "     *",
                "     * @see Whatever",
                "     */",
                "    public final Setting<Boolean> one = new Setting<>(true);",
                "",
                "    /** Short one. */",
                "    @Deprecated",
                "    @JavaOnly",
                "    public final Setting<Boolean> two = new Setting<>(true);",
                "",
                "    public final Setting<Integer> three = new Setting<>(1);",
                "}");
        List<SettingDescriptionsGenerator.Parsed> out = SettingDescriptionsGenerator.parse(src);
        assertEquals(3, out.size());
        assertEquals("Keep altoIdleCommand and Java-only going, List<Block> <fast> & loose. Second paragraph: a=b",
                out.get(0).description());
        assertEquals("Short one.", out.get(1).description());
        assertTrue(out.get(1).javaOnly());
        assertEquals("", out.get(2).description());
    }

    @Test
    public void paragraphsJoinIntoOneAndGetAFullStopIfTheyLackedOne() {
        String src = String.join("\n",
                "public final class Settings {",
                "    /**",
                "     * First part",
                "     * <p>",
                "     * Second part.<p>Third.",
                "     */",
                "    public final Setting<Boolean> one = new Setting<>(true);",
                "}");
        assertEquals("First part. Second part. Third.", SettingDescriptionsGenerator.parse(src).get(0).description());
    }

    @Test
    public void escapingRoundTripsThroughProperties() throws IOException {
        String tricky = " leading space, back\\slash, a=b, c:d, #hash, !bang, café, tab\there";
        var list = List.of(new SettingDescriptionsGenerator.Parsed("one", false, tricky));
        Properties props = new Properties();
        props.load(new StringReader(SettingDescriptionsGenerator.render(list)));
        assertEquals(tricky, props.getProperty("one"));
        assertTrue(SettingDescriptionsGenerator.render(list).startsWith(SettingDescriptionsGenerator.HEADER + "\n"));
    }

    private static List<SettingDescriptionsGenerator.Parsed> parsed() throws IOException {
        return SettingDescriptionsGenerator.parse(Files.readString(SettingDescriptionsGenerator.settingsJava(), StandardCharsets.UTF_8));
    }
}
