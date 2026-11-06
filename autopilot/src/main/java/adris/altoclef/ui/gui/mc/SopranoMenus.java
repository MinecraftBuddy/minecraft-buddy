package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.kit.Safe;
import baritone.Baritone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

// whether our boot screen and title screen are on. the very first frame of the boot overlay is drawn from inside
// Minecraft's constructor, before baritone (and its settings file) exists, so until the constructor returns the answer
// is just yes. that also keeps a settings class init failure out of a render call
public final class SopranoMenus {

    private static volatile boolean ready;

    private SopranoMenus() {
    }

    // MixinMinecraft calls this at the end of the constructor, right after baritone is up
    public static void markReady() {
        ready = true;
    }

    public static boolean enabled() {
        if (!ready) {
            return true;
        }
        try {
            return Baritone.settings().guiMenus.value;
        } catch (Throwable t) {
            Safe.report("menus setting", t);
            return true;
        }
    }

    // the switch in the bottom right of the title. same road the speaker button takes, so it lands in settings.txt. a
    // title decides whose it is once, on its first init, so going from one look to the other is a brand new screen.
    // that's also why flipping it twenty times in a row can't stack anything up
    static void flip() {
        try {
            Result r = new McSettings().toggle("guiMenus");
            if (!r.ok()) {
                Safe.report("menus switch", new IllegalStateException(r.message()));
                return;
            }
            Minecraft.getInstance().gui.setScreen(new TitleScreen());
        } catch (Throwable t) {
            Safe.report("menus switch", t);
        }
    }

    // the speaker button. the music only starts once the title has shown, long after markReady, so the early yes is
    // just there to keep the answer the same shape as enabled()
    static boolean music() {
        if (!ready) {
            return true;
        }
        try {
            return Baritone.settings().guiMenuMusic.value;
        } catch (Throwable t) {
            Safe.report("menu music setting", t);
            return true;
        }
    }

    // reduced motion for the boot screen, which has no KitSurface to sync Anim.enabled for it
    static boolean animations() {
        if (!ready) {
            return true;
        }
        try {
            return Baritone.settings().guiAnimations.value;
        } catch (Throwable t) {
            Safe.report("menus animation setting", t);
            return true;
        }
    }
}
