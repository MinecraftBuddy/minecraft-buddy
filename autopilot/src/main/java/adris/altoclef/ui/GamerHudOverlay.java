package adris.altoclef.ui;

import adris.altoclef.AltoClef;
import adris.altoclef.AltoSettings;
import adris.altoclef.BuildInfo;
import adris.altoclef.tasks.speedrun.gamer.GamerHudState;
import adris.altoclef.tasks.speedrun.gamer.GamerHudState.DetourRow;
import adris.altoclef.tasks.speedrun.gamer.GamerHudState.FurnaceRow;
import adris.altoclef.tasks.speedrun.gamer.GamerHudState.KitRow;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.GamerTask;
import adris.altoclef.tasks.speedrun.gamer.HudRules;
import adris.altoclef.util.helpers.CombatCommit;
import baritone.Baritone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// the #gamer card on the right: the phase in big gold with its clock against the budget, eleven dots for where the run is,
// the thing being done right now, the kit as have/want rows with the game's own item icons, the furnace batches, the coal,
// gravel or diamond detour, hp and food. a red strip on top only while the combat brain holds FIGHT or RUN.
// laid out once per tick into a list of draw ops (same idea as CommandStatusOverlay's lines), drawn every frame. the
// numbers come from GamerTask.hudSnapshot(), frozen by the engine's tick: the only live reads here are the combat
// commitment (plain fields on the chain, it moves while the engine is paused for the fight) and the player's hp and
// position, which are fields too
public class GamerHudOverlay {
    // gui units, scaled by hudScale with everything else. 152 wide is what fits "Smelting 37 iron" and "3:12 left" with
    // an icon at the gui's own font without a wrap, and leaves the vanilla hotbar alone at 854 wide
    private static final int WIDTH = 152;
    private static final int MARGIN = 4;
    private static final int PAD_X = 4;
    private static final int PAD_TOP = 3;
    private static final int PAD_BOTTOM = 4;
    private static final int INNER = WIDTH - 2 * PAD_X;
    private static final int LINE = 10;
    // the font is 9 tall with its descender row, the title is the font at 2x
    private static final int FONT = 9;
    private static final int TITLE = 2 * FONT;
    private static final int ICON = 8;
    private static final int ICON_GAP = 3;
    private static final int BAR = 5;
    private static final int DOT_W = 4;
    private static final int DOT_H = 3;
    private static final int DOT_GAP = 2;
    private static final int STRIP = 12;
    private static final int VITAL = 9;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    // same dim as the tree, minecraft's dark_gray vanishes on snow
    private static final int DIM = 0xFF8A8A8A;
    // read once, the id never changes while the game runs
    private static final String BUILD_TAG = HudRules.buildTag(BuildInfo.id());
    private static final int GOLD = 0xFFFFAA00;
    private static final int GOLD_DIM = 0xFFB07400;
    private static final int GREEN = 0xFF55FF55;
    private static final int GREEN_DIM = 0xFF2E9E2E;
    private static final int YELLOW = 0xFFFFFF55;
    private static final int BLACK = 0xFF000000;
    // the bar's rim and a dot for a phase that has not happened
    private static final int RIM = 0xFF3A3A3A;
    private static final int HOT = 0xFFB3471A;
    private static final int HOT_TOP = 0xFFFF8A3D;
    private static final int STRIP_BG = 0xBFAA1414;
    private static final int RULE = 0x2EFFFFFF;

    private static final Identifier HEART = Identifier.withDefaultNamespace("hud/heart/full");
    private static final Identifier FOOD = Identifier.withDefaultNamespace("hud/food_full");

    private interface Op {
        void draw(GuiGraphicsExtractor g, Font font);
    }

    private record Rect(int x, int y, int w, int h, int color) implements Op {
        @Override
        public void draw(GuiGraphicsExtractor g, Font font) {
            g.fill(x, y, x + w, y + h, color);
        }
    }

    private record Text(String s, int x, int y, int color) implements Op {
        @Override
        public void draw(GuiGraphicsExtractor g, Font font) {
            g.text(font, s, x, y, color, true);
        }
    }

    // the font at 2x, one glyph at a time with a unit between them: the plain scale-up has the letters touching
    private record BigText(String[] glyphs, int[] xs, int y, int color) implements Op {
        @Override
        public void draw(GuiGraphicsExtractor g, Font font) {
            for (int i = 0; i < glyphs.length; i++) {
                g.pose().pushMatrix();
                g.pose().translate(xs[i], y);
                g.pose().scale(2F, 2F);
                g.text(font, glyphs[i], 0, 0, color, true);
                g.pose().popMatrix();
            }
        }
    }

    // an item at half size: 16 gui units is a hotbar slot, a list line is 8
    private record Icon(ItemStack stack, int x, int y) implements Op {
        @Override
        public void draw(GuiGraphicsExtractor g, Font font) {
            g.pose().pushMatrix();
            g.pose().translate(x, y);
            g.pose().scale(0.5F, 0.5F);
            g.item(stack, 0, 0);
            g.pose().popMatrix();
        }
    }

    private record Sprite(Identifier id, int x, int y, int w, int h) implements Op {
        @Override
        public void draw(GuiGraphicsExtractor g, Font font) {
            g.blitSprite(RenderPipelines.GUI_TEXTURED, id, x, y, w, h);
        }
    }

    private final List<Op> ops = new ArrayList<>();
    // one stack per item for the icons, a new one per tick would be a new model lookup per tick
    private final Map<Item, ItemStack> stacks = new HashMap<>();
    private long layoutTick = -1;
    private float layoutScale;
    private int height;
    // the card of a run that was just won, and how long it has been up. the task that made it is gone by the time anyone looks,
    // so this is the one place that keeps it
    private GamerHudState won;
    private HudRules.Linger linger = new HudRules.Linger();
    // the connection it was won on. the respawn after the credits builds a new level but keeps the connection, leaving the world
    // does not, and the card must not follow us into the next one
    private Object wonOn;

    // GamerTask hands over its last card when the run ends DONE
    public void win(GamerHudState card) {
        won = card;
        wonOn = Minecraft.getInstance().getConnection();
        linger = new HudRules.Linger();
        layoutTick = -1;
    }

    public void render(AltoClef mod, GuiGraphicsExtractor graphics) {
        Minecraft mc = Minecraft.getInstance();
        // f1 hides the hud, f3 is busy enough
        if (mc.level == null || mc.player == null || mc.gui.hud.isHidden() || mc.getDebugOverlay().showDebugScreen()) {
            return;
        }
        if (!Baritone.settings().altoGamerHud.value) {
            return;
        }
        // only a run that is going has a live card, the old task can sit in the chain after the runner went idle
        GamerHudState state = mod.getTaskRunner().isActive() && mod.getUserTaskChain().getCurrentTask() instanceof GamerTask run
                ? run.hudSnapshot() : null;
        if (state != null) {
            // a new run took over, the old win is history
            won = null;
        } else {
            state = lingeringWin(mc);
        }
        if (state == null) {
            ops.clear();
            layoutTick = -1;
            return;
        }
        float scale = AltoSettings.hudScale();
        long tick = mc.level.getGameTime();
        if (tick != layoutTick || scale != layoutScale) {
            layoutTick = tick;
            layoutScale = scale;
            layout(mod, mc.player, mc.font, state, tick);
        }
        // right edge, 4 in, centred on the height like the mockup. the hotbar is centred too and 182 wide, at 854 wide
        // gui there is room for both, at a tiny window they overlap and that is the window's problem
        int x0 = (int) (graphics.guiWidth() / scale) - MARGIN - WIDTH;
        int y0 = ((int) (graphics.guiHeight() / scale) - height) / 2;
        graphics.pose().pushMatrix();
        graphics.pose().scale(scale, scale);
        graphics.pose().translate(x0, y0);
        // the chat's backdrop, so the accessibility text background option applies here too
        graphics.fill(0, 0, WIDTH, height, mc.options.getBackgroundColor(0.5F));
        for (Op op : ops) {
            op.draw(graphics, mc.font);
        }
        graphics.pose().popMatrix();
    }

    // the win card while its thirty seconds last. they start with the first frame it can be seen: the credits cover the whole
    // screen for a minute or two after the dragon, and a card that timed out behind them would never have been shown
    private GamerHudState lingeringWin(Minecraft mc) {
        if (won == null) {
            return null;
        }
        if (mc.getConnection() != wonOn) {
            won = null;
            return null;
        }
        long now = System.currentTimeMillis();
        boolean show = linger.visible(now, mc.gui.screen() instanceof WinScreen);
        if (linger.over(now)) {
            won = null;
            return null;
        }
        return show ? won : null;
    }

    private void layout(AltoClef mod, LocalPlayer player, Font font, GamerHudState s, long now) {
        ops.clear();
        boolean over = s.phase() == GamerPhase.DONE;
        // no fight strip on a win card, the runner is idle and whatever the combat chain remembers is not about this
        boolean fighting = !over && mod.getMobDefenseChain().combatMode() != CombatCommit.Mode.NONE;
        int y = fighting ? combat(mod, player, font) : PAD_TOP;
        // the phase, big and gold, with its clock against the budget sitting on the same baseline
        y = title(font, s, y);
        y = dots(s, y);
        ops.add(new Text(fit(font, s.action(), INNER), PAD_X, y, WHITE));
        y += LINE + 3;
        // a fight ends the detour (DetourRules gap rule, nobody asks it) and the engine is paused for it, so the row would sit at 0:00
        DetourRow detour = fighting ? null : s.detour();
        // the row being worked, the furnaces right under it like the mockup, then the rest of the kit as far as the
        // five rows go, the detour last
        List<KitRow> rows = s.rows();
        int kitRows = Math.min(rows.size(), HudRules.kitRowBudget(s.furnaces().size(), detour != null));
        int next = 0;
        if (!rows.isEmpty() && rows.get(0).working()) {
            y = kitRow(font, rows.get(0), y);
            next = 1;
        }
        for (FurnaceRow f : s.furnaces()) {
            y = furnaceRow(font, f, y, now);
        }
        for (; next < kitRows; next++) {
            y = kitRow(font, rows.get(next), y);
        }
        if (detour != null) {
            String name = HudRules.detourName(detour.name(), detour.blocks(), detour.dug(), detour.digCap());
            y = row(font, y, detour.icon(), name, GRAY,
                    HudRules.clock(HudRules.detourSecondsLeft(detour.startTick(), detour.budgetSeconds(), now)), YELLOW);
        }
        y += 4;
        ops.add(new Rect(PAD_X, y, INNER, 1, RULE));
        y += 1 + 3;
        y = footer(font, player, s, y);
        height = y + PAD_BOTTOM;
    }

    // the red strip, only while the combat brain holds a fight or a run. "RUN 11 blocks to go" counts down the 24
    private int combat(AltoClef mod, LocalPlayer player, Font font) {
        CombatCommit.Mode mode = mod.getMobDefenseChain().combatMode();
        ops.add(new Rect(0, 0, WIDTH, STRIP, STRIP_BG));
        String left;
        String right;
        if (mode == CombatCommit.Mode.RUN) {
            left = "RUN";
            int toGo = HudRules.blocksToGo(player.getX(), player.getZ(), mod.getMobDefenseChain().combatRunOriginX(),
                    mod.getMobDefenseChain().combatRunOriginZ(), CombatCommit.RUN_DISTANCE);
            right = toGo + " blocks to go";
        } else {
            left = "FIGHT";
            right = mod.getMobDefenseChain().combatFightName();
        }
        ops.add(new Text(left, PAD_X, 2, WHITE));
        ops.add(new Text(right, PAD_X + INNER - font.width(right), 2, WHITE));
        return STRIP + 3;
    }

    private int title(Font font, GamerHudState s, int y) {
        String title = HudRules.title(s.phase());
        String[] glyphs = new String[title.length()];
        int[] xs = new int[title.length()];
        int glyphs2x = 0;
        for (int i = 0; i < title.length(); i++) {
            glyphs[i] = String.valueOf(title.charAt(i));
            glyphs2x += font.width(glyphs[i]) * 2;
        }
        // "END PREP" and a ten minute clock do not both fit at 152 wide: pack the letters, then drop the budget off the
        // clock (the bar under it still shows it). the title's size never changes, see HudRules.titleFit
        String shortClock = HudRules.clock(s.secondsInPhase());
        String clock = HudRules.titleClock(s.phase(), s.secondsInPhase(), s.budgetMinutes());
        HudRules.TitleFit fit = HudRules.titleFit(glyphs2x, glyphs.length, font.width(clock), font.width(shortClock), INNER);
        int letterGap = fit == HudRules.TitleFit.SPACED ? 1 : 0;
        int x = PAD_X;
        for (int i = 0; i < glyphs.length; i++) {
            xs[i] = x;
            x += font.width(glyphs[i]) * 2 + letterGap;
        }
        // a won run is green all the way down, the dots under it are too
        boolean over = s.phase() == GamerPhase.DONE;
        ops.add(new BigText(glyphs, xs, y, over ? GREEN : GOLD));
        if (fit == HudRules.TitleFit.SHORT_CLOCK) {
            clock = shortClock;
        }
        ops.add(new Text(clock, PAD_X + INNER - font.width(clock), y + TITLE - FONT, DIM));
        y += TITLE + 2;
        double fraction = HudRules.titleFraction(s.phase(), s.secondsInPhase(), s.budgetMinutes());
        bar(PAD_X, y, INNER, fraction, over ? GREEN_DIM : GOLD_DIM, over ? GREEN : GOLD);
        return y + BAR + 3;
    }

    // eleven dots, 4 by 3 with a black rim: done green, now gold, the rest dark
    private int dots(GamerHudState s, int y) {
        HudRules.Dot[] dots = HudRules.dots(s.phase());
        for (int i = 0; i < dots.length; i++) {
            int x = PAD_X + i * (DOT_W + DOT_GAP);
            int color = switch (dots[i]) {
                case DONE -> GREEN;
                case NOW -> GOLD;
                case LATER -> RIM;
            };
            ops.add(new Rect(x, y, DOT_W, DOT_H, BLACK));
            ops.add(new Rect(x + 1, y + 1, DOT_W - 2, DOT_H - 2, color));
        }
        return y + DOT_H + 4;
    }

    private int kitRow(Font font, KitRow r, int y) {
        String right = r.counted() ? r.have() + "/" + r.want() : r.have() > 0 ? Integer.toString(r.have()) : "";
        // a done row is dim with its numbers in green, the one being worked gets a bar under it
        y = row(font, y, r.icon(), r.name(), r.done() ? DIM : GRAY, right, r.done() ? GREEN : WHITE);
        if (r.working() && r.counted()) {
            y += 1;
            bar(PAD_X + ICON + ICON_GAP, y, INNER - ICON - ICON_GAP, HudRules.fraction(r.have(), r.want()), GOLD_DIM, GOLD);
            y += BAR;
        }
        return y;
    }

    private int furnaceRow(Font font, FurnaceRow f, int y, long now) {
        int left = HudRules.secondsLeft(f.doneTick(), now);
        y = row(font, y, f.icon(), f.words(), GRAY, left == 0 ? "done" : HudRules.clock(left) + " left", WHITE);
        y += 1;
        bar(PAD_X + ICON + ICON_GAP, y, INNER - ICON - ICON_GAP, HudRules.furnaceFraction(f.startTick(), f.doneTick(), now), HOT, HOT_TOP);
        return y + BAR;
    }

    // icon, name, a number on the right. the name gets whatever width the number leaves
    private int row(Font font, int y, Item icon, String name, int nameColor, String right, int rightColor) {
        y += 2;
        if (icon != null) {
            ops.add(new Icon(stacks.computeIfAbsent(icon, ItemStack::new), PAD_X, y + 1));
        }
        int rightW = right.isEmpty() ? 0 : font.width(right);
        if (rightW > 0) {
            ops.add(new Text(right, PAD_X + INNER - rightW, y + 1, rightColor));
        }
        int textX = PAD_X + ICON + ICON_GAP;
        int room = INNER - ICON - ICON_GAP - (rightW > 0 ? rightW + ICON_GAP : 0);
        ops.add(new Text(fit(font, name, room), textX, y + 1, nameColor));
        return y + LINE;
    }

    private int footer(Font font, LocalPlayer player, GamerHudState s, int y) {
        int x = PAD_X;
        ops.add(new Sprite(HEART, x, y, VITAL, VITAL));
        x += VITAL + 2;
        String hp = Integer.toString((int) Math.ceil(player.getHealth()));
        ops.add(new Text(hp, x, y + 1, WHITE));
        x += font.width(hp) + 6;
        ops.add(new Sprite(FOOD, x, y, VITAL, VITAL));
        x += VITAL + 2;
        ops.add(new Text(s.foodUnits() + "/" + s.foodTarget(), x, y + 1, WHITE));
        String attempt = "attempt " + s.attempt();
        ops.add(new Text(attempt, PAD_X + INNER - font.width(attempt), y + 1, DIM));
        y += LINE;
        // one more row under the vitals, which is what the card height grows by (it is just where y ends up)
        ops.add(new Text(fit(font, BUILD_TAG, INNER), PAD_X, y + 1, DIM));
        return y + LINE;
    }

    // a bar the way vanilla draws one: black trough, one unit of dark rim, flat fill with a lighter top row
    private void bar(int x, int y, int w, double fraction, int fill, int top) {
        ops.add(new Rect(x, y, w, BAR, RIM));
        ops.add(new Rect(x + 1, y + 1, w - 2, BAR - 2, BLACK));
        int filled = HudRules.fillWidth(w - 2, fraction);
        if (filled > 0) {
            ops.add(new Rect(x + 1, y + 1, filled, BAR - 2, fill));
            ops.add(new Rect(x + 1, y + 1, filled, 1, top));
        }
    }

    // three dots instead of the ellipsis glyph, which comes from the fallback font and looks like it
    private static String fit(Font font, String s, int room) {
        if (s == null) {
            return "";
        }
        if (font.width(s) <= room) {
            return s;
        }
        return font.plainSubstrByWidth(s, Math.max(0, room - font.width("..."))) + "...";
    }
}
