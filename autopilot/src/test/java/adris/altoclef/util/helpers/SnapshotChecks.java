package adris.altoclef.util.helpers;

import adris.altoclef.util.snapshot.SnapshotFrame;
import adris.altoclef.util.snapshot.SnapshotPick;
import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.world.phys.Vec3;

// the invariants the snapshots are held to, each a small named check. two kinds:
// - pick-* and hit-*: what the autopilot decided in the run (SnapshotPick), judged against the world of that tick
// - replay-*: the planners of this build run again on the frame's world (BlazeCover.pick, GhastCover.pick, BlazePillbox,
//   BrewBox), and what they choose now judged the same way: a change in one layer that breaks another shows here
// a check adds a Violation per thing wrong, with a one-line reason; a check that cannot judge (the cells it needs were not
// in the box) counts a skip. new checks go in ALL
final class SnapshotChecks {
    private SnapshotChecks() {
    }

    record Violation(String check, String file, long tick, double before, String subject, String reason) {
        // what a baseline remembers it by
        String key() {
            return check + " " + file + " " + tick + " " + subject;
        }

        String line() {
            return String.format(Locale.ROOT, "%s  t-%.1fs  %s  %s: %s", file, before, check, subject, reason);
        }
    }

    // what a check gets: the file, one frame of it, the frame's world
    static final class Ctx {
        final SnapshotFile file;
        final SnapshotFrame frame;
        final SnapshotWorld world;
        final int index;
        final List<Violation> out;
        final int[] skips;

        Ctx(SnapshotFile file, int index, List<Violation> out, int[] skips) {
            this.file = file;
            this.index = index;
            this.frame = file.frames.get(index);
            this.world = new SnapshotWorld(frame, file.palette);
            this.out = out;
            this.skips = skips;
        }

        void flag(String check, String subject, String reason) {
            out.add(new Violation(check, file.name, frame.tick, file.before(frame), subject, reason));
        }

        void skip() {
            skips[0]++;
        }

        boolean last() {
            return index == file.frames.size() - 1;
        }
    }

    interface Check {
        String name();

        void run(Ctx c);
    }

    // ---- the kinds of thing in a frame

    // kinds always after the buddy; the rest only when the autopilot judged them angry
    static final Set<String> HOSTILE = Set.of("blaze", "ghast", "wither_skeleton", "skeleton", "stray", "bogged", "magma_cube", "slime",
            "piglin_brute", "hoglin", "zoglin", "wither", "creeper", "zombie", "husk", "drowned", "witch", "vindicator", "pillager", "ravager",
            "evoker", "vex", "phantom", "guardian", "elder_guardian", "warden", "breeze", "spider", "cave_spider", "silverfish", "endermite");
    // the kinds that hit from afar: SafeStand.ranged's (an AbstractSkeleton, the wither skeleton too)
    static final Set<String> RANGED = Set.of("blaze", "ghast", "skeleton", "stray", "bogged", "wither_skeleton", "pillager");
    // what a swing at is no melee hit (punched back or broken: another reach)
    static final Set<String> NOT_MELEE = Set.of("fireball", "small_fireball", "dragon_fireball", "wither_skull", "shulker_bullet",
            "wind_charge", "breeze_wind_charge", "end_crystal", "item_frame", "glow_item_frame", "painting", "armor_stand");
    // the picks that are a cell to stand in, and those meant to be out of the blazes' sight
    static final Set<String> STAND = Set.of("blaze.cover", "blaze.hide", "blaze.corner.hide", "blaze.corner", "blaze.camp", "pillbox",
            "pillbox.hide", "brewbox", "ghast.cover", "ghast.corner");
    static final Set<String> HIDE_FROM_BLAZES = Set.of("blaze.cover", "blaze.hide", "blaze.corner.hide", "pillbox.hide");
    // a creeper's blast: the harness's own reach for it, as far as a ghast's ball (SafeStand has none)
    static final double CREEPER = SafeStand.GHAST;
    static final double HEAD = 1.62;
    static final double GHAST_HEAD = 1.6;

    static boolean alive(SnapshotFrame.Ent e) {
        return e.hp != 0;
    }

    static boolean foe(SnapshotFrame.Ent e) {
        return alive(e) && e.hp > 0 && (e.angry || HOSTILE.contains(e.type));
    }

    static Vec3 eye(SnapshotFrame.Ent e) {
        return new Vec3(e.x, e.eyeY(), e.z);
    }

    static int[] feet(SnapshotFrame f) {
        return new int[]{(int) Math.floor(f.x), (int) Math.floor(f.y + 1e-3), (int) Math.floor(f.z)};
    }

    static String at(int[] c) {
        return c[0] + "," + c[1] + "," + c[2];
    }

    static String dist(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }

    // a body standing in the cell seen from the point: the head or the middle, through the cells given
    static boolean sees(Cells cells, Vec3 from, int[] c) {
        return SnapshotWorld.clear(cells, from, new Vec3(c[0] + 0.5, c[1] + HEAD, c[2] + 0.5))
                || SnapshotWorld.clear(cells, from, new Vec3(c[0] + 0.5, c[1] + 1.0, c[2] + 0.5));
    }

    // the pushes a hit at a body in the cell can give, from every foe that can hit it there: one that shoots, with a line to it;
    // one in melee, within SafeStand.FAR of it. names says who, for the reason
    static List<SafeStand.Push> pushes(SnapshotFrame f, Cells sight, int[] c, List<String> names) {
        List<SafeStand.Push> out = new ArrayList<>();
        double cx = c[0] + 0.5, cz = c[2] + 0.5;
        for (SnapshotFrame.Ent e : f.entities) {
            if (!foe(e)) continue;
            double d = Math.sqrt((e.x - cx) * (e.x - cx) + (e.y - c[1]) * (e.y - c[1]) + (e.z - cz) * (e.z - cz));
            boolean ranged = RANGED.contains(e.type);
            boolean can = ranged ? sees(sight, eye(e), c) : d <= SafeStand.FAR;
            if (!can) continue;
            SafeStand.Push p;
            if (e.type.equals("hoglin") || e.type.equals("zoglin")) p = SafeStand.Push.round(SafeStand.HOGLIN);
            else if (e.type.equals("ghast")) p = SafeStand.Push.away(e.x, e.z, SafeStand.GHAST);
            else if (e.type.equals("creeper")) p = SafeStand.Push.away(e.x, e.z, CREEPER);
            else p = SafeStand.Push.away(e.x, e.z, SafeStand.HIT);
            out.add(p);
            names.add(e.type + " at " + dist(d) + (ranged ? " (in sight)" : ""));
        }
        return out;
    }

    // the room for a body: a floor of a block under it, nothing in the way of the body, no fire or cobweb in it ("" for fine)
    static String unstandable(SnapshotWorld w, Cells walk, Cells body, int[] c) {
        Cell floor = body.at(c[0], c[1] - 1, c[2]);
        if (floor != Cell.SOLID) return "no floor under it (" + floor.name().toLowerCase(Locale.ROOT) + ")";
        for (int dy = 0; dy <= 1; dy++) {
            Cell b = body.at(c[0], c[1] + dy, c[2]);
            if (b != Cell.OPEN) return (dy == 0 ? "the feet's" : "the head's") + " cell is " + b.name().toLowerCase(Locale.ROOT);
            Cell k = walk.at(c[0], c[1] + dy, c[2]);
            if (k != Cell.OPEN) return (dy == 0 ? "the feet's" : "the head's") + " cell is no cell to walk in (fire, a cobweb or the like)";
        }
        return "";
    }

    // a knock from a foe that can hit the cell ends in lava or a fall that hurts (SafeStand); null for none, or when the cells
    // the knock could reach were not all seen (skipped)
    static String knock(Ctx ctx, Cells body, Cells sight, int[] c) {
        List<String> names = new ArrayList<>();
        List<SafeStand.Push> ps = pushes(ctx.frame, sight, c, names);
        if (ps.isEmpty()) return null;
        double reach = 0;
        for (SafeStand.Push p : ps) reach = Math.max(reach, p.reach());
        if (!ctx.world.known(c[0], c[1], c[2], (int) Math.ceil(reach) + 1, 0, 1)) {
            ctx.skip();
            return null;
        }
        if (!SafeStand.unsafe(body, c[0], c[1], c[2], ps)) return null;
        int[] side = SafeStand.blockSide(body, c[0], c[1], c[2], ps);
        return "a knock ends in lava or a fall of " + SafeStand.HURTS + "+ (from " + String.join(", ", names) + ")"
                + (side == null ? "" : "; a block at " + at(side) + " would stop it");
    }

    // the cells as they will be with the decision's blocks placed (a pillbox's, a brew box's)
    static Cells after(Cells cells, List<int[]> blocks) {
        return blocks.isEmpty() ? cells : BlazePillbox.after(cells, blocks);
    }

    // ---- the checks on what was decided

    static final Check PICK_STANDABLE = new Check() {
        public String name() {
            return "pick-standable";
        }

        public void run(Ctx c) {
            for (SnapshotPick p : c.frame.picks) {
                if (!STAND.contains(p.kind) || p.cell == null) continue;
                if (!c.world.known(p.cell[0], p.cell[1], p.cell[2], 0, 1, 1)) {
                    c.skip();
                    continue;
                }
                String why = unstandable(c.world, after(c.world.walk, p.blocks), after(c.world.body, p.blocks), p.cell);
                if (!why.isEmpty()) c.flag(name(), p.kind + " " + at(p.cell), why);
            }
        }
    };

    static final Check PICK_KNOCK_SAFE = new Check() {
        public String name() {
            return "pick-knock-safe";
        }

        public void run(Ctx c) {
            for (SnapshotPick p : c.frame.picks) {
                if (!STAND.contains(p.kind) || p.cell == null) continue;
                String why = knock(c, after(c.world.body, p.blocks), after(c.world.sight, p.blocks), p.cell);
                if (why != null) c.flag(name(), p.kind + " " + at(p.cell), why);
            }
        }
    };

    static final Check PICK_HIDDEN_FROM_BLAZES = new Check() {
        public String name() {
            return "pick-hidden-from-blazes";
        }

        public void run(Ctx c) {
            for (SnapshotPick p : c.frame.picks) {
                if (!HIDE_FROM_BLAZES.contains(p.kind) || p.cell == null) continue;
                String seen = seenBy(c, p.cell, "blaze", BlazeCover.SIGHT, BlazeCover.EYE);
                if (seen != null) c.flag(name(), p.kind + " " + at(p.cell), "the " + seen + " sees a body there");
            }
        }
    };

    static final Check PICK_HIDDEN_FROM_GHASTS = new Check() {
        public String name() {
            return "pick-hidden-from-ghasts";
        }

        public void run(Ctx c) {
            for (SnapshotPick p : c.frame.picks) {
                if (!p.kind.startsWith("ghast.") || p.cell == null) continue;
                String seen = seenBy(c, p.cell, "ghast", GhastCover.SIGHT, GHAST_HEAD);
                if (seen != null) c.flag(name(), p.kind + " " + at(p.cell), "the " + seen + " sees a body there");
            }
        }
    };

    // the first of a kind within range whose eyes see the head of a body in the cell (through the sight cells), as "blaze at 7.1
    // (14,70,-3)", or null for none. one whose eyes were outside the box says so: the cells between were partly unseen. headY is
    // where the head is over the feet, as the check that chose the cell has it (BlazeCover.EYE, GhastCover.seesCell's 1.6)
    static String seenBy(Ctx c, int[] cell, String type, double range, double headY) {
        Vec3 head = new Vec3(cell[0] + 0.5, cell[1] + headY, cell[2] + 0.5);
        for (SnapshotFrame.Ent e : c.frame.entities) {
            if (!e.type.equals(type) || !alive(e)) continue;
            Vec3 eye = eye(e);
            double d = eye.distanceTo(head);
            if (d > range) continue;
            if (SnapshotWorld.clear(c.world.sight, eye, head)) {
                return type + " at " + dist(d) + " (" + at(new int[]{(int) Math.floor(e.x), (int) Math.floor(e.y), (int) Math.floor(e.z)}) + ")"
                        + (c.world.inBox(eye.x, eye.y, eye.z) ? "" : ", its eyes outside the box");
            }
        }
        return null;
    }

    static final Check HIT_IN_REACH = new Check() {
        public String name() {
            return "hit-in-reach";
        }

        public void run(Ctx c) {
            for (SnapshotPick p : c.frame.picks) {
                if (!p.kind.equals("hit") || p.eye == null || p.box == null || NOT_MELEE.contains(p.targetType)) continue;
                double gap = ReachRules.gap(p.eye[0], p.eye[1], p.eye[2], p.box[0], p.box[1], p.box[2], p.box[3], p.box[4], p.box[5]);
                if (gap > p.reach + 0.01) c.flag(name(), "hit " + p.targetType + " #" + p.target, "swung from " + dist(gap) + " with a reach of " + dist(p.reach));
            }
        }
    };

    static final Check HIT_NOT_THROUGH_BLOCKS = new Check() {
        public String name() {
            return "hit-not-through-blocks";
        }

        public void run(Ctx c) {
            for (SnapshotPick p : c.frame.picks) {
                if (!p.kind.equals("hit") || p.eye == null || p.box == null || NOT_MELEE.contains(p.targetType)) continue;
                Vec3 eye = new Vec3(p.eye[0], p.eye[1], p.eye[2]);
                if (!c.world.inBox(eye.x, eye.y, eye.z)) {
                    c.skip();
                    continue;
                }
                double[] b = p.box;
                double mx = (b[0] + b[3]) / 2, mz = (b[2] + b[5]) / 2, h = b[4] - b[1];
                // (the nearest point of its box, its middle, near its top and a third of a block over its feet)
                Vec3[] points = {
                        new Vec3(clamp(eye.x, b[0], b[3]), clamp(eye.y, b[1], b[4]), clamp(eye.z, b[2], b[5])),
                        new Vec3(mx, b[1] + h / 2, mz), new Vec3(mx, b[1] + h * 0.85, mz), new Vec3(mx, b[1] + 0.3, mz)};
                boolean seen = false;
                for (Vec3 pt : points) seen |= SnapshotWorld.clear(c.world.fullOnly, eye, pt);
                // (and a grid on each face of the box toward the eye: a swing's ray that passed a wall's corner by 0.02 block
                // missed all four points above and was flagged though the hit was clear, snap-20261106-174048-567-low)
                if (!seen) seen = faceGridSeen(c.world, eye, b);
                if (!seen) c.flag(name(), "hit " + p.targetType + " #" + p.target, "whole blocks between the eye and every point of its body");
            }
        }
    };

    // a grid of FACE_GRID x FACE_GRID points on each face of the box that faces the eye, a hair inside it: one in sight is enough
    static final int FACE_GRID = 7;

    static boolean faceGridSeen(SnapshotWorld w, Vec3 eye, double[] b) {
        double in = 0.01;
        for (int axis = 0; axis < 3; axis++) {
            double lo = b[axis], hi = b[axis + 3], e = axis == 0 ? eye.x : axis == 1 ? eye.y : eye.z;
            if (e >= lo && e <= hi) continue;
            double face = e < lo ? lo + in : hi - in;
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            for (int i = 0; i < FACE_GRID; i++) {
                for (int j = 0; j < FACE_GRID; j++) {
                    double[] pt = new double[3];
                    pt[axis] = face;
                    pt[u] = b[u] + in + (b[u + 3] - b[u] - 2 * in) * i / (FACE_GRID - 1);
                    pt[v] = b[v] + in + (b[v + 3] - b[v] - 2 * in) * j / (FACE_GRID - 1);
                    if (SnapshotWorld.clear(w.fullOnly, eye, new Vec3(pt[0], pt[1], pt[2]))) return true;
                }
            }
        }
        return false;
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---- the checks that run the planners again

    // a replay on every REPLAY_EVERY-th frame and on every frame with a decision in it (the planners cost, the frames differ little)
    static final int REPLAY_EVERY = 2;

    static boolean replayHere(Ctx c) {
        return c.index % REPLAY_EVERY == 0 || !c.frame.picks.isEmpty() || c.last();
    }

    static final Check REPLAY_BLAZE_COVER = new Check() {
        public String name() {
            return "replay-blaze-cover";
        }

        public void run(Ctx c) {
            SnapshotFrame f = c.frame;
            if (!replayHere(c) || !f.onGround) return;
            List<Vec3> eyes = new ArrayList<>();
            boolean onUs = false;
            for (SnapshotFrame.Ent e : f.entities) {
                if (!e.type.equals("blaze") || !alive(e)) continue;
                double d = Math.sqrt((e.x - f.x) * (e.x - f.x) + (e.y - f.y) * (e.y - f.y) + (e.z - f.z) * (e.z - f.z));
                if (d <= BlazeCover.SIGHT + BlazeCover.RADIUS) eyes.add(eye(e));
                if (e.los && d <= 16) onUs = true;
            }
            if (!onUs) return;
            int[] u = feet(f);
            // (the box's centre sits on a grid, so the buddy may be two off it: RADIUS + 2 is what the box always holds)
            if (!c.world.known(u[0], u[1], u[2], BlazeCover.RADIUS + 2, BlazeCover.DOWN + 1, BlazeCover.UP + 2)) {
                c.skip();
                return;
            }
            List<double[]> withers = new ArrayList<>();
            for (SnapshotFrame.Ent e : f.entities) {
                if (e.type.equals("wither_skeleton") && alive(e) && Math.hypot(e.x - f.x, e.z - f.z) <= BlazeCover.RADIUS + BlazeCover.WITHER_CLEAR) {
                    withers.add(new double[]{e.x, e.z});
                }
            }
            Cells sight = c.world.sight;
            int[] cover = BlazeCover.pick(c.world.walk, u[0], u[1], u[2], withers, new HashSet<>(),
                    (x, y, z) -> BlazePillbox.hidden(sight, eyes, x, y, z), (x, y, z) -> enclosure(c.world.body, x, y, z));
            if (cover == null) return;
            String subject = "cover " + at(cover) + " from " + at(u);
            String why = unstandable(c.world, c.world.walk, c.world.body, cover);
            if (!why.isEmpty()) c.flag(name(), subject, why);
            String knock = knock(c, c.world.body, sight, cover);
            if (knock != null) c.flag(name(), subject, knock);
        }
    };

    // BlazeCover.enclosure over cells: of 24 rays out from the head, how many meet a block within ENCLOSE_REACH
    static int enclosure(Cells body, int x, int y, int z) {
        Vec3 head = new Vec3(x + 0.5, y + BlazeCover.EYE, z + 0.5);
        int n = 0;
        for (double el : BlazeCover.ELEVATIONS) {
            for (int i = 0; i < 8; i++) {
                double yaw = i * Math.PI / 4;
                Vec3 dir = new Vec3(Math.cos(yaw) * Math.cos(el), Math.sin(el), Math.sin(yaw) * Math.cos(el));
                if (!SnapshotWorld.clear(body, head, head.add(dir.scale(BlazeCover.ENCLOSE_REACH)))) n++;
            }
        }
        return n;
    }

    static final Check REPLAY_GHAST_COVER = new Check() {
        public String name() {
            return "replay-ghast-cover";
        }

        public void run(Ctx c) {
            SnapshotFrame f = c.frame;
            if (!replayHere(c) || !f.onGround) return;
            SnapshotFrame.Ent ghast = null;
            double best = Double.MAX_VALUE;
            for (SnapshotFrame.Ent e : f.entities) {
                if (!e.type.equals("ghast") || !alive(e) || !e.los) continue;
                double d = Math.sqrt((e.x - f.x) * (e.x - f.x) + (e.y - f.y) * (e.y - f.y) + (e.z - f.z) * (e.z - f.z));
                if (d <= GhastCover.SIGHT && d < best) {
                    best = d;
                    ghast = e;
                }
            }
            if (ghast == null) return;
            int[] u = feet(f);
            if (!c.world.known(u[0], u[1], u[2], GhastCover.RADIUS + GhastCover.MARGIN + 1, 3, 3)) {
                c.skip();
                return;
            }
            Vec3 eye = eye(ghast);
            Cells sight = c.world.sight;
            int[] cover = GhastCover.pick(c.world.walk, c.world.body, u[0], u[1], u[2],
                    (x, y, z) -> !SnapshotWorld.clear(sight, eye, new Vec3(x + 0.5, y + GHAST_HEAD, z + 0.5)));
            if (cover == null) return;
            String subject = "cover " + at(cover) + " from the ghast #" + ghast.id;
            String why = unstandable(c.world, c.world.walk, c.world.body, cover);
            if (!why.isEmpty()) c.flag(name(), subject, why);
            String knock = knock(c, c.world.body, sight, cover);
            if (knock != null) c.flag(name(), subject, knock);
            String seen = seenBy(c, cover, "ghast", GhastCover.SIGHT, GHAST_HEAD);
            if (seen != null) c.flag(name(), subject, "the " + seen + " sees a body there");
        }
    };

    // the spawner the buddy fights at: the nearest in the box within 16 (flat), as the rods task finds it
    static int[] spawner(Ctx c) {
        SnapshotFrame f = c.frame;
        int[] best = null;
        double bestD = 16;
        for (int[] s : c.world.blocksNamed(c.file.palette, "minecraft:spawner")) {
            double d = Math.hypot(s[0] + 0.5 - f.x, s[2] + 0.5 - f.z);
            if (d <= bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    // the cell by the spawner the plans walk out from, as CollectBlazeRodsTask.pillboxPlan finds it
    static int[] startBy(Cells walk, int sx, int sy, int sz) {
        int[] start = null;
        double best = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (!BlazeCover.walkable(walk, sx + dx, sy + dy, sz + dz)) continue;
                    double d = dx * dx + dz * dz + dy * dy;
                    if (d < best) {
                        best = d;
                        start = new int[]{sx + dx, sy + dy, sz + dz};
                    }
                }
            }
        }
        return start;
    }

    static List<Vec3> openEyes(Cells body, List<Vec3> eyes) {
        List<Vec3> out = new ArrayList<>();
        for (Vec3 e : eyes) {
            if (body.at((int) Math.floor(e.x), (int) Math.floor(e.y), (int) Math.floor(e.z)) == Cell.OPEN) out.add(e);
        }
        return out;
    }

    // the last frame of the file with a spawner near: the pillbox the plan of this build picks there. once per file (a plan
    // costs, and the room is the same in every frame)
    static final Check REPLAY_PILLBOX = new Check() {
        public String name() {
            return "replay-pillbox";
        }

        public void run(Ctx c) {
            if (c.index != lastSpawnerFrame(c.file)) return;
            int[] s = spawner(c);
            if (!c.world.known(s[0], s[1], s[2], (int) Math.ceil(BlazePillbox.FAR) + 4, 4, 5)) {
                c.skip();
                return;
            }
            Cells walk = c.world.walk;
            int[] start = startBy(walk, s[0], s[1], s[2]);
            if (start == null) return;
            List<Vec3> eyes = openEyes(c.world.body, BlazeCover.spawnEyes(s[0], s[1], s[2]));
            BlazePillbox.Plan plan = c.file.tuning("pillbox_bent", 1) > 0 ? BlazePillbox.planBent(walk, start, s[0], s[1], s[2], eyes) : null;
            if (plan == null) plan = BlazePillbox.plan(walk, start, s[0], s[1], s[2], eyes);
            if (plan == null) return;
            int[] b = plan.cell();
            String subject = "pillbox " + at(b) + " by the spawner " + at(s);
            Cells body = after(c.world.body, plan.blocks());
            String why = unstandable(c.world, after(walk, plan.blocks()), body, b);
            if (!why.isEmpty()) c.flag(name(), subject, why);
            if (SafeStand.unsafe(body, b[0], b[1], b[2])) {
                int[] side = SafeStand.blockSide(body, b[0], b[1], b[2]);
                c.flag(name(), subject, "a hit from any side, the box built, ends in lava or a fall of " + SafeStand.HURTS + "+"
                        + (side == null ? "" : "; a block at " + at(side) + " would stop it"));
            }
        }
    };

    // the last frame of the file where the brew box would be planned (the brew box on in the run's tuning, or a box decided): the
    // box this build plans from where the buddy stood. once per file
    static final Check REPLAY_BREWBOX = new Check() {
        public String name() {
            return "replay-brewbox";
        }

        public void run(Ctx c) {
            if (!c.last()) return;
            boolean wanted = BrewBox.on(c.file.tuning("brew_box", 0));
            for (SnapshotFrame f : c.file.frames) for (SnapshotPick p : f.picks) wanted |= p.kind.equals("brewbox");
            if (!wanted) return;
            SnapshotFrame f = c.frame;
            int[] u = feet(f);
            if (!c.world.known(u[0], u[1], u[2], BrewBox.WALK + 4, 4, 4)) {
                c.skip();
                return;
            }
            int[] s = spawner(c);
            List<Vec3> eyes = s == null ? List.of() : openEyes(c.world.body, BlazeCover.spawnEyes(s[0], s[1], s[2]));
            BrewBox.Box box = BrewBox.plan(c.world.body, c.world.walk, u, eyes, s == null ? Double.NaN : s[0], s == null ? Double.NaN : s[2], new HashSet<>());
            if (box == null) return;
            String subject = "brew box " + at(box.back()) + " from " + at(u);
            Cells body = after(c.world.body, box.blocks());
            if (!BrewBox.shut(body, box)) c.flag(name(), subject, "its shell is not shut once its blocks are in");
            String why = unstandable(c.world, after(c.world.walk, box.blocks()), body, box.back());
            if (!why.isEmpty()) c.flag(name(), subject, why);
        }
    };

    // the last frame of the file with a spawner near, -1 for none (worked out once per file)
    static int lastSpawnerFrame(SnapshotFile file) {
        if (file.lastSpawner == null) {
            file.lastSpawner = -1;
            for (int i = file.frames.size() - 1; i >= 0; i--) {
                if (spawner(new Ctx(file, i, new ArrayList<>(), new int[1])) != null) {
                    file.lastSpawner = i;
                    break;
                }
            }
        }
        return file.lastSpawner;
    }

    static final List<Check> ALL = List.of(PICK_STANDABLE, PICK_KNOCK_SAFE, PICK_HIDDEN_FROM_BLAZES, PICK_HIDDEN_FROM_GHASTS, HIT_IN_REACH,
            HIT_NOT_THROUGH_BLOCKS, REPLAY_BLAZE_COVER, REPLAY_GHAST_COVER, REPLAY_PILLBOX, REPLAY_BREWBOX);

    // every check over every frame of a file. SafeStand's switch as the run had it (tuning safe_stand), so the planners choose
    // as they did there
    static List<Violation> run(SnapshotFile file, List<Check> checks, int[] skips) {
        List<Violation> out = new ArrayList<>();
        Boolean was = SafeStand.forced;
        SafeStand.forced = file.tuning("safe_stand", 0) >= 1;
        try {
            for (int i = 0; i < file.frames.size(); i++) {
                Ctx c = new Ctx(file, i, out, skips);
                for (Check check : checks) check.run(c);
            }
        } finally {
            SafeStand.forced = was;
        }
        return out;
    }
}
