package adris.altoclef.ui.gui.kit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

// what the long scrolling things share: an item list, a Scroller, the fading scrollbar, wheel and thumb and page keys,
// and the "new rows fade in" bookkeeping. subclasses decide what a row is and where it goes
public abstract class VirtualView<T> extends Control implements FocusOnClick {

    private static final long NEW = -1L;
    private static final long DONE = Long.MIN_VALUE;

    protected List<T> items = List.of();
    protected final Scroller scroller = new Scroller();
    protected int wheelRows = 3;
    protected int pressedIndex = -1;
    private Object pressedKey;
    protected final FadeCanvas fadeCanvas = new FadeCanvas();
    private Function<T, Object> keyFn = t -> t;
    private Map<Object, Long> shown = new HashMap<>();
    private boolean stampPending;
    private boolean draggingThumb;
    private String emptyText;
    private boolean scrollbarVisible = true;

    public void setScrollbarVisible(boolean visible) {
        scrollbarVisible = visible;
    }

    protected VirtualView(Theme theme) {
        super(theme);
    }

    // an item's identity across refreshes: new rows fade in by it, a click belongs to the key you pressed, and a
    // settings row keeps focus while its key stays. the default is the item itself (equals), so a list rebuilt as fresh
    // objects every tick MUST set an id function or its clicks and focus will be dropped
    public void setKeyFunction(Function<T, Object> fn) {
        keyFn = fn == null ? t -> t : fn;
    }

    public void setEmptyText(String s) {
        emptyText = s;
    }

    public List<T> items() {
        return items;
    }

    protected final Object keyOf(T item) {
        return keyFn.apply(item);
    }

    // remember what was pressed by identity, not by index: a list that refreshes between mouse down and up may have
    // shuffled, and the click belongs to the thing you pressed
    protected final void press(int index) {
        pressedIndex = index;
        pressedKey = index >= 0 && index < items.size() ? keyFn.apply(items.get(index)) : null;
    }

    protected final boolean releasedOn(int index) {
        return pressedIndex >= 0 && index >= 0 && index < items.size()
                && Objects.equals(pressedKey, keyFn.apply(items.get(index)));
    }

    // copies the list: the caller keeps reusing theirs, and a mid draw mutation would otherwise tear a frame
    public void setItems(List<T> next) {
        List<T> list = next == null ? List.of() : new ArrayList<>(next);
        Map<Object, Long> fresh = new HashMap<>(Math.max(16, list.size() * 2));
        for (T t : list) {
            Object k = keyFn.apply(t);
            Long old = shown.get(k);
            fresh.put(k, old != null ? old : NEW);
        }
        shown = fresh;
        items = list;
        stampPending = true;
        itemsChanged();
        resize();
    }

    protected void itemsChanged() {
    }

    // content height in pixels for the current items and width
    protected abstract int contentHeight();

    // how far one "row" moves the content
    protected abstract int step();

    protected final void resize() {
        scroller.setSizes(contentHeight(), h);
    }

    @Override
    protected void layout() {
        resize();
    }

    @Override
    public boolean focusable() {
        return visible;
    }

    // gives each newly appeared, currently visible item its start time. everything else that is new but off screen
    // counts as already shown, or scrolling would replay the fade for rows you scrolled to
    protected final void stampVisible(int first, int lastExclusive, long nowNanos) {
        if (!stampPending) {
            return;
        }
        stampPending = false;
        boolean anim = Anim.enabled;
        for (int i = Math.max(0, first); i < lastExclusive && i < items.size(); i++) {
            Object k = keyFn.apply(items.get(i));
            Long s = shown.get(k);
            if (s != null && s == NEW) {
                shown.put(k, anim ? nowNanos : DONE);
            }
        }
        shown.replaceAll((k, v) -> v == NEW ? DONE : v);
    }

    // 0..1 for how far into its fade a row is
    protected final float fadeOf(T item, long nowNanos) {
        Long s = shown.get(keyFn.apply(item));
        if (s == null || s == DONE) {
            return 1f;
        }
        float t = (nowNanos - s) / (float) Anim.ms(Theme.ANIM_FAST);
        return t >= 1f ? 1f : Easing.outCubic(Math.max(0f, t));
    }

    // a canvas to draw one row with: the real one when it is fully here, a fading sliding one while it arrives
    protected final Canvas rowCanvas(Canvas c, float fade) {
        if (fade >= 1f) {
            return c;
        }
        return fadeCanvas.reset(c, fade, (1f - fade) * theme.px(6));
    }

    // the strip at the right of a list where the scrollbar lives, in design px. one number, so a card drawn around the
    // rows and the rows themselves cannot drift apart by a pixel
    public static final int GUTTER = 10;

    protected final int gutter() {
        return theme.px(GUTTER);
    }

    private int trackY() {
        return y + theme.px(4);
    }

    private int trackH() {
        return h - theme.px(8);
    }

    private int minThumb() {
        return theme.px(24);
    }

    protected final void drawEmpty(Canvas c) {
        if (!items.isEmpty() || emptyText == null || emptyText.isEmpty()) {
            return;
        }
        float tw = c.measure(emptyText, theme.body);
        c.text(emptyText, x + (w - tw) / 2f, baseline(c, theme.body, y, h), theme.body, theme.color(Theme.TEXT_3));
    }

    protected final void drawScrollbar(Canvas c) {
        if (!scrollbarVisible) {
            return;
        }
        float vis = scroller.visibility(now, hovered);
        if (vis < 0.01f || !scroller.scrollable()) {
            return;
        }
        float th = scroller.thumbHeight(trackH(), minThumb());
        float tt = scroller.thumbTop(trackH(), minThumb(), now);
        float bw = theme.px(Theme.SCROLLBAR_W);
        float bx = x + w - bw - theme.px(3);
        int col = Theme.lerp(theme.color(Theme.TEXT_3), theme.color(Theme.TEXT_2), draggingThumb ? 1f : 0f);
        c.fillRoundRect(bx, trackY() + tt, bw, th, bw / 2f, Theme.fade(col, vis));
    }

    private boolean inBar(float mx) {
        return scrollbarVisible && scroller.scrollable() && mx >= x + w - theme.px(14);
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (!contains(mx, my)) {
            return false;
        }
        if (button == 0 && inBar(mx)) {
            float th = scroller.thumbHeight(trackH(), minThumb());
            float tt = scroller.thumbTop(trackH(), minThumb(), now) + trackY();
            if (my >= tt && my <= tt + th) {
                draggingThumb = true;
                scroller.touch(now);
            } else {
                scroller.jumpToTrack(my - trackY(), trackH(), minThumb(), now);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDrag(float mx, float my, int button, float dx, float dy) {
        if (draggingThumb) {
            scroller.dragThumb(dy, trackH(), minThumb(), now);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseUp(float mx, float my, int button, int mods) {
        boolean was = draggingThumb;
        draggingThumb = false;
        return was;
    }

    @Override
    public boolean scroll(float mx, float my, float dy) {
        if (!contains(mx, my) || !scroller.scrollable()) {
            return false;
        }
        scroller.scrollBy(-dy * wheelRows * step(), now);
        return true;
    }

    @Override
    public boolean keyDown(int key, int mods) {
        if (!focused) {
            return false;
        }
        float page = Math.max(step(), h - step());
        switch (key) {
            case Keys.PAGE_DOWN -> scroller.scrollBy(page, now);
            case Keys.PAGE_UP -> scroller.scrollBy(-page, now);
            case Keys.HOME -> scroller.scrollTo(0f, now);
            case Keys.END -> scroller.scrollTo(scroller.maxOffset(), now);
            default -> {
                return false;
            }
        }
        return true;
    }

    public Scroller scroller() {
        return scroller;
    }
}
