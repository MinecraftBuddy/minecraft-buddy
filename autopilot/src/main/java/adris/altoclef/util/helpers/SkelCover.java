package adris.altoclef.util.helpers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// (the buddy's fork, Tuning skel_cover and skel_close_rule, fixes of 13 Nov 2026, on) the overworld's skeletons, the rules with
// no minecraft in them: SkelCoverTask asks the world and places or digs, CombatBrain holds the cover (Hold), CombatCommit asks
// the close rule before a walk up to one. test heel's first 18 runs: skeletons killed 5, each time with 44 to 400 cobblestone
// in the bag. the buddy stood in a skeleton's line after a run ended clear, while healing or with nothing running, and walked
// up to skeletons hurt, without armour or with others in sight. the nether keeps its own rules: nothing here is asked there
public final class SkelCover {
    private SkelCover() {
    }

    // under this hp a skeleton with a line on the buddy is met from cover; the hold lets go at it too
    public static final float HP_LINE = 18;
    // a skeleton this close with a line counts (its bow reaches 15 and it walks in)
    public static final double RANGE = 16;
    // a walk up to a skeleton only from this hp
    public static final float CLOSE_HP = 16;
    // out of cover once no skeleton of the kinds is within LEAVE_RANGE for LEAVE_TICKS (5 s)
    public static final double LEAVE_RANGE = 20;
    public static final long LEAVE_TICKS = 100;
    // no hp back for this long (a meal under way counts as progress): regen is not happening, out of cover (60 s), and no
    // cover again for RETRY_STALL_TICKS (30 s); a cover that could not be had is not tried again for RETRY_FAILED_TICKS (10 s)
    public static final long STALL_TICKS = 1200;
    public static final long RETRY_STALL_TICKS = 600;
    public static final long RETRY_FAILED_TICKS = 200;

    public static boolean coverOn() {
        return Tuning.get("skel_cover", 0) > 0;
    }

    // (fixes of 14 Nov 2026, test heel's 23 skeleton deaths since the cover: SkelCoverTask, the Hold below, CombatCommit, CombatBrain)
    // dig_first: two down with the top shut wherever the ground digs, a pillbox from the bag where it does not, a hole with no top
    // failed, an arrow in cover the next block or the dig at once
    public static boolean digFirstOn() {
        return Tuning.get("skel_cover_dig_first", 0) > 0;
    }

    // hold: out of cover at full hp, or at the hp line once no skeleton has had a line for LINE_GONE_TICKS; the stall waits for
    // no line and no skeleton within RANGE
    public static boolean holdOn() {
        return Tuning.get("skel_cover_hold", 0) > 0;
    }

    // (cover_night_hold) at night the far exit waits for full hp (Hold.nightFacts)
    public static boolean nightHoldOn() {
        return Tuning.get("cover_night_hold", 0) > 0;
    }

    // wet_fail: off the ground OFF_GROUND_TICKS in the cover task (in water) is a cover that failed, and none is offered in water
    public static boolean wetFailOn() {
        return Tuning.get("skel_cover_wet_fail", 0) > 0;
    }

    // over_runs: the dug-in cover takes the place of the low hp, crowd and unarmed runs with melee mobs about too, and a run
    // under the hp line that takes an arrow ends by digging in (CombatCommit.coverOverRun)
    public static boolean overRunsOn() {
        return Tuning.get("skel_cover_over_runs", 0) > 0;
    }

    // (pillbox_top_support, a fix of 14 Nov 2026, on: SkelCoverTask) the pillbox's top on flat bare ground: a block on a wall
    // cell's top first, clicked at the top of a jump, then the top against its side; the body to the cell's middle before a side
    public static boolean topSupportOn() {
        return Tuning.get("pillbox_top_support", 0) > 0;
    }

    // (pillbox_hold_melee, a fix of 14 Nov 2026, on: CombatCommit, CombatBrain) inside a shut cover a melee mob at the walls is no
    // reason to leave it; the aura swings at one that hits in reach with a line
    public static boolean holdMeleeOn() {
        return Tuning.get("pillbox_hold_melee", 0) > 0;
    }

    // (pillbox_top_support) the body this far off the cell's middle at most on each axis before a side goes in: half the body
    // (0.3) and this stay inside the cell, so no side cell has the body in it
    public static final double CENTRE_SLACK = 0.15;
    // (pillbox_top_support) the eye this far over the wall's top before the click on it (the face is in sight from above only)
    public static final double OVER_TOP = 0.1;
    // (pillbox_top_support) jumps for the top's support at one pillbox, then no top as before
    public static final int TOP_JUMPS = 3;

    // (pillbox_top_support) which side of the pillbox (in boxSides' order) takes the top's support, -1 for none: the first whose
    // cell at the eye's height is up and whose cell over it is free for a block. topFaced: the top has a face to go against
    // already (no support wanted then)
    public static int topSupportSide(boolean topFaced, boolean[] wallUp, boolean[] overFree) {
        if (topFaced) return -1;
        for (int i = 0; i < wallUp.length && i < overFree.length; i++) {
            if (wallUp[i] && overFree[i]) return i;
        }
        return -1;
    }

    // (pillbox_top_support) the body off the cell's middle more than the slack on an axis: x and z the feet, cellX and cellZ the
    // cell's corner
    public static boolean offCentre(double x, double z, int cellX, int cellZ, double slack) {
        return Math.abs(x - (cellX + 0.5)) > slack || Math.abs(z - (cellZ + 0.5)) > slack;
    }

    // (pillbox_top_support) the eye over the wall's top (at wallTopY) enough to click its top face
    public static boolean eyeOverTop(double eyeY, int wallTopY) {
        return eyeY >= wallTopY + OVER_TOP;
    }

    // (skel_cover_hold) the hp the cover lets go at whatever a skeleton does (a player's full bar)
    public static final float FULL_HP = 20;
    // (skel_cover_hold) a line gone this long (5 s) is gone: the hp line and the stall may let go then
    public static final long LINE_GONE_TICKS = 100;
    // (skel_cover_hold) the stall stays this much longer at most while a skeleton is about (4 minutes)
    public static final long STALL_CAP_TICKS = 4800;
    // (skel_cover_wet_fail) off the ground this long in the cover task (1 s): in water, the cover cannot be had
    public static final long OFF_GROUND_TICKS = 20;
    // (skel_cover_over_runs) a creeper within this is the run's, never the hole's
    public static final double CREEPER_RANGE = 6;

    public static boolean closeOn() {
        return Tuning.get("skel_close_rule", 0) > 0;
    }

    public static float hpLine() {
        return (float) Tuning.get("skel_cover_hp", HP_LINE);
    }

    public static double range() {
        return Tuning.get("skel_cover_range", RANGE);
    }

    public static float closeHp() {
        return (float) Tuning.get("skel_close_hp", CLOSE_HP);
    }

    // the kinds that shoot arrows from where they stand: the skeleton, the stray, the bogged and the parched. not the wither
    // skeleton (a melee mob), not the blaze
    public static boolean kind(String entityType) {
        return switch (entityType) {
            case "skeleton", "stray", "bogged", "parched" -> true;
            default -> false;
        };
    }

    // the cover is wanted: the overworld, alive and under the hp line, and at least one skeleton with a line within range
    public static boolean wanted(boolean overworld, float hp, int lines, float hpLine) {
        return overworld && hp > 0 && hp < hpLine && lines > 0;
    }

    // why the buddy does not walk up to the skeleton, null when it may: hp at closeHp or more, a sword, at least one armour piece
    // worn and no other skeleton with a line within range (by sight, hit yet or not)
    public static String closeRefusal(float hp, boolean sword, int armour, int otherLines, float closeHp, double range) {
        if (hp < closeHp) return String.format(Locale.ROOT, "hp %.1f, under %.0f", hp, closeHp);
        if (!sword) return "no sword";
        if (armour <= 0) return "no armour on";
        if (otherLines > 0) return otherLines + (otherLines == 1 ? " more skeleton" : " more skeletons") + " with a line within " + Math.round(range);
        return null;
    }

    // (skel_cover_dig_first) how the cover goes up where the buddy stands
    public enum Way {
        // the buddy's two cells walled on the four sides and roofed already
        SHUT,
        // two down and the top shut with the dug block
        DIG,
        // a pillbox from the bag: a block at the eye's height on the four sides and the top where it has a face to go against
        BOX,
        // no block and no ground to dig
        NONE
    }

    // shut: walled and roofed already; walled: the four cells at the eye's height are solid (a hole or a nook: the top alone is
    // missing, and the bag has it); diggable: two down come out with the tools at hand and the floor holds; blocks: a full block
    // in the bag
    public static Way way(boolean shut, boolean walled, boolean diggable, boolean blocks) {
        if (shut) return Way.SHUT;
        if (walled && blocks) return Way.BOX;
        if (diggable) return Way.DIG;
        if (blocks) return Way.BOX;
        return Way.NONE;
    }

    // (skel_cover_dig_first) the pillbox's four cells at the eye's height, as {x, z} offsets from the feet, in the order they go
    // up: the side toward the skeleton dx/dz off (skeleton minus buddy) on the axis it is most along, the other axis's side
    // toward it, that axis's side away, the side away
    public static List<int[]> boxSides(double dx, double dz) {
        boolean xMain = Math.abs(dx) >= Math.abs(dz);
        int sx = dx >= 0 ? 1 : -1;
        int sz = dz >= 0 ? 1 : -1;
        int[] main = xMain ? new int[]{sx, 0} : new int[]{0, sz};
        int[] other = xMain ? new int[]{0, sz} : new int[]{sx, 0};
        return List.of(main, other, new int[]{-other[0], -other[1]}, new int[]{-main[0], -main[1]});
    }

    // (skel_cover_dig_first) a hole is cover from a skeleton only with its top shut: one standing over the rim shoots straight
    // down into it (test heel: dug three deep, no top, shot in the hole)
    public static boolean holeHolds(boolean digFirst, boolean topShut) {
        return !digFirst || topShut;
    }

    // (skel_cover_wet_fail) off the ground since offSince (-1: on it) for OFF_GROUND_TICKS: the cover cannot be had here
    public static boolean offGroundFailed(long offSince, long now) {
        return offSince >= 0 && now >= offSince && now - offSince >= OFF_GROUND_TICKS;
    }

    // the block cells the line from (x0, y0, z0) to (x1, y1, z1) passes through, in order, as {x, y, z}, the cell it starts in
    // left out, up to maxDist along the line: the cells a block can take the line away in (the skeleton's sight is the ray from
    // its eye to ours, LivingEntity.hasLineOfSight)
    public static List<int[]> rayCells(double x0, double y0, double z0, double x1, double y1, double z1, double maxDist) {
        List<int[]> out = new ArrayList<>();
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1e-9) return out;
        dx /= length;
        dy /= length;
        dz /= length;
        double limit = Math.min(length, maxDist);
        int x = (int) Math.floor(x0), y = (int) Math.floor(y0), z = (int) Math.floor(z0);
        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int sy = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tx = boundary(x0, x, sx, dx), ty = boundary(y0, y, sy, dy), tz = boundary(z0, z, sz, dz);
        double ux = sx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double uy = sy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double uz = sz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        for (int i = 0; i < 64; i++) {
            double t;
            if (tx <= ty && tx <= tz) {
                t = tx;
                x += sx;
                tx += ux;
            } else if (ty <= tz) {
                t = ty;
                y += sy;
                ty += uy;
            } else {
                t = tz;
                z += sz;
                tz += uz;
            }
            // (a face at the limit itself is the far side of the last cell, not a cell of its own)
            if (t >= limit) break;
            out.add(new int[]{x, y, z});
        }
        return out;
    }

    // how far along the line the first cell face on this axis is
    private static double boundary(double start, int cell, int step, double dir) {
        if (step == 0) return Double.POSITIVE_INFINITY;
        double next = step > 0 ? cell + 1 : cell;
        return (next - start) / dir;
    }

    // the cover as it holds, once a tick (CombatBrain): taken when it is wanted and on offer (a full block in the bag, or ground
    // to dig), left when the hp is back to the line, when no skeleton of the kinds is within LEAVE_RANGE for LEAVE_TICKS, when
    // regen does not happen (STALL_TICKS), or when the cover task could not get it up. what it said goes out through takeNote.
    // (skel_cover_hold, holdRules) the hp exit is full hp, or the hp line once no skeleton has had a line for LINE_GONE_TICKS: test
    // heel's buddy let go at 18 in a skeleton's line five times; and the stall leaves only once no skeleton has had a line for
    // LINE_GONE_TICKS and none is within RANGE, STALL_CAP_TICKS later at most: four held unhit with no food and walked out of
    // the cover into the line after the stall's minute
    // (witch_cover, a fix of 14 Nov 2026, on: WitchRules) a witch's hit, or the poison with a witch within 16, takes the cover too
    // (witchFacts); a witch's line counts as a skeleton's for the hold, the hp exits wait for the poison to be over and no witch's
    // line for LINE_GONE_TICKS, and the far exit for no witch within LEAVE_RANGE and no poison either
    public static final class Hold {
        private boolean on;
        private long gainAt;
        private float lastHp;
        private long farSince = -1;
        private long lineAt;
        private boolean stallSaid;
        private boolean holdRules;
        private long blockedUntil = Long.MIN_VALUE / 2;
        private long blockedFrom = Long.MIN_VALUE / 2;
        private String note;
        // (witch_cover) the witches' side, handed in before each step: the key, a witch wants the cover this tick, the poison on,
        // the witches with a line within range and the nearest of them, the nearest witch seen or not; when a witch last had a
        // line, and whether this stretch in cover was taken for a witch
        private boolean witchOn;
        private boolean witchWanted;
        private boolean poisoned;
        private int witchLines;
        private double witchLineDist = Double.POSITIVE_INFINITY;
        private double witchNearest = Double.POSITIVE_INFINITY;
        private long witchLineAt = Long.MIN_VALUE / 2;
        private boolean witch;
        // (mounted_no_run, MountRules) the mounts' side, handed in before each step: the key, a mounted melee mob wants the cover
        // this tick, the nearest mounted melee mob (infinity for none); when one was last within RANGE, and whether this stretch
        // in cover was taken for one
        private boolean mountOn;
        private boolean mountWanted;
        private double mountNearest = Double.POSITIVE_INFINITY;
        private long mountAt = Long.MIN_VALUE / 2;
        private boolean mount;
        // (Tuning cover_night_hold, a fix of 14 Nov 2026, on) at night the far exit waits for full hp, handed in before each step;
        // and whether that was said this stretch
        private boolean nightHold;
        private boolean nightSaid;

        public boolean on() {
            return on;
        }

        // (cover_night_hold) the key and the night (the game's isBrightOutside false), before each step
        public void nightFacts(boolean on, boolean night) {
            nightHold = on && night;
        }

        // (skel_cover_hold) the key, handed in before each step; off in a bare hold
        public void holdRules(boolean on) {
            holdRules = on;
        }

        public String takeNote() {
            String said = note;
            note = null;
            return said;
        }

        // (witch_cover) the witches' facts, handed in before each step; all off in a bare hold
        public void witchFacts(boolean on, boolean wanted, boolean poisoned, int lines, double lineDist, double nearest) {
            witchOn = on;
            witchWanted = on && wanted;
            this.poisoned = on && poisoned;
            witchLines = on ? lines : 0;
            witchLineDist = lineDist;
            witchNearest = on ? nearest : Double.POSITIVE_INFINITY;
        }

        // (witch_cover) this stretch in cover was taken for a witch (its lines say "witch cover")
        public boolean witch() {
            return on && witch;
        }

        // (mounted_no_run) the mounts' facts, handed in before each step; all off in a bare hold
        public void mountFacts(boolean on, boolean wanted, double nearest) {
            mountOn = on;
            mountWanted = on && wanted;
            mountNearest = on ? nearest : Double.POSITIVE_INFINITY;
        }

        // (mounted_no_run) this stretch in cover was taken for a mounted mob, with no skeleton's line and no witch (its lines say
        // "mount cover")
        public boolean mount() {
            return on && mount;
        }

        private String name() {
            return witch ? "witch cover" : mount ? "mount cover" : "skeleton cover";
        }

        // a stall or a cover that could not be had waits its retry (a clock that went back, a new world, lets it go)
        private boolean blocked(long now) {
            return now >= blockedFrom && now < blockedUntil;
        }

        // the cover takes the buddy this tick: in it already, or wanted with cover on offer and no retry to wait for
        public boolean takes(long now, boolean wanted, boolean offered) {
            return on || (wanted && offered && !blocked(now));
        }

        // with nothing committed (no run, no fight). lines and lineDist: the skeletons with a line within range and the nearest
        // of them; nearest: the nearest skeleton of the kinds, seen or not (infinity for none); eating: a meal is under way;
        // failed: why the cover task gave up, null while it has not
        public void step(long now, boolean wanted, boolean offered, float hp, float hpLine, int lines, double lineDist,
                         double nearest, boolean eating, String failed) {
            step(now, wanted, offered, hp, hpLine, lines, lineDist, nearest, eating, failed, true, false);
        }

        // hasFood: food in the bag (the stall's note); meleeNear: (skel_cover_over_runs) a melee mob close to the dug-in cover,
        // which keeps it as a skeleton within LEAVE_RANGE does (false with the key off)
        public void step(long now, boolean wanted, boolean offered, float hp, float hpLine, int lines, double lineDist,
                         double nearest, boolean eating, String failed, boolean hasFood, boolean meleeNear) {
            if (!on) {
                if (!wanted || !offered || blocked(now)) return;
                on = true;
                gainAt = now;
                lastHp = hp;
                farSince = -1;
                nightSaid = false;
                lineAt = now;
                stallSaid = false;
                witch = witchWanted;
                witchLineAt = witchLines > 0 ? now : Long.MIN_VALUE / 2;
                mount = mountWanted && !witch && lines == 0;
                mountAt = mountNearest <= RANGE ? now : Long.MIN_VALUE / 2;
                if (mount) {
                    note = String.format(Locale.ROOT, "mount cover: a mounted mob within %.1f at %.1f hp, taking cover", mountNearest, hp);
                } else if (witch) {
                    String within = Double.isInfinite(witchLineDist) ? "" : String.format(Locale.ROOT, " within %.1f", witchLineDist);
                    note = String.format(Locale.ROOT, "witch cover: %d %s with a line%s at %.1f hp%s%s, taking cover", witchLines,
                            witchLines == 1 ? "witch" : "witches", within, hp, poisoned ? ", poisoned" : "",
                            lines > 0 ? ", " + lines + (lines == 1 ? " skeleton" : " skeletons") + " with a line" : "");
                } else {
                    note = String.format(Locale.ROOT, "skeleton cover: %d %s with a line within %.1f at %.1f hp, taking cover", lines,
                            lines == 1 ? "skeleton" : "skeletons", lineDist, hp);
                }
                return;
            }
            if (hp > lastHp || eating || now < gainAt) gainAt = now;
            lastHp = hp;
            if (lines > 0 || now < lineAt) lineAt = now;
            boolean lineGone = now - lineAt >= LINE_GONE_TICKS;
            // (witch_cover) a witch's line counts as a skeleton's for the hold, and the hp exits wait for the poison to be over
            // and no witch's line for LINE_GONE_TICKS: the poison takes about 1 hp a 1.25 s whatever the hp says now
            if (witchOn && (witchLines > 0 || now < witchLineAt)) witchLineAt = now;
            boolean witchLineGone = !witchOn || now - witchLineAt >= LINE_GONE_TICKS;
            boolean witchClear = witchLineGone && !poisoned;
            // (mounted_no_run) and no mounted melee mob within RANGE for LINE_GONE_TICKS: it rides up in a second
            if (mountOn && (mountNearest <= RANGE || now < mountAt)) mountAt = now;
            witchClear &= !mountOn || now - mountAt >= LINE_GONE_TICKS;
            if (holdRules) {
                if (hp >= FULL_HP && witchClear) {
                    leave(now, String.format(Locale.ROOT, "hp %.1f", hp) + witchSaid(), 0);
                    return;
                }
                if (hp >= hpLine && lineGone && witchClear) {
                    leave(now, String.format(Locale.ROOT, "hp %.1f, no line for %d s", hp, LINE_GONE_TICKS / 20) + witchSaid(), 0);
                    return;
                }
            } else if (hp >= hpLine && witchClear) {
                leave(now, String.format(Locale.ROOT, "hp %.1f", hp) + witchSaid(), 0);
                return;
            }
            if (failed != null) {
                leave(now, "no cover here (" + failed + ")", RETRY_FAILED_TICKS);
                return;
            }
            // (witch_cover: no witch within LEAVE_RANGE either, and the poison over) (mounted_no_run: nor a mounted mob)
            double near = Math.min(nearest, Math.min(witchNearest, mountNearest));
            // (cover_night_hold) at night the far exit stays shut until the hp is full: the cover gave way after a give-up or a
            // far exit and the buddy ran at 6 to 7 hp in the dark and died (test heel, early deaths since heel-17)
            if (near > LEAVE_RANGE && !meleeNear && !poisoned && nightHold && hp < FULL_HP) {
                farSince = -1;
                if (!nightSaid) {
                    nightSaid = true;
                    note = String.format(Locale.ROOT, "%s: no skeleton within %d, but night at %.1f hp: the far exit stays shut until the hp is full (cover_night_hold)",
                            name(), Math.round(LEAVE_RANGE), hp);
                }
            } else if (near > LEAVE_RANGE && !meleeNear && !poisoned) {
                if (farSince < 0 || now < farSince) farSince = now;
                if (now - farSince >= LEAVE_TICKS) {
                    leave(now, "no skeleton " + (witchOn ? "or witch " : "") + (mountOn ? "or mounted mob " : "") + "within " + Math.round(LEAVE_RANGE) + " for " + LEAVE_TICKS / 20 + " s", 0);
                    return;
                }
            } else {
                farSince = -1;
            }
            if (now - gainAt < STALL_TICKS) return;
            String stall = "no hp back in " + STALL_TICKS / 20 + " s" + (hasFood ? "" : ", no food");
            if (!holdRules) {
                leave(now, "no hp back in " + STALL_TICKS / 20 + " s", RETRY_STALL_TICKS);
                return;
            }
            // (skel_cover_hold) out only into no line: none for LINE_GONE_TICKS and no skeleton within RANGE to step into
            // (witch_cover: nor a witch's)
            if (lineGone && witchLineGone && near > RANGE) {
                leave(now, stall + ", no skeleton " + (witchOn ? "or witch " : "") + (mountOn ? "or mounted mob " : "") + "within " + Math.round(RANGE), RETRY_STALL_TICKS);
                return;
            }
            if (now - gainAt >= STALL_TICKS + STALL_CAP_TICKS) {
                leave(now, stall + ", a skeleton about for " + (STALL_TICKS + STALL_CAP_TICKS) / 1200 + " min", RETRY_STALL_TICKS);
                return;
            }
            if (!stallSaid) {
                stallSaid = true;
                note = name() + ": " + stall + ", staying while a " + (witch ? "witch or skeleton" : mount ? "mounted mob or skeleton" : "skeleton") + " is within "
                        + Math.round(RANGE) + (lineGone && witchLineGone ? "" : " with a line");
            }
        }

        // a run or a fight took the wheel: out of cover, the next stretch with nothing committed asks again
        public void interrupt(long now, String why) {
            if (on) leave(now, why, 0);
        }

        public void reset() {
            on = false;
            witch = false;
            mount = false;
            mountAt = Long.MIN_VALUE / 2;
            witchLineAt = Long.MIN_VALUE / 2;
            farSince = -1;
            blockedUntil = Long.MIN_VALUE / 2;
            blockedFrom = Long.MIN_VALUE / 2;
            note = null;
        }

        // (witch_cover) what a witch's cover adds to an hp exit's words (mounted_no_run: a mount's cover the same)
        private String witchSaid() {
            if (mount) return ", no mounted mob within " + Math.round(RANGE) + " for " + LINE_GONE_TICKS / 20 + " s";
            return witch ? ", no poison, no witch's line for " + LINE_GONE_TICKS / 20 + " s" : "";
        }

        private void leave(long now, String why, long retry) {
            String name = name();
            on = false;
            witch = false;
            mount = false;
            farSince = -1;
            if (retry > 0) {
                blockedFrom = now;
                blockedUntil = now + retry;
            }
            note = name + ": out of cover: " + why;
        }
    }
}
