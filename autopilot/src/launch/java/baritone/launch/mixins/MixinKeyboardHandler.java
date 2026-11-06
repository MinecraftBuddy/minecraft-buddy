package baritone.launch.mixins;

import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.mc.GuiHotkey;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class MixinKeyboardHandler {

    // head and cancelled: the press never reaches KeyMapping, and never reaches the screen it just opened either
    @Inject(
            method = "keyPress",
            at = @At("HEAD"),
            cancellable = true
    )
    private void soprano$openGui(final long window, final int action, final KeyEvent event, final CallbackInfo ci) {
        try {
            // the event is sdl's: event.key() is a scancode and action is PRESS 1 / RELEASE 0 / REPEAT -1. GuiHotkey does
            // the translation to the glfw numbers the setting holds
            if (window == Minecraft.getInstance().getWindow().handle() && GuiHotkey.onKey(event, action)) {
                ci.cancel();
            }
        } catch (Throwable t) {
            // a key press is the middle of everything, the gui does not get to throw out of it
            Safe.report("gui key mixin", t);
        }
    }
}
