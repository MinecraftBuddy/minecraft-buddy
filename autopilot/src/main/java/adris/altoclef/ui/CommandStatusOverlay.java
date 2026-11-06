package adris.altoclef.ui;

import baritone.Baritone;
import adris.altoclef.AltoSettings;
import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.ARGB;

// the task tree in the top left. same staircase altoclef always drew (one line per task, each nested task 8px
// further right), just with a backdrop, a shadow and the line you actually care about in white
public class CommandStatusOverlay {

    // gui units, scaled by hudScale with everything else
    private static final int MARGIN = 4;
    private static final int PAD = 3;
    private static final int INDENT = 8;
    private static final int LINE = 10;
    private static final int GAP = 6;
    // more than this many task lines and the middle of the tree gets folded into "... N more"
    private static final int MAX_LINES = 10;
    private static final int FADE_MS = 200;
    // how long with no task before the timer starts over (the old 10 s)
    private static final long TIMER_RESET_MS = 10_000;

    // soprano's colorBestPathSoFar, so the hud and the path line look like one mod
    private static final int ACCENT = 0xFF4DA3FF;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    // minecraft's dark_gray (555555) vanishes on snow even with the backdrop, this one doesn't
    private static final int DIM = 0xFF8A8A8A;

    private record Line(int depth, String name, int nameColor, String state, int stateColor) {
    }

    private record Entry(String name, String state, boolean leaf) {
    }

    // laid out once per tick, drawn every frame
    private final List<Line> _lines = new ArrayList<>();
    private long _layoutTick = -1;
    private int _layoutWidth;
    private float _layoutScale;
    private int _width;
    private String _timer;
    private int _timerWidth;
    private int _timerX;

    private long _shownSince;
    private long _timeRunning;
    private long _lastTime;

    public void render(AltoClef mod, GuiGraphicsExtractor graphics) {
        Minecraft mc = Minecraft.getInstance();
        // f1 hides the hud and f3 lives in the same corner, it was there first
        if (mc.level == null || mc.gui.hud.isHidden() || mc.getDebugOverlay().showDebugScreen()) {
            return;
        }
        if (!Baritone.settings().altoShowTaskChains.value) {
            return;
        }
        float scale = AltoSettings.hudScale();
        long now = System.currentTimeMillis();
        long tick = mc.level.getGameTime();
        if (tick != _layoutTick || graphics.guiWidth() != _layoutWidth || scale != _layoutScale) {
            _layoutTick = tick;
            _layoutWidth = graphics.guiWidth();
            _layoutScale = scale;
            layout(mod, mc.font, now);
        }
        if (_lines.isEmpty()) {
            // nothing running, nothing to say. the old " (no task running) " was noise
            _shownSince = 0;
            return;
        }
        if (_shownSince == 0) {
            _shownSince = now;
        }
        draw(mc, graphics, scale, Math.min(1F, (now - _shownSince) / (float) FADE_MS));
    }

    private void layout(AltoClef mod, Font font, long now) {
        _lines.clear();
        _timer = null;
        TaskChain chain = mod.getTaskRunner().getCurrentTaskChain();
        List<Task> tasks = chain == null ? List.of() : chain.getTasks();
        if (tasks.isEmpty()) {
            if (_lastTime + TIMER_RESET_MS < now) {
                _timeRunning = now;
            }
            return;
        }
        _lastTime = now;
        if (Baritone.settings().altoShowTimer.value) {
            _timer = formatElapsed(now - _timeRunning);
            _timerWidth = font.width(_timer);
        }

        // the gui is already in scaled units, this just keeps a huge toString from crossing the screen
        int maxWidth = (int) (Math.max(160, Math.min(320, _layoutWidth * 0.4F)) / _layoutScale);

        // the developer view is the old hud exactly: class names, item lists, raw states, nothing hidden
        boolean detailed = Baritone.settings().altoHudDetailed.value;
        List<Entry> entries = new ArrayList<>();
        String header = detailed ? chain.getName() : hudName(chain);
        if (header != null) {
            entries.add(new Entry(header, "", false));
        }
        int n = tasks.size();
        for (int i = 0; i < n; i++) {
            Task task = tasks.get(i);
            boolean leaf = i == n - 1;
            if (!detailed && !leaf && task.hudPlumbing()) {
                // slot shuffling under a craft is not something the player wants a line for, unless it's what's
                // happening right now
                continue;
            }
            Entry e = new Entry(detailed ? name(task) : task.getHudName(), detailed ? task.getDebugState() : task.getHudState(), leaf);
            Entry prev = entries.isEmpty() ? null : entries.get(entries.size() - 1);
            if (!detailed && prev != null && prev.name.equals(e.name)) {
                // "Crafting 4 Oak Planks" twice (the 2x2 task and the generic one under it) is one line. the leaf
                // flag and a state move up so the line still reads as the active one
                entries.set(entries.size() - 1, new Entry(prev.name, prev.state.isEmpty() ? e.state : prev.state, prev.leaf || e.leaf));
                continue;
            }
            entries.add(e);
        }
        // too many lines: keep the header and the root, fold the middle, show the last few. those are the ones that
        // mean anything. the folded lines still only step 8px each so the stairs don't jump
        int rootLines = header == null ? 1 : 2;
        int skip = entries.size() > MAX_LINES + 1 ? entries.size() - MAX_LINES : 0;
        int depth = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (i == rootLines && skip > 0) {
                _lines.add(new Line(depth++, "... " + skip + " more", DIM, "", DIM));
            }
            if (i >= rootLines && i < rootLines + skip) {
                continue;
            }
            Entry e = entries.get(i);
            if (i == 0) {
                // the chain name, or with no chain name the root task: what you asked for is the title
                _lines.add(new Line(depth++, e.name, ACCENT, e.state, DIM));
                continue;
            }
            _lines.add(new Line(depth++, e.name, e.leaf ? WHITE : GRAY, e.state, e.leaf ? GRAY : DIM));
        }

        _width = 0;
        for (int i = 0; i < _lines.size(); i++) {
            _lines.set(i, fit(font, _lines.get(i), maxWidth));
            _width = Math.max(_width, width(font, _lines.get(i)));
        }
        if (_timer != null) {
            _timerX = MARGIN + PAD + width(font, _lines.get(0)) + GAP * 2;
            _width = Math.max(_width, width(font, _lines.get(0)) + GAP * 2 + _timerWidth);
        }
    }

    private static String name(Task task) {
        try {
            return task.getDebugName();
        } catch (Throwable t) {
            // a task's debug string is allowed to be sloppy, the hud is not allowed to die from it
            return task.getClass().getSimpleName();
        }
    }

    private static String hudName(TaskChain chain) {
        try {
            return chain.getHudName();
        } catch (Throwable t) {
            return chain.getName();
        }
    }

    private static int width(Font font, Line line) {
        int w = line.depth * INDENT + font.width(line.name);
        return line.state.isEmpty() ? w : w + GAP + font.width(line.state);
    }

    // name first, the state gets whatever is left. three dots instead of the ellipsis glyph, which comes from the
    // fallback font and looks like it
    private static Line fit(Font font, Line line, int maxWidth) {
        int room = maxWidth - line.depth * INDENT;
        int dots = font.width("...");
        if (font.width(line.name) > room) {
            return new Line(line.depth, font.plainSubstrByWidth(line.name, room - dots) + "...", line.nameColor, "", line.stateColor);
        }
        if (line.state.isEmpty() || width(font, line) <= maxWidth) {
            return line;
        }
        int left = room - font.width(line.name) - GAP - dots;
        if (left < 3 * dots) {
            return new Line(line.depth, line.name, line.nameColor, "", line.stateColor);
        }
        return new Line(line.depth, line.name, line.nameColor, font.plainSubstrByWidth(line.state, left) + "...", line.stateColor);
    }

    private void draw(Minecraft mc, GuiGraphicsExtractor graphics, float scale, float alpha) {
        Font font = mc.font;
        int height = _lines.size() * LINE - 1;
        int x0 = MARGIN + PAD;
        int y0 = MARGIN + PAD;
        graphics.pose().pushMatrix();
        graphics.pose().scale(scale, scale);
        // the chat's backdrop, so the accessibility text background option applies here too
        graphics.fill(MARGIN, MARGIN, x0 + _width + PAD, y0 + height + PAD, fade(mc.options.getBackgroundColor(0.5F), alpha));
        int y = y0;
        for (Line line : _lines) {
            int x = x0 + line.depth * INDENT;
            graphics.text(font, line.name, x, y, fade(line.nameColor, alpha), true);
            if (!line.state.isEmpty()) {
                // the state starts where the name ends, minus the shadow pixel the old drawString counted into its return
                graphics.text(font, line.state, x + font.width(line.name) + GAP, y, fade(line.stateColor, alpha), true);
            }
            y += LINE;
        }
        if (_timer != null) {
            // right after the chain name, not flush right: a long task line can make the box 300px wide and
            // a clock floating off on its own over there reads as somebody else's
            graphics.text(font, _timer, _timerX, y0, fade(DIM, alpha), true);
        }
        graphics.pose().popMatrix();
    }

    // Font treats alpha under 4 as "no alpha given" and draws opaque, so the fade never goes all the way down
    private static int fade(int color, float alpha) {
        int a = Math.max(4, Math.round(ARGB.alpha(color) * alpha));
        return (a << 24) | (color & 0xFFFFFF);
    }

    private static String formatElapsed(long ms) {
        long s = ms / 1000;
        long m = s / 60;
        long h = m / 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m % 60, s % 60) : String.format("%02d:%02d", m, s % 60);
    }
}
