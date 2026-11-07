package adris.altoclef.util.helpers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

// the buddy's fork: one score for the danger around the buddy, the hp it is likely to lose in the next HORIZON seconds, and
// whether it can win the race against what is after it. pure numbers: the game side (DangerShadow) hands in the mobs, the
// hazards and the buddy as plain values, so the whole meter is tested without minecraft.
//
// - T: the sum over mobs of want x its damage within the horizon (hp per 3 s). a melee mob counts from the moment it can reach
//   us (distance minus reach, over its speed) and only with a way to us; a shooter counts with a line of sight within range.
// - push: the hp a push into a hazard is likely to cost within the horizon. a hazard (lava, fire, a deep edge) within 3 blocks
//   makes any mob within 3 blocks a pusher: a passive or calm one by walking into us, a hostile by its knock when the hazard
//   lies behind us. without a hazard nothing pushes
// - D: time to die, hp over the loss rate (T + push); K: time to kill what is after us (its hp over our damage a second, plus
//   the walk to each); R = D / K
// - the level: calm (T and push both under about 1), flee (R under 1, or hp minus the loss at the margin), cover (R under 2),
//   fight (R 2 and up). a level is left only 20% past its bound and is held at least HOLD_TICKS (Track)
//
// fire resistance zeroes fire damage: a blaze's fireballs and their burning, and the fire hazards (lava, fire, soul fire,
// magma, campfires). the mob numbers in MobThreat are through iron (armour 15) on Normal; scale() turns them into ours
public final class DangerMeter {
    public enum Level {
        CALM, FIGHT, COVER, FLEE;

        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        // flee is worse than cover is worse than fight is worse than calm
        boolean worseThan(Level other) {
            return ordinal() > other.ordinal();
        }
    }

    // the horizon in seconds: T is hp per this many seconds
    public static final double HORIZON = 3;
    // calm below these (hp per 3 s)
    public static final double CALM_T = 1;
    public static final double CALM_PUSH = 1;
    // flee when hp minus the loss within the horizon comes down to this
    public static final double MARGIN = 4;
    public static final double FIGHT_R = 2;
    public static final double FLEE_R = 1;
    // a level is left only this far past its bound
    public static final double BAND = 0.2;
    // and held at least this long (1 s)
    public static final long HOLD_TICKS = 20;
    // pushers and hazards count within this
    public static final double PUSH_RANGE = 3;
    // the chance within the horizon that a body beside us walks us into a hazard, and that a hostile's hit knocks us into one
    // lying behind us (guesses; phase 1 logs them to size them)
    public static final double BODY_CHANCE = 0.1;
    public static final double HIT_CHANCE = 0.5;
    // our own reach and walking speed, for the walk to each foe in K
    public static final double OUR_REACH = 3;
    public static final double OUR_SPEED = 4.3;

    private DangerMeter() {
    }

    // one mob as the game side sees it. dx, dy, dz: its feet relative to ours. row: MobThreat.forMeter (null: a body that hurts
    // nobody). want: wantsToHurt. way: it can walk to us (or flies). sees: a line from its eyes to ours. hp: its health now
    public record Mob(String kind, MobThreat row, double dx, double dy, double dz, double want, boolean way, boolean sees, double hp) {
        double distance() {
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    // a hazard block (its centre relative to our feet). kind: lava, fire, soul_fire, magma_block, campfire, soul_campfire,
    // wither_rose, cactus, sweet_berry_bush, powder_snow, or drop (an edge). fall: for a drop the blocks fallen, or LETHAL_DROP
    // (no floor in reach) or LAVA_DROP (lava below)
    public record Hazard(String kind, double dx, double dy, double dz, double fall) {
        double distance() {
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    public static final double LETHAL_DROP = -1;
    public static final double LAVA_DROP = -2;

    // the buddy: hp (absorption included), our damage a second, armour points and toughness, the difficulty (0 peaceful to 3
    // hard), resistance (0 none, else its level), fire resistance
    public record Us(double hp, double dps, double armour, double toughness, int difficulty, int resistance, boolean fireRes) {
    }

    // one contributor: what it adds to T (or to push, for a pusher), in hp per 3 s
    public record Part(String kind, double distance, double hp3) {
    }

    public record Reading(double t, double push, double d, double k, double r, double hp, List<Part> top) {
        public double loss() {
            return t + push;
        }
    }

    // the one "wants to hurt" rule. 1: after us (the game's own signs: aggressive, creepy, a charged blaze with sight, it hit us
    // lately, a provoked neutral). 0: a calm neutral (a piglin while we wear gold, a zombified piglin nobody poked, an
    // enderman nobody looked at) or a passive kind. 0.5: a hostile kind that sees us within its notice range (likely soon)
    public static double wantsToHurt(boolean afterUs, boolean hostileKind, boolean calmNeutral, boolean sees, double distance, double notice) {
        if (afterUs) return 1;
        if (calmNeutral || !hostileKind) return 0;
        return sees && distance <= notice ? 0.5 : 0;
    }

    // a row's damage (through iron on Normal) as it lands on us: back to raw, then our difficulty, armour and resistance.
    // fire damage from burning is not scaled here (armour does not stop it)
    public static double scale(MobThreat row, Us us) {
        if (row.hit() <= 0) return 1;
        double raw = rawFromIron(row.hit());
        double hit = afterArmour(byDifficulty(raw, us.difficulty()), us.armour(), us.toughness());
        hit *= Math.max(0, 1 - 0.2 * us.resistance());
        return hit / row.hit();
    }

    // CombatRules.getDamageAfterAbsorb: armour takes armour - damage / (2 + toughness / 4) points, at least a fifth of the
    // armour and at most 20, each point 4%
    static double afterArmour(double damage, double armour, double toughness) {
        double points = Math.min(20, Math.max(armour * 0.2, armour - damage / (2 + toughness / 4)));
        return damage * (1 - points / 25);
    }

    // the raw damage that comes through armour 15 (no toughness) as this much: solves afterArmour(d, 15, 0) = after
    static double rawFromIron(double after) {
        // while 15 - d / 2 >= 3 (d <= 24): after = d (10 + d / 2) / 25
        double d = 25 * (-0.4 + Math.sqrt(0.16 + 0.08 * after));
        if (d <= 24) return d;
        return after / 0.88;
    }

    // a mob's hit on a player by difficulty (Player.hurt): peaceful none, easy half plus one (never more), hard half again
    static double byDifficulty(double raw, int difficulty) {
        return switch (difficulty) {
            case 0 -> 0;
            case 1 -> Math.min(raw / 2 + 1, raw);
            case 3 -> raw * 1.5;
            default -> raw;
        };
    }

    // what falling into a hazard costs, in hp: lava or a lethal drop everything, fire a few hp. fire resistance takes the fire
    // ones away (lava, fire, soul fire, magma, campfires), and a drop into lava then costs nothing either
    public static double hazardCost(String kind, double fall, double hp, boolean fireRes) {
        return switch (kind) {
            case "lava" -> fireRes ? 0 : hp;
            case "fire" -> fireRes ? 0 : 4;
            case "soul_fire" -> fireRes ? 0 : 5;
            case "campfire" -> fireRes ? 0 : 3;
            case "soul_campfire" -> fireRes ? 0 : 4;
            case "magma_block" -> fireRes ? 0 : 2;
            case "wither_rose" -> 3;
            case "cactus", "sweet_berry_bush" -> 2;
            case "powder_snow" -> 3;
            case "drop" -> {
                if (fall == LAVA_DROP) yield fireRes ? 0 : hp;
                if (fall < 0) yield hp;
                yield Math.min(hp, Math.max(0, fall - 3));
            }
            default -> 0;
        };
    }

    public static Reading read(Us us, List<Mob> mobs, List<Hazard> hazards) {
        List<Part> parts = new ArrayList<>();
        double t = 0;
        double push = 0;
        double k = 0;
        for (Mob mob : mobs) {
            double distance = mob.distance();
            double hp3 = damage3(mob, us, distance);
            if (hp3 > 0) {
                t += hp3;
                parts.add(new Part(mob.kind(), distance, hp3));
                // the race: its hp over our damage a second, plus the walk to it
                double hp = mob.hp() > 0 ? mob.hp() : mob.row() != null ? mob.row().hp() : 20;
                double kill = us.dps() > 0 ? hp / us.dps() + Math.max(0, distance - OUR_REACH) / OUR_SPEED : Double.POSITIVE_INFINITY;
                k += mob.want() * kill;
            }
            double risk = pushRisk(mob, us, distance, hazards);
            if (risk > 0) {
                push += risk;
                parts.add(new Part(mob.kind(), distance, risk));
            }
        }
        push = Math.min(push, us.hp());
        double loss = t + push;
        double d = loss > 0 ? us.hp() / (loss / HORIZON) : Double.POSITIVE_INFINITY;
        double r = loss > 0 && k > 0 ? d / k : Double.POSITIVE_INFINITY;
        parts.sort(Comparator.comparingDouble(Part::hp3).reversed());
        return new Reading(t, push, d, k, r, us.hp(), parts.size() > 3 ? List.copyOf(parts.subList(0, 3)) : List.copyOf(parts));
    }

    // want x what one mob can do to us within the horizon, hp per 3 s
    static double damage3(Mob mob, Us us, double distance) {
        MobThreat row = mob.row();
        if (row == null || mob.want() <= 0) return 0;
        double scale = scale(row, us);
        double melee = 0;
        if (row.melee() > 0 && (mob.way() || row.flies())) {
            double toReach = Math.max(0, distance - row.reach()) / Math.max(0.05, row.speed());
            melee = row.melee() * scale * Math.max(0, HORIZON - toReach);
            // a creeper blasts once
            if (row.kind().equals("creeper")) melee = Math.min(melee, row.hit() * scale);
        }
        double shot = 0;
        if (row.shot() > 0 && mob.sees() && distance <= row.range()) {
            double share = row.aimShare(distance);
            double shots = us.fireRes() && row.fireShot() ? 0 : row.shot() * scale * share;
            double burn = us.fireRes() ? 0 : row.burn() * share;
            shot = (shots + burn) * HORIZON;
        }
        return mob.want() * (melee + shot);
    }

    // what this mob within PUSH_RANGE risks pushing us into: the worst hazard within PUSH_RANGE, by its cost, how close it lies
    // (1 within a block, down to 0.2 at 3) and the chance of the push
    static double pushRisk(Mob mob, Us us, double distance, List<Hazard> hazards) {
        if (distance > PUSH_RANGE || hazards.isEmpty()) return 0;
        MobThreat row = mob.row();
        boolean hits = mob.want() > 0 && row != null && row.melee() > 0;
        double worst = 0;
        for (Hazard h : hazards) {
            double hd = h.distance();
            if (hd > PUSH_RANGE + 0.5) continue;
            double cost = hazardCost(h.kind(), h.fall(), us.hp(), us.fireRes());
            if (cost <= 0) continue;
            double near = Math.min(1, Math.max(0, (PUSH_RANGE + 0.5 - hd) / 2.5));
            double chance = BODY_CHANCE;
            // a hit knocks us away from the mob: the hazard counts for it when it lies on our far side, within the knock
            if (hits) {
                double behind = -mob.dx() * h.dx() - mob.dz() * h.dz();
                double flat = Math.sqrt(h.dx() * h.dx() + h.dz() * h.dz());
                if ((behind > 0 || row.pushRound()) && flat <= row.push() + 1) chance = Math.max(chance, HIT_CHANCE * mob.want());
            }
            worst = Math.max(worst, chance * near * cost);
        }
        return worst;
    }

    // the level for these numbers, starting from the current one: a level is entered at its bound and left 20% past it
    public static Level classify(Level current, double t, double push, double r, double hp) {
        double calmBand = current == Level.CALM ? 1 + BAND : 1 - BAND;
        if (t < CALM_T * calmBand && push < CALM_PUSH * calmBand) return Level.CALM;
        double fleeR = FLEE_R * (current == Level.FLEE ? 1 + BAND : current == Level.COVER ? 1 - BAND : 1);
        double margin = MARGIN * (current == Level.FLEE ? 1 + BAND : 1);
        if (r < fleeR || hp - (t + push) <= margin) return Level.FLEE;
        double coverR = FIGHT_R * (current == Level.FIGHT ? 1 - BAND : current == Level.COVER || current == Level.FLEE ? 1 + BAND : 1);
        if (r < coverR) return Level.COVER;
        return Level.FIGHT;
    }

    // the level over time: classify, and a change only once the current level has been held HOLD_TICKS
    public static final class Track {
        private Level _level = Level.CALM;
        private long _since = Long.MIN_VALUE / 4;

        public Level level() {
            return _level;
        }

        // true when the level changed
        public boolean step(long tick, Reading reading) {
            return step(tick, reading.t(), reading.push(), reading.r(), reading.hp());
        }

        public boolean step(long tick, double t, double push, double r, double hp) {
            Level next = classify(_level, t, push, r, hp);
            if (next == _level || tick - _since < HOLD_TICKS) return false;
            _level = next;
            _since = tick;
            return true;
        }

        public void reset(long tick) {
            _level = Level.CALM;
            _since = tick - HOLD_TICKS;
        }
    }

    // the time spent in cover or flee without progress. progress: a kill, a rod picked up, hp a heart above its low since the
    // last progress, or T at half its peak since then. a stretch is the time in cover or flee in one go; when it ends, the
    // longest span in it without progress
    public static final class Patience {
        public record Ended(Level worst, long ticks) {
        }

        private boolean _in;
        private Level _worst;
        private long _lastProgress;
        private long _longest;
        private double _lowHp;
        private double _peakT;

        // one sample. progress: a kill or a rod since the last sample. a non-null result: a stretch just ended
        public Ended step(long tick, Level level, double t, double hp, boolean progress) {
            boolean covered = level == Level.COVER || level == Level.FLEE;
            if (!_in) {
                if (!covered) return null;
                _in = true;
                _worst = level;
                _longest = 0;
                mark(tick, t, hp);
                return null;
            }
            if (!covered) return end(tick);
            if (level.worseThan(_worst)) _worst = level;
            _lowHp = Math.min(_lowHp, hp);
            _peakT = Math.max(_peakT, t);
            if (progress || hp >= _lowHp + 2 || (_peakT >= CALM_T && t <= _peakT / 2)) {
                _longest = Math.max(_longest, tick - _lastProgress);
                mark(tick, t, hp);
            }
            return null;
        }

        // a stretch cut short (a death): its result, or null outside one
        public Ended end(long tick) {
            if (!_in) return null;
            _in = false;
            return new Ended(_worst, Math.max(_longest, tick - _lastProgress));
        }

        private void mark(long tick, double t, double hp) {
            _lastProgress = tick;
            _lowHp = hp;
            _peakT = t;
        }
    }

    // ---- the log line's pieces (Locale.ROOT: a decimal comma would break the rig's reading)

    public static String num(double v) {
        if (Double.isInfinite(v) || Double.isNaN(v)) return "inf";
        return String.format(Locale.ROOT, "%.1f", v);
    }

    // "zombie 2.1 60%, blaze 10.4 40%": kind, distance, share of T plus push; "none" without any
    public static String top(Reading reading) {
        double total = reading.t() + reading.push();
        if (reading.top().isEmpty() || total <= 0) return "none";
        StringBuilder sb = new StringBuilder();
        for (Part part : reading.top()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(part.kind()).append(' ').append(num(part.distance())).append(' ')
                    .append(Math.round(100 * part.hp3() / total)).append('%');
        }
        return sb.toString();
    }

    // danger: <level>, T <t> hp/3s, push <p>, R <r>, hp <hp>, top <kind> <dist> <share>%, ...
    public static String levelLine(Level level, Reading reading) {
        return "danger: " + level.word() + ", T " + num(reading.t()) + " hp/3s, push " + num(reading.push()) + ", R " + num(reading.r())
                + ", hp " + num(reading.hp()) + ", top " + top(reading);
    }

    // danger: hit <damage> from <kind>, level <level>, T <t>, R <r>
    public static String hitLine(double damage, String kind, Level level, Reading reading) {
        return "danger: hit " + num(damage) + " from " + kind + ", level " + level.word() + ", T " + num(reading.t()) + ", R " + num(reading.r());
    }

    // danger: at death, level <level>, T <t>, push <p>, R <r>, top ...
    public static String deathLine(Level level, Reading reading) {
        return "danger: at death, level " + level.word() + ", T " + num(reading.t()) + ", push " + num(reading.push()) + ", R " + num(reading.r())
                + ", top " + top(reading);
    }

    // danger: <level> for <s> s without progress
    public static String patienceLine(Patience.Ended ended) {
        return "danger: " + ended.worst().word() + " for " + num(ended.ticks() / 20.0) + " s without progress";
    }

    // an empty reading, for a line before the first sample
    public static Reading none(double hp) {
        return new Reading(0, 0, Double.POSITIVE_INFINITY, 0, Double.POSITIVE_INFINITY, hp, List.of());
    }
}
