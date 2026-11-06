package adris.altoclef.ui.gui.kit;

import adris.altoclef.ui.gui.facade.Result;

import java.util.ArrayDeque;
import java.util.Deque;

// the little confirmation at the bottom of the content area: the human line, and under it the exact command that ran.
// one at a time, the rest wait in line. a failed result draws its line in red. it never takes a click
public class Toast extends Control {

    private static final long HOLD_NANOS = Anim.ms(3000);
    private static final int MAX_QUEUED = 3;

    private static final class Entry {
        final String line;
        final String command;
        final boolean ok;
        final float lineW;
        final float cmdW;
        long startNanos = -1;

        Entry(String line, String command, boolean ok, float lineW, float cmdW) {
            this.line = line;
            this.command = command;
            this.ok = ok;
            this.lineW = lineW;
            this.cmdW = cmdW;
        }
    }

    private final Deque<Entry> queue = new ArrayDeque<>();
    private final FadeCanvas fade = new FadeCanvas();

    public Toast(Theme theme) {
        super(theme);
    }

    public void show(Result r) {
        if (r == null) {
            return;
        }
        push(r.message(), r.command(), r.ok());
    }

    public void show(String line, String command) {
        push(line, command, true);
    }

    private void push(String line, String command, boolean ok) {
        String l = line == null ? "" : line;
        String cmd = null;
        if (command != null && !command.isBlank()) {
            cmd = command.startsWith("#") || command.startsWith("/") ? command : "#" + command;
        }
        if (l.isEmpty() && cmd == null) {
            return;
        }
        // a flood of results should not mean a minute of toasts
        // (the one on screen stays, the oldest one still waiting goes, so the newest message always gets its turn)
        while (queue.size() > MAX_QUEUED) {
            Entry showing = queue.pollFirst();
            queue.pollFirst();
            queue.addFirst(showing);
        }
        TextMeasurer m = theme.measurer;
        queue.addLast(new Entry(l, cmd, ok, m.measure(l, theme.body), cmd == null ? 0f : m.measure(cmd, theme.small)));
    }

    public int pending() {
        return queue.size();
    }

    public String currentLine() {
        Entry e = queue.peekFirst();
        return e == null ? null : e.line;
    }

    public boolean currentOk() {
        Entry e = queue.peekFirst();
        return e == null || e.ok;
    }

    public String currentCommand() {
        Entry e = queue.peekFirst();
        return e == null ? null : e.command;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        Entry e = queue.peekFirst();
        if (e == null) {
            return;
        }
        if (e.startNanos < 0) {
            e.startNanos = now;
        }
        long fadeN = Anim.enabled ? Anim.ms(Theme.ANIM) : 0L;
        long t = now - e.startNanos;
        if (t >= fadeN * 2 + HOLD_NANOS) {
            queue.pollFirst();
            return;
        }
        float a;
        float rise = 0f;
        if (t < fadeN) {
            float p = Easing.outCubic(t / (float) fadeN);
            a = p;
            rise = (1f - p) * theme.px(8);
        } else if (t < fadeN + HOLD_NANOS) {
            a = 1f;
        } else {
            a = 1f - Easing.outCubic((t - fadeN - HOLD_NANOS) / (float) fadeN);
        }

        int pad = theme.px(Theme.PAD);
        TextMeasurer m = theme.measurer;
        float maxW = Math.max(theme.px(120), w - 2 * pad);
        float inner = Math.min(maxW - 2 * pad, Math.max(e.lineW, e.cmdW));
        float bw = Math.max(theme.px(200), inner + 2 * pad);
        bw = Math.min(bw, maxW);
        float lh = m.lineHeight(theme.body);
        float sh = m.lineHeight(theme.small);
        float bh = 2 * theme.px(12) + lh + (e.command != null ? theme.px(4) + sh : 0f);
        float bx = x + (w - bw) / 2f;
        float by = y + h - pad - bh;
        float r = theme.pxf(Theme.CARD_RADIUS);

        Canvas g = fade.reset(c, a, rise);
        theme.floating(g, bx, by, bw, bh, r);
        float room = bw - 2 * pad;
        float ty = by + theme.px(12);
        String line = e.lineW <= room ? e.line : m.ellipsize(e.line, theme.body, room);
        g.text(line, bx + pad, ty + m.ascent(theme.body), theme.body, e.ok ? theme.color(Theme.TEXT) : theme.color(Theme.DANGER));
        if (e.command != null) {
            String cmd = e.cmdW <= room ? e.command : m.ellipsize(e.command, theme.small, room);
            g.text(cmd, bx + pad, ty + lh + theme.px(4) + m.ascent(theme.small), theme.small, theme.color(Theme.TEXT_2));
        }
    }

    @Override
    public boolean contains(float px, float py) {
        return false;
    }
}
