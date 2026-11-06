package adris.altoclef.ui.gui.kit;

// the whole look in one place. colours are argb, sizes are design pixels at 1080p and go through px() so the panel is
// the same thing at every window size and minecraft gui scale. three surfaces (sidebar, panel, card), one blue for
// anything selected or on, red only for danger and the end of a path. depth is a surface step and a hairline, not a
// shadow: the only shadows are under the panel itself and under things that really float (menus, toasts, a thumb)
public final class Theme {

    // these are also the default dark palette. resolve a token through color() before blending or fading it. every
    // dark value has to be unique, color() switches on it. the greys were picked with a contrast calculator: TEXT_3
    // clears 4.5:1 on a card and on a hovered row, white clears it on ACCENT and on ACCENT_HOVER (which is why hover
    // goes a step darker, not lighter), DANGER clears it on a raised button
    // lighter than it used to be because the world behind it is blurred now, the scrim only has to settle it down
    public static final int SCRIM = 0x52000000;
    public static final int SIDEBAR = 0xFF1B1B1D;
    public static final int PANEL = 0xFF232325;
    public static final int SURFACE = 0xFF2C2C2E;
    public static final int RAISED = 0xFF3A3A3C;
    public static final int FIELD = 0xFF1C1C1E;
    public static final int HOVER = 0xFF353537;
    public static final int PRESSED = 0xFF424244;
    public static final int SELECTED = 0xFF5C5C60;
    public static final int HAIRLINE = 0xFF37373A;
    public static final int HAIRLINE_STRONG = 0xFF6B6B70;
    public static final int TEXT = 0xFFF5F5F7;
    public static final int TEXT_2 = 0xFFAEAEB4;
    public static final int TEXT_3 = 0xFFA0A0A5;
    public static final int ACCENT = 0xFF0A73E8;
    public static final int ACCENT_HOVER = 0xFF0B6BD6;
    public static final int ACCENT_PRESSED = 0xFF0A64CC;
    public static final int ACCENT_SOFT = 0xFF22354D;
    public static final int ACCENT_TEXT = 0xFF5AA9FF;
    public static final int ON_ACCENT = 0xFFFFFFFF;
    public static final int DANGER = 0xFFFF8080;
    public static final int DANGER_FILL = 0xFF3A2A2C;
    public static final int DANGER_PRESSED = 0xFF4A3134;
    public static final int SHADOW = 0x80000000;
    public static final int PATH_START = 0xFF30D158;
    public static final int PATH_END = 0xFFFF453A;

    // design sizes at scale 1
    public static final int SIDEBAR_W = 176;
    public static final int HEADER_H = 56;
    public static final int STATUS_H = 36;
    public static final int PANEL_RADIUS = 16;
    public static final int CARD_RADIUS = 12;
    public static final int ROW_RADIUS = 8;
    public static final int CONTROL_RADIUS = 7;
    public static final int ROW_H = 36;
    public static final int CONTROL_H = 30;
    public static final int ICON = 16;
    public static final int ICON_LG = 24;
    public static final int SCROLLBAR_W = 4;

    // spacing on a 4 grid. PAD is the margin inside the panel and inside a card, GAP sits between controls, SECTION
    // goes above a section label (it gets more room above than below, that is what makes it a heading)
    public static final int SPACE_1 = 4;
    public static final int SPACE_2 = 8;
    public static final int SPACE_3 = 12;
    public static final int PAD = 16;
    public static final int GAP = 8;
    public static final int SECTION = 24;
    public static final int SECTION_BELOW = 8;

    // animation lengths in ms. nothing bounces, nothing spins
    public static final int ANIM_FAST = 150;
    public static final int ANIM = 240;
    public static final int ANIM_SLOW = 320;

    public final float scale;
    public final TextMeasurer measurer;

    // title is the panel header, label is anything that names a control or a row, body is content and what you type,
    // small is the secondary line, smallLabel is a small thing that has to hold its own (chips, counts, section heads)
    public final TextStyle title;
    public final TextStyle label;
    public final TextStyle body;
    public final TextStyle small;
    public final TextStyle smallLabel;

    private final Anim appearance = new Anim(0f);
    private boolean dark = true;
    private float lightMix;

    public Theme(float scale, TextMeasurer measurer) {
        this.scale = scale;
        this.measurer = measurer;
        this.title = new TextStyle(Weight.SEMIBOLD, px(20));
        this.label = new TextStyle(Weight.MEDIUM, px(14));
        this.body = new TextStyle(Weight.REGULAR, px(14));
        this.small = new TextStyle(Weight.REGULAR, px(12));
        this.smallLabel = new TextStyle(Weight.MEDIUM, px(12));
    }

    public boolean dark() {
        return dark;
    }

    // loading a saved preference should not animate from the opposite appearance
    public void setDark(boolean value) {
        dark = value;
        lightMix = value ? 0f : 1f;
        appearance.snap(lightMix);
    }

    public void setDark(boolean value, long nowNanos) {
        dark = value;
        appearance.set(value ? 0f : 1f, nowNanos, ANIM_SLOW);
        update(nowNanos);
    }

    public void update(long nowNanos) {
        lightMix = appearance.value(nowNanos);
    }

    // every widget shares the same frame's blend; switching themes never rebuilds controls or loses an edit. light is
    // white cards on a grouped grey, the sidebar a step darker again, so the three surfaces separate without shadows
    public int color(int token) {
        if (lightMix <= 0f) {
            return token;
        }
        int light = switch (token) {
            case SCRIM -> 0x33000000;
            case SIDEBAR -> 0xFFE6E6EB;
            case PANEL -> 0xFFF2F2F7;
            case SURFACE -> 0xFFFFFFFF;
            case RAISED -> 0xFFE3E3E8;
            case FIELD -> 0xFFEAEAEF;
            case HOVER -> 0xFFE9E9EE;
            case PRESSED -> 0xFFDCDCE1;
            case SELECTED -> 0xFFFFFFFF;
            case HAIRLINE -> 0xFFD9D9DE;
            case HAIRLINE_STRONG -> 0xFFA9A9AE;
            case TEXT -> 0xFF1D1D1F;
            case TEXT_2 -> 0xFF5C5C62;
            case TEXT_3 -> 0xFF66666B;
            case ACCENT -> 0xFF0071E3;
            case ACCENT_HOVER -> 0xFF0062C8;
            case ACCENT_PRESSED -> 0xFF0053AD;
            case ACCENT_SOFT -> 0xFFDCEBFC;
            case ACCENT_TEXT -> 0xFF0066CC;
            case ON_ACCENT -> 0xFFFFFFFF;
            case DANGER -> 0xFFC20012;
            case DANGER_FILL -> 0xFFFBE5E7;
            case DANGER_PRESSED -> 0xFFF5CDD1;
            case SHADOW -> 0x2E000000;
            case PATH_START -> 0xFF248A3D;
            case PATH_END -> 0xFFD70015;
            default -> token;
        };
        return lightMix >= 1f ? light : lerp(token, light, lightMix);
    }

    // a grouped surface one step up from the panel with a hairline around it. raised is for the few that sit over
    // other content (the basket dock), and gets the one soft shadow
    public void card(Canvas c, float x, float y, float w, float h, float radius, boolean raised) {
        if (raised) {
            c.shadow(x, y, w, h, radius, fade(color(SHADOW), 0.5f));
        }
        c.fillRoundRect(x, y, w, h, radius, color(SURFACE));
        c.strokeRoundRect(x, y, w, h, radius, color(HAIRLINE));
    }

    // an input or a track: a quiet tinted fill, the opposite direction from a card. whoever draws it adds the focus ring
    public void field(Canvas c, float x, float y, float w, float h, float radius) {
        c.fillRoundRect(x, y, w, h, radius, color(FIELD));
        c.strokeRoundRect(x, y, w, h, radius, color(HAIRLINE));
    }

    // menus and toasts: a card that is really off the surface, so it gets the full shadow
    public void floating(Canvas c, float x, float y, float w, float h, float radius) {
        c.shadow(x, y, w, h, radius, color(SHADOW));
        c.fillRoundRect(x, y, w, h, radius, color(SURFACE));
        c.strokeRoundRect(x, y, w, h, radius, color(HAIRLINE_STRONG));
    }

    // a minecraft item icon is 16 px of texture: draw it at a whole multiple so the pixels stay crisp, and lay
    // out with this size in the preview too so the real icons do not shift anything
    public int itemIconPx() {
        return 16 * Math.max(1, Math.round(scale));
    }

    public int px(int design) {
        return Math.max(1, Math.round(design * scale));
    }

    public float pxf(float design) {
        return design * scale;
    }

    // quarter steps so the glyph atlases stay cached while the window is dragged around, clamped so a tiny window
    // still gets readable text and a 4k one does not get a billboard. 1234 instead of 1080 is the "a bit smaller"
    // ask: 1080p rounds to 1.0 anyway, but 900p lands on 0.75 instead of 0.875 and 1440p on 1.25 instead of 1.5
    public static float scaleFor(int windowHeightPx) {
        float raw = windowHeightPx / 1234f;
        float stepped = Math.round(raw * 4f) / 4f;
        return Math.max(0.75f, Math.min(2.0f, stepped));
    }

    // argb with a new alpha, 0..1
    public static int alpha(int argb, float a) {
        int al = Math.round(Math.max(0f, Math.min(1f, a)) * 255f);
        return (al << 24) | (argb & 0x00FFFFFF);
    }

    // scale the alpha that is already there
    public static int fade(int argb, float factor) {
        int al = (argb >>> 24);
        return alpha(argb, (al / 255f) * factor);
    }

    public static int lerp(int from, int to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = Math.round(((from >>> 24) & 255) + (((to >>> 24) & 255) - ((from >>> 24) & 255)) * t);
        int r = Math.round(((from >> 16) & 255) + (((to >> 16) & 255) - ((from >> 16) & 255)) * t);
        int g = Math.round(((from >> 8) & 255) + (((to >> 8) & 255) - ((from >> 8) & 255)) * t);
        int b = Math.round((from & 255) + ((to & 255) - (from & 255)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
