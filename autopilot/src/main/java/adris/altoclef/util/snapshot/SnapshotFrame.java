package adris.altoclef.util.snapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// one look at the situation round the buddy (Snapshots): the buddy, the living things and shots near the buddy, the blocks of a
// box round the buddy and what the autopilot decided that tick. plain data, no world in here, so the writer and the tests' reader
// need no game
public final class SnapshotFrame {
    // the recorder's own tick count (a server time correction can jump the game's), the game time and the wall clock
    public long tick;
    public long gameTime;
    public long wallMillis;

    // ---- the buddy
    public double x, y, z, eyeY;
    public double vx, vy, vz;
    public float yaw, pitch;
    public float hp, absorption;
    // the entity interaction range, what a swing may reach
    public double reach;
    public int fireTicks, hurtTime, air;
    public boolean onGround, inLava;
    public String held = "", offhand = "";
    public String[] armour = new String[0];
    // "fire_resistance 600 0": the effect, ticks left, amplifier
    public List<String> effects = new ArrayList<>();
    public Map<String, Integer> bag = new LinkedHashMap<>();
    // the last damage taken: its kind (the death message's id), and who dealt it
    public String damage;
    public String damageBy;
    public int damageById = -1;

    public List<Ent> entities = new ArrayList<>();

    // ---- the blocks: a box from (ox, oy, oz), sx by sy by sz, as palette indices, x fastest, then z, then y (index)
    public int ox, oy, oz, sx, sy, sz;
    public short[] cells;
    // per column of the box (x fastest, then z): what is under the box's bottom cell, when that cell is open: depth << 16 | palette
    // index of the first cell that is not open, NONE_BELOW for nothing within the scan, 0 for a column not looked at (its bottom
    // is not open, so nothing under it counts)
    public int[] below;
    public static final int NONE_BELOW = -1;

    // ---- the decision
    // the chain that had the wheel, its tasks root first, the leaf's debug state, and the user's chain's tasks when another chain
    // had the wheel
    public String chain;
    public List<String> tasks = new ArrayList<>();
    public String leaf;
    public List<String> userTasks = new ArrayList<>();
    // the combat machine: its mode, the name of the fight's foe, the fight target's entity id (-1 for none)
    public String combat;
    public String fight;
    public int target = -1;
    // the cells and hits decided since the frame before
    public List<SnapshotPick> picks = new ArrayList<>();

    public int index(int x, int y, int z) {
        return ((y - oy) * sz + (z - oz)) * sx + (x - ox);
    }

    public boolean inBox(int x, int y, int z) {
        return x >= ox && x < ox + sx && y >= oy && y < oy + sy && z >= oz && z < oz + sz;
    }

    // a living thing or a shot near the buddy
    public static final class Ent {
        // the entity type without its namespace ("blaze", "small_fireball")
        public String type;
        public int id;
        public double x, y, z;
        public double vx, vy, vz;
        // eye height over its feet, and its box
        public double eye, width, height;
        public float hp = -1;
        public boolean fire;
        // hostile to the buddy as the autopilot judges it (EntityHelper.isAngryAtPlayer), and a clear line from its eyes to the
        // buddy's (the game's own test)
        public boolean angry;
        public boolean los;
        // a blaze's charge, a ghast about to shoot (1 or 0), a creeper's swell (0 to 1)
        public float charge;

        public double eyeY() {
            return y + eye;
        }
    }
}
