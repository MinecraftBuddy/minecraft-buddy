package adris.altoclef.ui.gui.mc;

import adris.altoclef.BuildInfo;
import adris.altoclef.ui.gui.kit.Anim;
import adris.altoclef.ui.gui.kit.BrandArt;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.DockLayout;
import adris.altoclef.ui.gui.kit.Easing;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.PixelImage;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.Spring;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Weight;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// the title screen, ours: blurred panorama, a backdrop, the brand block, a dock of tiles that grow under the mouse,
// three round corner buttons, the two corner lines and the menu switch. one of these lives as long as its TitleScreen (MixinTitleScreen
// keeps it), so the entry animation, the springs and the parallax survive init re-running on resize and when the
// boot overlay re-inits the screen. vanilla's buttons stay in the screen, just invisible, and each gets a TitleTile
// that presses it (the music button's is one of our own that never joins the screen). the layout and animation
// numbers are gui-mock/title/title.js, keep the two in step.
// friend request badges and the realms notification icons are dropped for now, they'd need vanilla's sprites
public final class TitleMenu {

    private enum Role { PLAY, SECOND, REALMS, EXTRA, OPTIONS, QUIT, LANGUAGE, ACCESSIBILITY, MUSIC, COPYRIGHT, MENU_SWITCH }

    private static final class Piece {
        final TitleTile tile;
        final Role role;
        // the music button swaps both when it's flipped
        Icon icon;
        final int top;
        final int bottom;
        final String id;
        String label;
        // multiplayer and realms show vanilla's "why not" while they're off
        final boolean mpTip;
        // device px rect and corner radius from the last layout
        int x;
        int y;
        int w;
        int h;
        int r;

        Piece(TitleTile tile, Role role, Icon icon, int top, int bottom, String id, String label, boolean mpTip) {
            this.tile = tile;
            this.role = role;
            this.icon = icon;
            this.top = top;
            this.bottom = bottom;
            this.id = id;
            this.label = label;
            this.mpTip = mpTip;
        }

        boolean contains(float px, float py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    // gradient stops, top then bottom. every tile the same graphite under a white glyph: a row of six different
    // gradients looked like a phone home screen, one colour reads as one dock and matches the black boot screen
    private static final int TILE_0 = 0xFF2C2C2E;
    private static final int TILE_1 = 0xFF161618;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GLASS = 0x24FFFFFF;
    private static final int GLASS_HOVER = 0x38FFFFFF;
    private static final int HAIRLINE = 0x38FFFFFF;
    private static final int SHADOW = 0x59000000;
    private static final int PRESSED = 0x33000000;
    private static final int RING = 0xFF0A84FF;
    private static final int LABEL_BG = 0xDB1C1C1E;
    private static final int SUBTITLE = 0x9EFFFFFF;
    private static final int VERSION = 0xB3FFFFFF;
    private static final int COPYRIGHT = 0x99FFFFFF;
    private static final int TIP = 0xB3FFFFFF;

    // the menu switch, design px. the kit's Toggle is 34/18/14, this is the same shape a size down for 14 px text
    private static final int SWITCH_H = 18;
    private static final int SWITCH_GAP = 8;
    private static final int TRACK_W = 28;
    private static final int TRACK_H = 16;
    private static final int KNOB = 12;

    // the backdrop is all soft gradients, so it bakes small once and stretches
    private static final int SHADE_W = 480;
    private static final int SHADE_H = 270;
    private static PixelImage shade;

    private final Minecraft mc = Minecraft.getInstance();
    private final Renderable chrome = (g, mouseX, mouseY, partial) -> drawChrome(g);

    // what we hid, so a failure only ever re-shows our own doing (mods hide their own buttons on purpose too)
    private final Set<Button> hidden = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Button> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Piece> dock = new ArrayList<>();
    private Piece language;
    private Piece accessibility;
    private Piece music;
    private boolean musicOn;
    private Piece copyright;
    private Piece menuSwitch;
    private boolean menuOn;
    private final Map<String, Spring> springs = new HashMap<>();
    private String mpReason;
    private int guiScale = 1;
    private boolean broken;

    // entry: the boot fade hands us a screen opened with vanilla's fading flag, the clock starts on its first frame
    private boolean entryPending;
    private long entryStart = -1L;

    private KitSurface surface;
    private float scale = -1f;
    private TextStyle subStyle;
    private TextStyle versionStyle;
    private TextStyle copyStyle;
    private TextStyle labelStyle;
    private TextStyle tipStyle;

    private PixelImage icon;
    private int iconPx;
    private PixelImage word;
    private int wordPx;
    private final Map<Long, PixelImage> tiles = new HashMap<>();
    private int tileBakePx;

    // parallax, design px, exponential follow
    private float parBx;
    private float parBy;
    private float parDx;
    private float parDy;
    private long parLast;

    private Piece labelPiece;
    private long labelSince;

    // this frame's layout, made by the backdrop pass and used by the chrome pass
    private boolean fresh;
    private int width;
    private int height;
    private float eBrand;
    private float eSub;
    private float eDock;
    private float eCorner;
    private int pbx;
    private int pby;
    private boolean present;
    private float mouseX;
    private float mouseY;
    private boolean inBand;
    private int tPx;
    private int tileDesign;
    private int pad;
    private int pillTop;
    private int pillBottom;
    private DockLayout.Row row;
    private int divBefore = -1;
    private Piece hover;
    private String soundedId;
    private boolean soundPrimed;

    private TitleMenu(boolean fading) {
        this.entryPending = fading;
    }

    // decided once per title screen: our setting is on and the atlases came up (without them every picture would be a
    // grey box, vanilla looks better than that)
    public static TitleMenu create(boolean fading) {
        if (!SopranoMenus.enabled()) {
            return null;
        }
        McAtlases at = McAtlases.get();
        if (at == null || !at.ok) {
            return null;
        }
        return new TitleMenu(fading);
    }

    public boolean broken() {
        return broken;
    }

    // anything that went wrong while drawing: the mixin puts vanilla back before the next frame
    public void fail(String where, Throwable t) {
        Safe.report(where, t);
        broken = true;
    }

    public Renderable chrome() {
        return chrome;
    }

    // ----- init: sort vanilla's buttons into tiles

    // every init builds fresh vanilla buttons, so this starts over (the springs and clocks stay)
    public void rebuild(List<? extends GuiEventListener> children, Component multiplayerDisabled, int guiScale) {
        this.guiScale = Math.max(1, guiScale);
        this.mpReason = multiplayerDisabled == null ? null : multiplayerDisabled.getString();
        hidden.clear();
        seen.clear();
        dock.clear();
        language = null;
        accessibility = null;
        copyright = null;
        classify(children);
        music = musicPiece();
        menuSwitch = menuSwitchPiece();
    }

    // ours, not vanilla's: a button that never joins the screen, it's only there so the tile has something to press
    private Piece musicPiece() {
        boolean on = SopranoMenus.music();
        String label = musicLabel(on);
        Button b = Button.builder(Component.literal(label), btn -> MenuMusic.toggle()).build();
        musicOn = on;
        return new Piece(new TitleTile(b, guiScale), Role.MUSIC, musicIcon(on), 0, 0, "music", label, false);
    }

    // #set can flip it too, so the button follows the setting and not its own clicks
    private void syncMusic() {
        boolean on = SopranoMenus.music();
        if (music == null || on == musicOn) {
            return;
        }
        musicOn = on;
        music.label = musicLabel(on);
        music.icon = musicIcon(on);
        Component msg = Component.literal(music.label);
        music.tile.target.setMessage(msg);
        music.tile.setMessage(msg);
    }

    // same trick as musicPiece, a button that never joins the screen. the piece's label is what's drawn next to the
    // switch, the tile's message is what the narrator reads, that one needs the state too
    private Piece menuSwitchPiece() {
        boolean on = SopranoMenus.enabled();
        Button b = Button.builder(menuMessage(on), btn -> SopranoMenus.flip()).build();
        menuOn = on;
        return new Piece(new TitleTile(b, guiScale), Role.MENU_SWITCH, null, 0, 0, "menuSwitch", "Soprano menu", false);
    }

    // the click gui can flip guiMenus while this screen waits underneath it. the look waits for the next open, the
    // switch shows the setting all the same, same as syncMusic
    private void syncMenuSwitch() {
        boolean on = SopranoMenus.enabled();
        if (menuSwitch == null || on == menuOn) {
            return;
        }
        menuOn = on;
        Component msg = menuMessage(on);
        menuSwitch.tile.target.setMessage(msg);
        menuSwitch.tile.setMessage(msg);
    }

    private static Component menuMessage(boolean on) {
        return Component.literal("Soprano menu: " + (on ? "on" : "off"));
    }

    // what a press would do, like a play/pause button
    private static String musicLabel(boolean on) {
        return on ? "Mute music" : "Play music";
    }

    private static Icon musicIcon(boolean on) {
        return on ? Icon.SPEAKER : Icon.SPEAKER_OFF;
    }

    // the tiles to add before the update card: the dock in order, then the three corner buttons. tab walks the screen's
    // children in the order they were added, so this is the tab order too
    public List<TitleTile> tilesBeforeCard() {
        List<TitleTile> out = new ArrayList<>();
        for (Piece p : dock) {
            out.add(p.tile);
        }
        if (language != null) {
            out.add(language.tile);
        }
        if (accessibility != null) {
            out.add(accessibility.tile);
        }
        if (music != null) {
            out.add(music.tile);
        }
        return out;
    }

    // the bottom right corner goes after the update card in the tab order, the switch first because it's on top
    public List<TitleTile> tilesAfterCard() {
        List<TitleTile> out = new ArrayList<>();
        if (menuSwitch != null) {
            out.add(menuSwitch.tile);
        }
        if (copyright != null) {
            out.add(copyright.tile);
        }
        return out;
    }

    // buttons other mods added after our init tail (fabric's after init event, mod menu). collected before anything
    // changes, the caller adds what comes back before the screen starts walking its renderables
    public List<TitleTile> reclassify(List<? extends GuiEventListener> children) {
        return classify(children);
    }

    // put back what we hid and hand over our tiles so the mixin can take them out again
    public List<TitleTile> undress() {
        broken = true;
        MenuMusic.titleGone();
        for (Button b : hidden) {
            b.visible = true;
        }
        hidden.clear();
        List<TitleTile> ours = tilesBeforeCard();
        ours.addAll(tilesAfterCard());
        dock.clear();
        language = null;
        accessibility = null;
        music = null;
        copyright = null;
        menuSwitch = null;
        return ours;
    }

    public void startEntryIfPending() {
        if (entryPending) {
            entryPending = false;
            entryStart = System.nanoTime();
        }
    }

    private List<TitleTile> classify(List<? extends GuiEventListener> children) {
        List<Button> found = new ArrayList<>();
        for (GuiEventListener child : new ArrayList<>(children)) {
            // one somebody else hid stays unseen, so it gets its tile the frame they show it again
            if (child instanceof Button b && !(child instanceof TitleTile) && b.visible && seen.add(b)) {
                found.add(b);
            }
        }
        List<TitleTile> made = new ArrayList<>();
        for (Button b : found) {
            Piece p = piece(b);
            if (p != null) {
                made.add(p.tile);
            }
        }
        dock.sort(Comparator.comparingInt(p -> p.role.ordinal()));
        return made;
    }

    // a button somebody already hid keeps its own business. everything else goes invisible, most of it gets a tile
    private Piece piece(Button b) {
        if (!b.visible) {
            return null;
        }
        Component msg = b.getMessage();
        String key = msg != null && msg.getContents() instanceof TranslatableContents tc ? tc.getKey() : null;
        String text = msg == null ? "" : msg.getString();
        hide(b);
        // the dev only test world button next to singleplayer, and friends: nobody opens it, and its two people
        // icon is the better picture for multiplayer anyway. both stay hidden with no tile
        if ((key == null && "TW".equals(text)) || "gui.friends.open".equals(key)) {
            return null;
        }
        Role role = roleOf(key);
        // a second options button or whatever some mod adds is still a button somebody wants, it gets a puzzle tile
        if (role != Role.EXTRA && taken(role)) {
            role = Role.EXTRA;
        }
        String label = label(text);
        TitleTile tile = new TitleTile(b, guiScale);
        Piece p = switch (role) {
            case PLAY -> new Piece(tile, role, Icon.PLAYER, TILE_0, TILE_1, "play", label, false);
            case SECOND -> "menu.resetdemo".equals(key)
                    ? new Piece(tile, role, Icon.RESET, TILE_0, TILE_1, "reset", label, false)
                    : new Piece(tile, role, Icon.PEOPLE, TILE_0, TILE_1, "multiplayer", label, true);
            case REALMS -> new Piece(tile, role, Icon.CLOUD, TILE_0, TILE_1, "realms", label, true);
            case OPTIONS -> new Piece(tile, role, Icon.GEAR, TILE_0, TILE_1, "options", label, false);
            case QUIT -> new Piece(tile, role, Icon.POWER, TILE_0, TILE_1, "quit", label, false);
            case LANGUAGE -> new Piece(tile, role, Icon.LANGUAGE, 0, 0, "language", label, false);
            case ACCESSIBILITY -> new Piece(tile, role, Icon.ACCESSIBILITY, 0, 0, "accessibility", label, false);
            case COPYRIGHT -> new Piece(tile, role, null, 0, 0, "copyright", label, false);
            // roleOf never says music or the switch, those aren't vanilla's
            case EXTRA, MUSIC, MENU_SWITCH -> new Piece(tile, role, Icon.PUZZLE, TILE_0, TILE_1, extraId(label), label, false);
        };
        switch (role) {
            case LANGUAGE -> language = p;
            case ACCESSIBILITY -> accessibility = p;
            case COPYRIGHT -> copyright = p;
            default -> dock.add(p);
        }
        return p;
    }

    // the spring's key. two mods can both call their button "Mods", and two tiles on one spring would fight over it
    private String extraId(String label) {
        int same = 0;
        for (Piece p : dock) {
            if (p.role == Role.EXTRA && p.label.equals(label)) {
                same++;
            }
        }
        return "extra:" + label + "#" + same;
    }

    private static Role roleOf(String key) {
        if (key == null) {
            return Role.EXTRA;
        }
        return switch (key) {
            case "menu.singleplayer", "menu.playdemo" -> Role.PLAY;
            case "menu.multiplayer", "menu.resetdemo" -> Role.SECOND;
            case "menu.online" -> Role.REALMS;
            case "menu.options" -> Role.OPTIONS;
            case "menu.quit" -> Role.QUIT;
            case "options.language" -> Role.LANGUAGE;
            case "options.accessibility" -> Role.ACCESSIBILITY;
            case "title.credits" -> Role.COPYRIGHT;
            default -> Role.EXTRA;
        };
    }

    private boolean taken(Role role) {
        switch (role) {
            case LANGUAGE:
                return language != null;
            case ACCESSIBILITY:
                return accessibility != null;
            case COPYRIGHT:
                return copyright != null;
            default:
                for (Piece p : dock) {
                    if (p.role == role) {
                        return true;
                    }
                }
                return false;
        }
    }

    // vanilla's messages end in "..." (Options..., Language...), a dock label with an ellipsis looks like a typo
    private static String label(String s) {
        String t = s.strip();
        while (t.endsWith("...") || t.endsWith("…")) {
            t = t.endsWith("...") ? t.substring(0, t.length() - 3).strip() : t.substring(0, t.length() - 1).strip();
        }
        return t;
    }

    private void hide(Button b) {
        if (b.visible) {
            b.visible = false;
            hidden.add(b);
        }
    }

    // ----- drawing. both passes draw in framebuffer px under a 1/guiScale pose, same as UpdateCardWidget

    // right after the panorama (and vanilla's blur): the backdrop and the brand block, under every widget
    public void drawBackdrop(GuiGraphicsExtractor g) {
        if (broken) {
            return;
        }
        pass(g, true);
    }

    private void drawChrome(GuiGraphicsExtractor g) {
        if (broken) {
            return;
        }
        pass(g, false);
    }

    private void pass(GuiGraphicsExtractor g, boolean backdrop) {
        Window win = mc.getWindow();
        int w = win.getWidth();
        int h = win.getHeight();
        if (w < 1 || h < 1) {
            return;
        }
        long now = System.nanoTime();
        float inv = 1f / guiScale;
        g.pose().pushMatrix();
        g.pose().scale(inv, inv);
        boolean began = false;
        try {
            ensureSurface(Theme.scaleFor(h));
            surface.begin(g, w, h);
            began = true;
            surface.syncPreferences(now);
            Canvas c = surface.canvas();
            if (backdrop || !fresh) {
                layout(c, w, h, now);
            }
            if (backdrop) {
                fresh = true;
                paintBackdrop(c);
            } else {
                fresh = false;
                paintChrome(c, now);
            }
        } catch (Throwable t) {
            fail(backdrop ? "title backdrop" : "title chrome", t);
        } finally {
            try {
                if (began) {
                    surface.end();
                }
            } catch (Throwable t) {
                fail("title draw end", t);
            }
            g.pose().popMatrix();
        }
        if (surface != null) {
            surface.afterFrame();
            // the atlas canvas gave up and the plain one took over, which draws our pictures as grey boxes
            if (!(surface.canvas() instanceof McCanvas)) {
                broken = true;
            }
        }
    }

    private void ensureSurface(float s) {
        if (surface != null && s == scale) {
            return;
        }
        surface = new KitSurface(s);
        scale = s;
        subStyle = new TextStyle(Weight.MEDIUM, r(18));
        versionStyle = new TextStyle(Weight.MEDIUM, r(14));
        copyStyle = new TextStyle(Weight.REGULAR, r(14));
        labelStyle = new TextStyle(Weight.MEDIUM, r(14));
        tipStyle = new TextStyle(Weight.REGULAR, r(13));
    }

    private int r(float design) {
        return Math.round(design * scale);
    }

    // ----- layout, ported from title.js drawTitle

    private void layout(Canvas c, int w, int h, long now) {
        width = w;
        height = h;
        boolean anim = Anim.enabled;
        float et = !anim || entryStart < 0L ? Float.POSITIVE_INFINITY : (now - entryStart) / 1_000_000f;
        eBrand = Easing.outCubic(et / 800f);
        eSub = Easing.outCubic((et - 120f) / 800f);
        eDock = Easing.outCubic((et - 200f) / 700f);
        eCorner = Easing.outCubic((et - 400f) / 600f);

        // while the boot overlay is up the screen is drawn with a mouse at 0,0, so the dock holds still till it's gone
        present = mc.gui.overlay() == null;
        if (present) {
            MenuMusic.markTitleShown();
        }
        mouseX = present ? KitSurface.pointerX() : w / 2f;
        mouseY = present ? KitSurface.pointerY() : h / 2f;
        followParallax(now, anim);
        pbx = Math.round(parBx * scale);
        pby = Math.round(parBy * scale);
        int pdx = Math.round(parDx * scale);
        int pdy = Math.round(parDy * scale);

        layoutDock(w, h, now, pdx, pdy);
        layoutCorners(c, w, h);

        hover = null;
        if (present) {
            for (Piece p : dock) {
                if (p.contains(mouseX, mouseY)) {
                    hover = p;
                    break;
                }
            }
            if (hover == null) {
                hover = hit(language);
            }
            if (hover == null) {
                hover = hit(accessibility);
            }
            if (hover == null) {
                hover = hit(music);
            }
            if (hover == null) {
                hover = hit(menuSwitch);
            }
            if (hover == null) {
                hover = hit(copyright);
            }
        }
        sound(present);
    }

    // a soft note when the pointer or tab lands on something new. dock tiles climb a pentatonic scale left to right so
    // a sweep along the dock plays a little run, the three corner buttons climb along with it. tracked by id, not by
    // Piece: a resize rebuilds every piece and that must not replay the note you're already on. the first frame after
    // the boot overlay only takes note of where the mouse is, a menu opening under a resting cursor shouldn't ding
    private void sound(boolean present) {
        if (!present) {
            soundPrimed = false;
            return;
        }
        Piece target = hover != null ? hover : focused();
        String id = target == null ? null : target.id;
        if (!soundPrimed) {
            soundPrimed = true;
            soundedId = id;
            return;
        }
        if (Objects.equals(id, soundedId)) {
            return;
        }
        soundedId = id;
        // disabled tiles stay quiet like they stay small, and the copyright line is just text
        if (target == null || target == copyright || !target.tile.target.active) {
            return;
        }
        int step = dock.indexOf(target);
        MenuSounds.hover(MenuSounds.note(step >= 0 ? step : target == language ? 3 : target == accessibility ? 4 : 5));
    }

    private Piece hit(Piece p) {
        return p != null && p.contains(mouseX, mouseY) ? p : null;
    }

    private void followParallax(long now, boolean anim) {
        if (!anim) {
            parBx = parBy = parDx = parDy = 0f;
            parLast = 0L;
            return;
        }
        float nx = Math.max(-1f, Math.min(1f, (mouseX - width / 2f) / (width / 2f)));
        float ny = Math.max(-1f, Math.min(1f, (mouseY - height / 2f) / (height / 2f)));
        float tbx = 8f * nx;
        float tby = 5f * ny;
        float tdx = 3f * nx;
        float tdy = 2f * ny;
        if (parLast == 0L) {
            parBx = tbx;
            parBy = tby;
            parDx = tdx;
            parDy = tdy;
        } else {
            float k = 1f - (float) Math.exp(-((now - parLast) / 1_000_000.0) / 120.0);
            parBx += (tbx - parBx) * k;
            parBy += (tby - parBy) * k;
            parDx += (tdx - parDx) * k;
            parDy += (tdy - parDy) * k;
        }
        parLast = now;
    }

    private void layoutDock(int w, int h, long now, int pdx, int pdy) {
        int n = dock.size();
        divBefore = -1;
        for (int i = 1; i < n; i++) {
            if (dock.get(i).role == Role.QUIT) {
                divBefore = i;
            }
        }
        tileDesign = DockLayout.tileDesign(n, divBefore > 0, w, scale);
        tPx = r(tileDesign);
        pad = r(DockLayout.PAD);
        int gap = r(DockLayout.GAP);
        int margin = r(DockLayout.DIVIDER_MARGIN);
        int pillH = 2 * pad + tPx;
        int dockDy = r(40f * (1f - eDock));
        pillBottom = h - r(DockLayout.BOTTOM) + pdy + dockDy;
        pillTop = pillBottom - pillH;
        int[] restSizes = new int[n];
        Arrays.fill(restSizes, tPx);
        DockLayout.Row rest = DockLayout.row(restSizes, pad, gap, margin, divBefore, w, pdx);
        inBand = present && mouseY >= pillTop - r(DockLayout.BAND) && mouseY <= pillBottom;
        int[] sizes = new int[n];
        for (int i = 0; i < n; i++) {
            Piece p = dock.get(i);
            p.tile.mirror();
            float target = 1f;
            if (inBand && p.tile.target.active) {
                target = DockLayout.magnify(mouseX, rest.xs()[i] + tPx / 2f, scale);
            }
            Spring sp = springs.computeIfAbsent(p.id, k -> new Spring(1f));
            sp.set(target, now, DockLayout.SPRING_MS);
            sizes[i] = Math.round(tPx * sp.value(now));
        }
        row = DockLayout.row(sizes, pad, gap, margin, divBefore, w, pdx);
        for (int i = 0; i < n; i++) {
            Piece p = dock.get(i);
            int s = sizes[i];
            p.x = row.xs()[i];
            p.y = pillBottom - pad - s;
            p.w = s;
            p.h = s;
            p.r = Math.round(s * 0.234f);
            p.tile.place(p.x, p.y, p.w, p.h);
        }
    }

    // top right, left to right: language, accessibility, music hugging the corner. the update card has the top left
    private void layoutCorners(Canvas c, int w, int h) {
        int b = r(40);
        int inset = r(24);
        int gap = r(10);
        int musicX = w - inset - b;
        int a11yX = musicX - gap - b;
        int langX = a11yX - gap - b;
        syncMusic();
        syncMenuSwitch();
        corner(music, musicX, inset, b);
        corner(accessibility, a11yX, inset, b);
        corner(language, langX, inset, b);
        int ch = Math.round(c.lineHeight(copyStyle));
        if (menuSwitch != null) {
            // the row above the copyright line. the dock's pill ends 72 design px up (70 with the parallax) and this
            // row's top is about 65 up, so once the entry animation has lifted the dock the two can't touch
            int sh = r(SWITCH_H);
            int sw = (int) Math.ceil(c.measure(menuSwitch.label, copyStyle)) + r(SWITCH_GAP) + r(TRACK_W);
            menuSwitch.tile.mirror();
            menuSwitch.x = w - inset - sw;
            menuSwitch.y = h - inset - ch - r(4) - sh;
            menuSwitch.w = sw;
            menuSwitch.h = sh;
            menuSwitch.r = sh / 2;
            menuSwitch.tile.place(menuSwitch.x, menuSwitch.y, sw, sh);
        }
        if (copyright != null) {
            copyright.tile.mirror();
            int cw = (int) Math.ceil(c.measure(copyright.label, copyStyle));
            copyright.x = w - inset - cw;
            copyright.y = h - inset - ch;
            copyright.w = cw;
            copyright.h = ch;
            copyright.r = 0;
            copyright.tile.place(copyright.x, copyright.y, cw, ch);
        }
    }

    private static void corner(Piece p, int x, int y, int b) {
        if (p == null) {
            return;
        }
        p.tile.mirror();
        p.x = x;
        p.y = y;
        p.w = b;
        p.h = b;
        p.r = b / 2;
        p.tile.place(x, y, b, b);
    }

    // ----- paint

    private void paintBackdrop(Canvas c) {
        if (shade == null) {
            shade = BrandArt.shade(SHADE_W, SHADE_H);
        }
        c.image(shade, 0, 0, width, height, WHITE);

        int ip = r(72);
        if (icon == null || iconPx != ip) {
            McAtlases.release(icon);
            icon = BrandArt.logo(ip);
            iconPx = ip;
        }
        int wp = r(88);
        if (word == null || wordPx != wp) {
            McAtlases.release(word);
            word = BrandArt.wordmark(McAtlases.get().fonts, wp);
            wordPx = wp;
        }
        String sub = "for Minecraft " + SharedConstants.getCurrentVersion().name();
        int subW = (int) Math.ceil(c.measure(sub, subStyle));
        int subH = Math.round(c.lineHeight(subStyle));
        int blockH = ip + r(20) + word.height() + r(8) + subH;
        int top = Math.round(height * 0.38f - blockH / 2f) + pby;
        int dyBrand = r(18f * (1f - eBrand));
        int dySub = r(18f * (1f - eSub));
        c.image(icon, Math.round(width / 2f - ip / 2f) + pbx, top + dyBrand, ip, ip, Theme.alpha(WHITE, eBrand));
        int wy = top + ip + r(20);
        c.image(word, Math.round(width / 2f - word.width() / 2f) + pbx, wy + dyBrand, word.width(), word.height(),
                Theme.alpha(WHITE, eBrand));
        float subTop = wy + word.height() + r(8) + dySub;
        c.text(sub, Math.round(width / 2f - subW / 2f) + pbx, subTop + c.ascent(subStyle), subStyle,
                Theme.fade(SUBTITLE, eSub));
    }

    private void paintChrome(Canvas c, long now) {
        paintDock(c);
        paintCorners(c);
        paintLines(c);
        paintMenuSwitch(c);
        Piece focus = focused();
        paintRing(c, focus);
        paintLabel(c, focus, now);
    }

    private void paintDock(Canvas c) {
        if (dock.isEmpty() || row == null) {
            return;
        }
        int pillH = pillBottom - pillTop;
        int pillR = Math.min(r(26), pillH / 2);
        // the kit's shadow darkens under the box too and the glass shows it through its 14%. the mock does the same
        c.shadow(row.x0(), pillTop, row.width(), pillH, pillR, Theme.fade(SHADOW, eDock));
        c.fillRoundRect(row.x0(), pillTop, row.width(), pillH, pillR, Theme.fade(GLASS, eDock));
        c.strokeRoundRect(row.x0(), pillTop, row.width(), pillH, pillR, Theme.fade(HAIRLINE, eDock));
        if (divBefore > 0) {
            int divH = r(40f * tileDesign / 64f);
            c.fillRect(row.dividerX(), Math.round(pillTop + pillH / 2f - divH / 2f), 1, divH, Theme.fade(HAIRLINE, eDock));
        }
        ensureTileBakes();
        for (Piece p : dock) {
            boolean off = !p.tile.target.active;
            boolean pressed = p.tile.held && !off;
            int s = p.w;
            int ds = pressed ? Math.round(s * 0.94f) : s;
            int ox = p.x + Math.round((s - ds) / 2f);
            int oy = p.y + Math.round((s - ds) / 2f);
            // every draw fades on its own like FadeCanvas, so a 0.4 tile shows its gradient through the white glyph
            float ta = eDock * (off ? 0.4f : 1f);
            PixelImage img = tileImage(off ? BrandArt.grey(p.top) : p.top, off ? BrandArt.grey(p.bottom) : p.bottom);
            c.image(img, ox, oy, ds, ds, Theme.alpha(WHITE, ta));
            if (pressed) {
                c.fillRoundRect(ox, oy, ds, ds, Math.round(ds * 0.234f), Theme.fade(PRESSED, ta));
            }
            int gp = Math.round(ds * 0.47f);
            c.icon(p.icon, ox + Math.round((ds - gp) / 2f), oy + Math.round((ds - gp) / 2f), gp, Theme.alpha(WHITE, ta));
        }
    }

    // each gradient baked once at the biggest a tile gets, drawn smaller through the linear sampler. a new window
    // size means new bakes, the old textures go on the delayed close
    private void ensureTileBakes() {
        int want = (int) Math.ceil(tPx * (1f + DockLayout.MAGNIFY));
        if (want == tileBakePx) {
            return;
        }
        for (PixelImage old : tiles.values()) {
            McAtlases.release(old);
        }
        tiles.clear();
        tileBakePx = want;
    }

    private PixelImage tileImage(int top, int bottom) {
        long key = ((long) top << 32) | (bottom & 0xFFFFFFFFL);
        PixelImage img = tiles.get(key);
        if (img == null) {
            img = BrandArt.tile(top, bottom, tileBakePx);
            tiles.put(key, img);
        }
        return img;
    }

    private void paintCorners(Canvas c) {
        glass(c, language);
        glass(c, accessibility);
        glass(c, music);
    }

    private void glass(Canvas c, Piece p) {
        if (p == null) {
            return;
        }
        c.shadow(p.x, p.y, p.w, p.h, p.r, Theme.fade(SHADOW, eCorner));
        c.fillRoundRect(p.x, p.y, p.w, p.h, p.r, Theme.fade(hover == p ? GLASS_HOVER : GLASS, eCorner));
        c.strokeRoundRect(p.x, p.y, p.w, p.h, p.r, Theme.fade(HAIRLINE, eCorner));
        int gp = r(20);
        c.icon(p.icon, p.x + Math.round((p.w - gp) / 2f), p.y + Math.round((p.h - gp) / 2f), gp, Theme.alpha(WHITE, eCorner));
    }

    private void paintLines(Canvas c) {
        int inset = r(24);
        String v = BuildInfo.version();
        String line = v == null || v.isBlank() || BuildInfo.UNKNOWN.equals(v) ? "Soprano" : "Soprano " + v;
        int vh = Math.round(c.lineHeight(versionStyle));
        c.text(line, inset, height - inset - vh + c.ascent(versionStyle), versionStyle, Theme.fade(VERSION, eCorner));
        Piece p = copyright;
        if (p == null) {
            return;
        }
        int col = Theme.fade(COPYRIGHT, eCorner);
        float asc = c.ascent(copyStyle);
        c.text(p.label, p.x, p.y + asc, copyStyle, col);
        if (hover == p || focused() == p) {
            int th = Math.max(1, Math.round(scale));
            c.fillRect(p.x, p.y + Math.round(asc) + th, p.w, th, col);
        }
    }

    // the kit's Toggle at copyright line size, plain text next to it: this is a preference, not a seventh dock tile.
    // it never slides, a press swaps the whole screen before there'd be a frame to slide in
    private void paintMenuSwitch(Canvas c) {
        Piece p = menuSwitch;
        if (p == null) {
            return;
        }
        boolean lit = hover == p || focused() == p;
        c.text(p.label, p.x, p.y + Math.round((p.h - c.lineHeight(copyStyle)) / 2f) + c.ascent(copyStyle), copyStyle,
                Theme.fade(lit ? WHITE : COPYRIGHT, eCorner));
        int tw = r(TRACK_W);
        int th = r(TRACK_H);
        int tx = p.x + p.w - tw;
        int ty = p.y + Math.round((p.h - th) / 2f);
        if (menuOn) {
            c.fillRoundRect(tx, ty, tw, th, th / 2f, Theme.fade(lit ? Theme.ACCENT_HOVER : Theme.ACCENT, eCorner));
        } else {
            c.fillRoundRect(tx, ty, tw, th, th / 2f, Theme.fade(lit ? GLASS_HOVER : GLASS, eCorner));
            c.strokeRoundRect(tx, ty, tw, th, th / 2f, Theme.fade(HAIRLINE, eCorner));
        }
        int knob = r(KNOB);
        int gap = Math.round((th - knob) / 2f);
        int kx = menuOn ? tx + tw - gap - knob : tx + gap;
        c.fillRoundRect(kx, ty + gap, knob, knob, knob / 2f, Theme.fade(Theme.ON_ACCENT, eCorner));
    }

    // keyboard focus only: a click focuses the widget too, and that must not put a ring on it
    private Piece focused() {
        if (!mc.getLastInputType().isKeyboard()) {
            return null;
        }
        for (Piece p : dock) {
            if (p.tile.isFocused()) {
                return p;
            }
        }
        if (language != null && language.tile.isFocused()) {
            return language;
        }
        if (accessibility != null && accessibility.tile.isFocused()) {
            return accessibility;
        }
        if (music != null && music.tile.isFocused()) {
            return music;
        }
        if (menuSwitch != null && menuSwitch.tile.isFocused()) {
            return menuSwitch;
        }
        if (copyright != null && copyright.tile.isFocused()) {
            return copyright;
        }
        return null;
    }

    // two stacked 1 px strokes, 3 px out from the thing. the copyright line shows focus with its underline instead
    private void paintRing(Canvas c, Piece p) {
        if (p == null || p == copyright) {
            return;
        }
        boolean corner = p == language || p == accessibility || p == music || p == menuSwitch;
        int col = Theme.fade(RING, corner ? eCorner : eDock);
        int off = r(3);
        for (int k = 2; k >= 1; k--) {
            int o = off + k;
            c.strokeRoundRect(p.x - o, p.y - o, p.w + 2 * o, p.h + 2 * o, p.r + o, col);
        }
    }

    private void paintLabel(Canvas c, Piece focus, long now) {
        Piece lid = labelled(hover) ? hover : labelled(focus) ? focus : null;
        if (lid != labelPiece) {
            labelPiece = lid;
            labelSince = now;
        }
        if (lid == null) {
            return;
        }
        boolean below = lid == language || lid == accessibility || lid == music;
        String tip = lid.mpTip && !lid.tile.target.active ? mpReason : null;
        int px10 = r(10);
        int py5 = r(5);
        int lgap = r(2);
        int l1w = (int) Math.ceil(c.measure(lid.label, labelStyle));
        int l1h = Math.round(c.lineHeight(labelStyle));
        List<String> lines = tip == null ? List.of() : wrap(c, tip, tipStyle, r(260));
        int l2h = Math.round(c.lineHeight(tipStyle));
        int innerW = l1w;
        for (String s : lines) {
            innerW = Math.max(innerW, (int) Math.ceil(c.measure(s, tipStyle)));
        }
        int lw = innerW + 2 * px10;
        int lh = l1h + 2 * py5 + (lines.isEmpty() ? 0 : lgap + l2h * lines.size());
        int lx = Math.round(lid.x + lid.w / 2f - lw / 2f);
        int lo = r(8);
        int hi = width - r(8) - lw;
        lx = lx < lo ? lo : Math.min(lx, hi);
        // above the tallest tile, not just this one: a hovered disabled tile stays small while its neighbours grow,
        // and the label sat right on their heads
        int rowTop = lid.y;
        if (!below && (lid == hover || inBand)) {
            for (Piece p : dock) {
                rowTop = Math.min(rowTop, p.y);
            }
        }
        int ly = below ? lid.y + lid.h + r(8) : rowTop - r(10) - lh;
        float la = Anim.enabled ? Math.min(1f, (now - labelSince) / 120_000_000f) : 1f;
        float a = la * (below ? eCorner : eDock);
        c.fillRoundRect(lx, ly, lw, lh, r(8), Theme.fade(LABEL_BG, a));
        c.text(lid.label, lx + px10, ly + py5 + c.ascent(labelStyle), labelStyle, Theme.alpha(WHITE, a));
        float y = ly + py5 + l1h + lgap;
        for (String s : lines) {
            c.text(s, lx + px10, y + c.ascent(tipStyle), tipStyle, Theme.fade(TIP, a));
            y += l2h;
        }
    }

    private boolean labelled(Piece p) {
        // the switch has its name written right next to it, a label on top would just say it twice
        return p != null && p != copyright && p != menuSwitch;
    }

    // greedy word wrap, the same one the mock uses for the disabled reason
    private static List<String> wrap(Canvas c, String s, TextStyle style, float maxW) {
        List<String> lines = new ArrayList<>();
        String cur = "";
        for (String w : s.split(" ")) {
            String next = cur.isEmpty() ? w : cur + " " + w;
            if (!cur.isEmpty() && c.measure(next, style) > maxW) {
                lines.add(cur);
                cur = w;
            } else {
                cur = next;
            }
        }
        if (!cur.isEmpty()) {
            lines.add(cur);
        }
        return lines;
    }
}
