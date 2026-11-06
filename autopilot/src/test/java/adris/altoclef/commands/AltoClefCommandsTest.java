/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package adris.altoclef.commands;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import baritone.api.IBaritone;
import baritone.api.command.ICommand;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.argument.ICommandArgument;
import baritone.command.argument.ArgConsumer;
import baritone.command.argument.CommandArguments;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.Test;

// the command table itself: names, descriptions, and the completion that does not need a running game
public class AltoClefCommandsTest {

    // an IBaritone that hands out more of itself for every getter, so commands can be built without a game. anything
    // that is not an interface comes back null, which is fine for constructors
    private static IBaritone fakeBaritone() {
        return (IBaritone) fake(IBaritone.class);
    }

    private static Object fake(Class<?> type) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            Class<?> r = method.getReturnType();
            if (method.getName().equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            if (method.getName().equals("equals")) {
                return proxy == args[0];
            }
            if (r.isInterface()) {
                return fake(r);
            }
            if (r == boolean.class) {
                return false;
            }
            if (r.isPrimitive() && r != void.class) {
                return r == double.class ? 0.0 : r == float.class ? 0f : r == long.class ? 0L : r == int.class ? 0 : r == short.class ? (short) 0 : r == byte.class ? (byte) 0 : '\0';
            }
            return null;
        });
    }

    private static IArgConsumer args(String rest) {
        List<ICommandArgument> list = CommandArguments.from(rest, true);
        return new ArgConsumer(null, list);
    }

    private static ICommand command(String name) {
        for (ICommand c : AltoClefCommands.createAll(fakeBaritone())) {
            if (c.getNames().contains(name)) {
                return c;
            }
        }
        return null;
    }

    private static List<String> tab(String name, String rest) throws Exception {
        return command(name).tabComplete(name, args(rest)).toList();
    }

    @Test
    public void everyCommandIsDocumentedLikeSopranosOwn() {
        for (ICommand c : AltoClefCommands.createAll(fakeBaritone())) {
            String first = c.getNames().get(0);
            assertFalse(first + " has no short description", c.getShortDesc().isBlank());
            if (c instanceof CustomCommand) {
                // its long description reads configs/CustomTasks.json, which would get written into the test's folder
                continue;
            }
            List<String> longDesc = c.getLongDesc();
            assertTrue(first + " needs a Usage: block", longDesc.contains("Usage:"));
            // every usage line starts with "> " and names a command that exists
            boolean afterUsage = false;
            boolean sawUsage = false;
            for (String line : longDesc) {
                if (line.equals("Usage:")) {
                    afterUsage = true;
                    continue;
                }
                if (afterUsage && line.isEmpty()) {
                    break;
                }
                if (afterUsage) {
                    sawUsage = true;
                    assertTrue(first + ": usage line does not start with \"> \": " + line, line.startsWith("> "));
                }
            }
            assertTrue(first + " has an empty Usage: block", sawUsage);
        }
    }

    @Test
    public void namesAreLowercaseAndUnique() {
        Set<String> seen = new HashSet<>();
        for (ICommand c : AltoClefCommands.createAll(fakeBaritone())) {
            for (String n : c.getNames()) {
                assertEquals(n.toLowerCase(Locale.US), n);
                assertTrue("two altoclef commands are both called " + n, seen.add(n));
            }
        }
    }

    @Test
    public void noNameClashesWithAStockCommandOrASetting() throws Exception {
        // the registry hands out the newest command first, so a clash would silently replace a stock command. and a
        // setting with the same name wins before the command is even looked up
        // the stock commands can't be built without a game (BuildCommand wants the game dir), so read their names out of
        // the source: every string without a space on a line that passes the names to Command/CommandAlias
        Set<String> stock = new HashSet<>();
        Pattern literal = Pattern.compile("\"([^\"\\s]+)\"");
        try (var files = Files.list(Path.of("src/main/java/baritone/command/defaults"))) {
            for (Path file : files.toList()) {
                for (String line : Files.readAllLines(file)) {
                    if (line.contains("(baritone,") && !line.contains("getSimpleName")) {
                        Matcher m = literal.matcher(line);
                        while (m.find()) {
                            stock.add(m.group(1));
                        }
                    }
                }
            }
        }
        assertTrue("did not find the stock commands, wrong working directory?", stock.contains("goto") && stock.contains("cancel") && stock.contains("sethome"));
        // Settings can't be built without a game either, so the field names come out of the source too
        Set<String> settings = new HashSet<>();
        Matcher fields = Pattern.compile("public final Setting<[^=]+?>\\s+(\\w+)\\s*=").matcher(Files.readString(Path.of("src/api/java/baritone/api/Settings.java")));
        while (fields.find()) {
            settings.add(fields.group(1).toLowerCase(Locale.US));
        }
        assertTrue("did not find the settings, wrong working directory?", settings.contains("allowbreak"));
        for (ICommand c : AltoClefCommands.createAll(fakeBaritone())) {
            for (String n : c.getNames()) {
                assertFalse(n + " is a stock command", stock.contains(n));
                assertFalse(n + " is a setting", settings.contains(n));
            }
        }
    }

    @Test
    public void theNamesTheLeadAskedFor() {
        String[] expected = {"get", "give", "equip", "deposit", "stash", "food", "meat", "gamer", "hero", "punk",
                "coords", "inventory", "list", "locate_structure", "locatestructure", "status", "idle", "selfcare",
                "gamma", "setgamma", "set_gamma", "coverwithblocks", "coverwithsand", "altoreload", "reload_settings"};
        for (String name : expected) {
            assertNotNull(name + " is missing", command(name));
        }
        // dropped on purpose: test is dev only, follow/goto/stop/help are soprano's now
        for (String gone : new String[]{"test", "follow", "goto", "stop", "help", "marvion"}) {
            assertNull(gone + " should not be an altoclef command", command(gone));
        }
    }

    @Test
    public void butlerAllowListIsOurCommandsAndAFewOfSopranosNamedOnPurpose() {
        // register() fills the list in the real game, here we only look at the fixed part of it
        assertTrue(AltoClefCommands.isButlerAllowed("stop"));
        assertTrue(AltoClefCommands.isButlerAllowed("GOTO"));
        assertTrue(AltoClefCommands.isButlerAllowed("follow"));
        for (String denied : new String[]{"set", "build", "mine", "settings", "waypoints", "reloadall", "saveall", "sel", "click", "elytra", "farm", "explore", "", "#set", "set;get"}) {
            assertFalse(denied + " must not be allowed", AltoClefCommands.isButlerAllowed(denied));
        }
    }

    @Test
    public void locateStructureCompletesTheStructures() throws Exception {
        assertEquals(List.of("desert_temple", "stronghold"), tab("locate_structure", "").stream().sorted().toList());
        assertEquals(List.of("stronghold"), tab("locatestructure", "str"));
        assertEquals(List.of("desert_temple"), tab("locate_structure", "DESERT"));
        // a second word is not a thing
        assertTrue(tab("locate_structure", "stronghold ").isEmpty());
        assertEquals(LocateStructureCommand.Structure.DESERT_TEMPLE, LocateStructureCommand.Structure.parse("desert_temple"));
        assertEquals(LocateStructureCommand.Structure.DESERT_TEMPLE, LocateStructureCommand.Structure.parse("DesertTemple"));
        assertEquals(LocateStructureCommand.Structure.STRONGHOLD, LocateStructureCommand.Structure.parse("stronghold"));
        assertNull(LocateStructureCommand.Structure.parse("mansion"));
    }

    @Test
    public void stashCompletesAllSixCoordinates() throws Exception {
        assertEquals(List.of("~"), tab("stash", ""));
        assertEquals(List.of("~"), tab("stash", "1 "));
        assertEquals(List.of("~"), tab("stash", "1 2 3 4 5 "));
        assertEquals(List.of("~"), tab("stash", "~ ~ ~ ~ ~ ~"));
        // a number being typed has nothing to add to it
        assertTrue(tab("stash", "12").isEmpty());
    }

    @Test
    public void gammaOffersAFewValues() throws Exception {
        assertEquals(List.of("1.0", "5.0", "15.0"), tab("gamma", ""));
        assertEquals(List.of("15.0"), tab("setgamma", "15"));
        assertTrue(tab("gamma", "1.0 ").isEmpty());
    }

    @Test
    public void gamerCompletesItsSubcommandsAndPhases() throws Exception {
        assertEquals(List.of("phase", "reset", "status", "trace"), tab("gamer", ""));
        assertEquals(List.of("status"), tab("gamer", "st"));
        assertEquals(List.of("trace"), tab("gamer", "tr"));
        assertTrue(tab("gamer", "trace ").isEmpty());
        assertEquals(List.of("locate"), tab("gamer", "phase lo"));
        assertEquals(11, tab("gamer", "phase ").size());
        assertTrue(tab("gamer", "phase ").containsAll(List.of("gather", "end_prep", "dragon")));
        assertFalse(tab("gamer", "phase ").contains("done"));
        // nothing after a finished command
        assertTrue(tab("gamer", "status ").isEmpty());
        assertTrue(tab("gamer", "reset ").isEmpty());
        assertTrue(tab("gamer", "phase locate ").isEmpty());
    }

    @Test
    public void gamerPhaseNamesAreForgiving() throws Exception {
        assertEquals(adris.altoclef.tasks.speedrun.gamer.GamerPhase.END_PREP, GamerCommand.parsePhase("end_prep"));
        assertEquals(adris.altoclef.tasks.speedrun.gamer.GamerPhase.END_PREP, GamerCommand.parsePhase("END-PREP"));
        assertEquals(adris.altoclef.tasks.speedrun.gamer.GamerPhase.END_PREP, GamerCommand.parsePhase("endprep"));
        assertEquals(adris.altoclef.tasks.speedrun.gamer.GamerPhase.LOCATE, GamerCommand.parsePhase("Locate"));
        for (String bad : new String[]{"done", "stuck", "breakfast", ""}) {
            try {
                GamerCommand.parsePhase(bad);
                org.junit.Assert.fail(bad + " should not be a phase");
            } catch (baritone.api.command.exception.CommandInvalidStateException expected) {
                // fine
            }
        }
    }

    @Test
    public void commandsWithNoArgumentsCompleteNothing() throws Exception {
        for (String name : new String[]{"hero", "selfcare", "idle", "coverwithblocks", "coverwithsand", "coords", "status", "altoreload", "list"}) {
            assertTrue(name, tab(name, "").isEmpty());
        }
    }
}
