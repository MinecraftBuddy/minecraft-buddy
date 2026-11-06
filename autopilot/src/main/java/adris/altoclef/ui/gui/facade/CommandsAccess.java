package adris.altoclef.ui.gui.facade;

import java.util.List;

// the command registry and a way to run a line, for the help tab
public interface CommandsAccess {

    // names[0] is the primary name, the rest are aliases. longDesc is the lines the command's getLongDesc gives
    record CommandInfo(List<String> names, String shortDesc, List<String> longDesc) {
        public String name() {
            return names.get(0);
        }
    }

    // everything not hidden from help, sorted by primary name, built once per screen
    List<CommandInfo> commands();

    // runs "goto 1 2 3" (no prefix). message holds what the command printed if that could be captured, else a
    // short confirmation. a thrown CommandException becomes fail(its message)
    Result run(String line);
}
