package adris.altoclef.ui.gui.kit;

// smooth scroll state for anything taller than its box. the target jumps, the offset eases after it, and the scrollbar
// fades in while you scroll or hover and out 600 ms after you stop
public final class Scroller {

    public static final long BAR_HOLD_NANOS = Anim.ms(600);

    private float contentH;
    private float viewH;
    private float target;
    private final Spring pos = new Spring(0f);
    private final Anim vis = new Anim(0f);
    private long lastTouch = Long.MIN_VALUE / 2;

    public void setSizes(float contentHeight, float viewportHeight) {
        contentH = Math.max(0f, contentHeight);
        viewH = Math.max(0f, viewportHeight);
        float max = maxOffset();
        if (target > max) {
            target = max;
            pos.snap(max);
        }
    }

    public float contentHeight() {
        return contentH;
    }

    public float viewportHeight() {
        return viewH;
    }

    public float maxOffset() {
        return Math.max(0f, contentH - viewH);
    }

    public boolean scrollable() {
        return contentH > viewH + 0.5f;
    }

    public float target() {
        return target;
    }

    public float offset(long nowNanos) {
        return clamp(pos.value(nowNanos));
    }

    public boolean running(long nowNanos) {
        return pos.running(nowNanos);
    }

    public void scrollBy(float delta, long nowNanos) {
        scrollTo(target + delta, nowNanos);
    }

    public void scrollTo(float offset, long nowNanos) {
        float t = clamp(offset);
        touch(nowNanos);
        if (t == target) {
            return;
        }
        target = t;
        pos.set(t, nowNanos, Theme.ANIM_FAST);
    }

    public void snapTo(float offset) {
        target = clamp(offset);
        pos.snap(target);
    }

    // pull the span [top, bottom] (content coordinates) into view with the least movement
    public void ensureVisible(float top, float bottom, long nowNanos) {
        if (top < target) {
            scrollTo(top, nowNanos);
        } else if (bottom > target + viewH) {
            scrollTo(bottom - viewH, nowNanos);
        }
    }

    public static float thumbHeight(float trackH, float contentH, float viewH, float minThumb) {
        if (contentH <= viewH || contentH <= 0f) {
            return trackH;
        }
        return Math.min(trackH, Math.max(minThumb, trackH * viewH / contentH));
    }

    public float thumbHeight(float trackH, float minThumb) {
        return thumbHeight(trackH, contentH, viewH, minThumb);
    }

    // top of the thumb inside a track that starts at 0
    public float thumbTop(float trackH, float minThumb, long nowNanos) {
        float travel = trackH - thumbHeight(trackH, minThumb);
        float max = maxOffset();
        if (max <= 0f || travel <= 0f) {
            return 0f;
        }
        return travel * offset(nowNanos) / max;
    }

    // thumb moved dy pixels along the track: content moves the matching, much larger, amount. no easing, the
    // thumb is under the mouse and should stay there
    public void dragThumb(float dy, float trackH, float minThumb, long nowNanos) {
        float travel = trackH - thumbHeight(trackH, minThumb);
        if (travel <= 0f) {
            return;
        }
        touch(nowNanos);
        snapTo(target + dy * maxOffset() / travel);
    }

    // click on the bare track: centre the thumb on the click
    public void jumpToTrack(float trackPos, float trackH, float minThumb, long nowNanos) {
        float thumbH = thumbHeight(trackH, minThumb);
        float travel = trackH - thumbH;
        if (travel <= 0f) {
            return;
        }
        float frac = Math.max(0f, Math.min(1f, (trackPos - thumbH / 2f) / travel));
        scrollTo(frac * maxOffset(), nowNanos);
    }

    public void touch(long nowNanos) {
        lastTouch = nowNanos;
    }

    public float visibility(long nowNanos, boolean hovering) {
        boolean show = scrollable() && (hovering || nowNanos - lastTouch < BAR_HOLD_NANOS);
        vis.set(show ? 1f : 0f, nowNanos, Theme.ANIM);
        return vis.value(nowNanos);
    }

    private float clamp(float v) {
        return Math.max(0f, Math.min(maxOffset(), v));
    }
}
