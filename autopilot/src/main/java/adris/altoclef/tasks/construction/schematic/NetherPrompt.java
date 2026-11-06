package adris.altoclef.tasks.construction.schematic;

import baritone.api.utils.Helper;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static baritone.api.command.IBaritoneChatControl.FORCE_COMMAND_PREFIX;

// the "may I go to the nether" question a survival build asks. every chat component of it lives here, so a version
// port only fights one file. same shape as the trip prompt on 26.3
public final class NetherPrompt {

    // the command the buttons run, and what the player can type instead. SchemCommand answers it
    public static final String COMMAND = "schem nether";

    private NetherPrompt() {
    }

    // the items worth asking about: still short (the bag doesn't cover them) and the trip for them goes to the nether.
    // nether says which ones do, the task folds the skipped, the unchecked and the ungettable into it
    public static Set<String> shortfall(Map<String, Integer> need, Map<String, Integer> have, Predicate<String> nether) {
        Set<String> out = new LinkedHashSet<>();
        need.forEach((id, n) -> {
            if (have.getOrDefault(id, 0) < n && nether.test(id)) {
                out.add(id);
            }
        });
        return out;
    }

    public static String question(String items) {
        return "This build requires things in the nether (" + items + "). Would you like me to go there and get them?";
    }

    public static void ask(long id, String items) {
        MutableComponent msg = Component.literal(question(items) + " ");
        msg.append(button("[Yes]", ChatFormatting.GREEN, COMMAND + " yes " + id, "Go to the nether for them"));
        msg.append(Component.literal(" "));
        msg.append(button("[No]", ChatFormatting.RED, COMMAND + " no " + id, "Build without them"));
        // always chat, a toast can't be clicked. the toast is the "hey, look at chat", the build waits on the answer
        Helper.HELPER.logDirect(false, msg);
        Helper.HELPER.logToast(Helper.getPrefix(), Component.literal("The build needs things from the nether. Answer in chat"));
    }

    private static MutableComponent button(String text, ChatFormatting color, String command, String hover) {
        MutableComponent c = Component.literal(text);
        c.setStyle(c.getStyle()
            .withColor(color)
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover)))
            .withClickEvent(new ClickEvent.RunCommand(FORCE_COMMAND_PREFIX + command)));
        return c;
    }
}
