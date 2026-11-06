package adris.altoclef.util.helpers;

import adris.altoclef.Debug;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

// the buddy's fork: the way back into hiding, measured (steering entry 18, 4 Nov 2026: the core of it is that the buddy does
// not get back into hiding well or fast enough, and dies of it). each tick of the blaze fight with a corner: in the
// corner's cell or out of it, the hp lost in each, and every way back from the moment it was decided to the moment the
// buddy stands in the cell. one summary line every ten game seconds and after each way back, which the drills read (drillrun.py, the run's last line).
// ticked by AltoClef every tick (BlazeShelter.tickHide), so a run of the mob defense's counts too; reset when a rods task starts
public final class HideStats {
    private long ticksIn, ticksOut;
    private double lostIn, lostOut;
    // of the out: at the look out (BlazeShelter.lookingOut), where the lure means to be seen. steering 35 counts a trip out
    // apart from it; out stays as it was so the numbers before it compare
    private long ticksLook;
    private double lostLook;
    private float lastHp = Float.NaN;
    private long backSince = -1;
    private final List<Integer> backs = new ArrayList<>();
    private long loggedAt = Long.MIN_VALUE / 2;

    // going back: out of the cell and on the way to it (not fighting, not looking out)
    // in hiding: within IN of the corner cell's middle across, its edges taken in (the buddy often stands on the cell's edge,
    // half in the next one, and the narrow test of BlazeShelter.in counted that as out: 79% out in corner21's first runs)
    static final double IN = 1.2;

    public void tick(LocalPlayer p, BlockPos corner, long now, boolean goingBack) {
        tick(p, corner, now, goingBack, false);
    }

    public void tick(LocalPlayer p, BlockPos corner, long now, boolean goingBack, boolean lookingOut) {
        double dx = p.getX() - (corner.getX() + 0.5), dz = p.getZ() - (corner.getZ() + 0.5);
        boolean in = dx * dx + dz * dz <= IN * IN && Math.abs(p.getY() - corner.getY()) < 1;
        float hp = p.getHealth();
        if (!Float.isNaN(lastHp) && hp < lastHp) {
            if (in) lostIn += lastHp - hp;
            else lostOut += lastHp - hp;
            if (!in && lookingOut) lostLook += lastHp - hp;
        }
        lastHp = hp;
        if (in) ticksIn++;
        else ticksOut++;
        if (!in && lookingOut) ticksLook++;
        boolean logNow = false;
        if (!in && goingBack && backSince < 0) backSince = now;
        if (!in && !goingBack) backSince = -1;
        if (in && backSince >= 0) {
            backs.add((int) Math.max(0, now - backSince));
            backSince = -1;
            logNow = true;
        }
        if (logNow || now - loggedAt >= 200 || now < loggedAt) {
            loggedAt = now;
            List<Integer> s = new ArrayList<>(backs);
            s.sort(null);
            Debug.logInternal(String.format(java.util.Locale.ROOT,
                    "hide: in %d ticks, out %d; hp lost in %.1f, out %.1f; back %d times, median %d, worst %d ticks%s; of the out at the look out %d ticks, %.1f hp",
                    ticksIn, ticksOut, lostIn, lostOut, s.size(), s.isEmpty() ? 0 : s.get(s.size() / 2),
                    s.isEmpty() ? 0 : s.get(s.size() - 1), backSince >= 0 ? ", one under way " + (now - backSince) : "", ticksLook, lostLook));
        }
    }
}
