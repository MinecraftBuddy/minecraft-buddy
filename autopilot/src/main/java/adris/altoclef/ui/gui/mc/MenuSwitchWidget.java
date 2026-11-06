package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.Theme;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

import java.util.List;

// the way back from a vanilla title screen. same words and same corner as the switch TitleMenu draws, but in vanilla's
// font and vanilla's square pixels: on this screen a glass pill would look like a sticker. no kit in here on purpose,
// this also has to work when the atlases never came up and that's why the screen is vanilla in the first place
public final class MenuSwitchWidget extends AbstractButton {

    private static final String LABEL = "Soprano menu";
    private static final Component PLAIN = Component.literal(LABEL);
    private static final Component UNDERLINED = Component.literal(LABEL).withStyle(ChatFormatting.UNDERLINE);

    // vanilla's copyright line is 10 tall at height - 10, 2 in from the right edge. we're one row up from it with a
    // gap of 2. its bottom buttons stop at height / 4 + 152, which at the smallest normal gui screen (240 tall) is 212,
    // and this row starts at 218. fitAround is for the screens that aren't normal
    private static final int EDGE = 2;
    private static final int COPYRIGHT_H = 10;
    private static final int GAP = 2;
    private static final int ROW_H = 10;
    private static final int TEXT_GAP = 4;
    private static final int TRACK_W = 16;
    private static final int TRACK_H = 8;
    private static final int KNOB = 6;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = 0xFFA0A0A0;
    private static final int TRACK_OFF = 0x8C000000;

    private final Font font;
    private boolean shown;
    private boolean compact;

    public MenuSwitchWidget(int screenWidth, int screenHeight) {
        super(screenWidth - EDGE - width(), screenHeight - COPYRIGHT_H - GAP - ROW_H, width(), ROW_H, message(SopranoMenus.enabled()));
        this.font = Minecraft.getInstance().font;
        this.shown = SopranoMenus.enabled();
    }

    private static int width() {
        return Minecraft.getInstance().font.width(LABEL) + TEXT_GAP + TRACK_W;
    }

    private static Component message(boolean on) {
        return Component.literal(LABEL + ": " + (on ? "on" : "off"));
    }

    // forced unicode rounds the gui scale up, and on a screen that short vanilla's buttons run down into our row. the
    // label goes and the switch alone sits past their right edge. the row is only as good as what's there at init, a
    // mod adding a button in this corner later on is on its own. a window under 200 gui wide has vanilla's own buttons
    // wall to wall, nothing fits there, those keep the overlap vanilla already has with its copyright line
    public void fitAround(List<? extends GuiEventListener> others) {
        for (GuiEventListener other : others) {
            if (other != this && other instanceof AbstractWidget o && o.visible
                    && o.getX() < getRight() && o.getRight() > getX()
                    && o.getY() < getY() + getHeight() && o.getY() + o.getHeight() > getY()) {
                int right = getRight();
                compact = true;
                setWidth(TRACK_W);
                setX(right - TRACK_W);
                return;
            }
        }
    }

    @Override
    public void onPress(InputWithModifiers input) {
        SopranoMenus.flip();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        // reads the setting and not its own clicks, the click gui can flip it too. same as TitleMenu.syncMenuSwitch
        boolean on = SopranoMenus.enabled();
        if (on != shown) {
            shown = on;
            setMessage(message(on));
        }
        boolean lit = isHoveredOrFocused();
        int x = getX();
        int y = getY();
        // the copyright line underlines on hover too, so this is what a vanilla player already expects
        if (!compact) {
            g.text(font, lit ? UNDERLINED : PLAIN, x, y, Theme.fade(WHITE, alpha));
        }
        int tx = getRight() - TRACK_W;
        g.fill(tx, y, tx + TRACK_W, y + TRACK_H, Theme.fade(on ? Theme.ACCENT : TRACK_OFF, alpha));
        g.outline(tx, y, TRACK_W, TRACK_H, Theme.fade(lit ? WHITE : GREY, alpha));
        int kx = on ? tx + TRACK_W - 1 - KNOB : tx + 1;
        g.fill(kx, y + 1, kx + KNOB, y + 1 + KNOB, Theme.fade(WHITE, alpha));
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }
}
