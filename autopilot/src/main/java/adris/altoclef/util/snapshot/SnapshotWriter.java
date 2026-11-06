package adris.altoclef.util.snapshot;

import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

// a snapshot file: one gzip JSON object, the header, the palette and the frames oldest first. the cells and the columns under the
// box are run-length text ("3*120,5,7*3": index 3 a hundred and twenty times, 5 once, 7 three times); a frame whose box is the
// frame before's (same place, same cells) says "same" instead. format 1:
//   {format, reason, written, build, dimension, tuning {key: value}, cost {captures, avgMicros, maxMicros},
//    box {half, down, up, below}, palette [{id, p, w, c, f}],
//    frames [{t, gt, ms, me {...}, ents [...], blocks {o, s, cells, below | same}, chain, tasks, leaf, user, combat, fight,
//             target, picks [...]}]}
// pure: no game, so the tests write fixtures with it and read them back the way the harness reads the real ones
public final class SnapshotWriter {
    public static final int FORMAT = 1;

    private SnapshotWriter() {
    }

    // what the file says about itself
    public static final class Header {
        public String reason = "";
        public String written = "";
        public String build = "";
        public String dimension = "";
        public Map<String, String> tuning = Map.of();
        public long captures;
        public double avgMicros;
        public double maxMicros;
        public int half, down, up, below;
    }

    public static void write(OutputStream out, Header header, List<SnapshotPalette.Entry> palette, List<SnapshotFrame> frames) throws IOException {
        GZIPOutputStream gz = new GZIPOutputStream(out, 1 << 16);
        Writer text = new OutputStreamWriter(gz, StandardCharsets.UTF_8);
        JsonWriter w = new JsonWriter(text);
        w.beginObject();
        w.name("format").value(FORMAT);
        w.name("reason").value(header.reason);
        w.name("written").value(header.written);
        w.name("build").value(header.build);
        w.name("dimension").value(header.dimension);
        w.name("tuning").beginObject();
        for (Map.Entry<String, String> e : header.tuning.entrySet()) w.name(e.getKey()).value(e.getValue());
        w.endObject();
        w.name("cost").beginObject();
        w.name("captures").value(header.captures);
        w.name("avgMicros").value(round(header.avgMicros, 1));
        w.name("maxMicros").value(round(header.maxMicros, 1));
        w.endObject();
        w.name("box").beginObject();
        w.name("half").value(header.half);
        w.name("down").value(header.down);
        w.name("up").value(header.up);
        w.name("below").value(header.below);
        w.endObject();
        w.name("palette").beginArray();
        for (SnapshotPalette.Entry e : palette) {
            w.beginObject();
            w.name("id").value(e.id());
            if (!e.props().isEmpty()) w.name("p").value(e.props());
            w.name("w").value(String.valueOf(e.walk()));
            w.name("c").value(String.valueOf(e.body()));
            w.name("f").value(e.full() ? 1 : 0);
            w.endObject();
        }
        w.endArray();
        w.name("frames").beginArray();
        SnapshotFrame before = null;
        for (SnapshotFrame f : frames) {
            frame(w, f, before);
            before = f;
        }
        w.endArray();
        w.endObject();
        w.flush();
        gz.finish();
        gz.flush();
    }

    private static void frame(JsonWriter w, SnapshotFrame f, SnapshotFrame before) throws IOException {
        w.beginObject();
        w.name("t").value(f.tick);
        w.name("gt").value(f.gameTime);
        w.name("ms").value(f.wallMillis);
        w.name("me").beginObject();
        triple(w, "p", f.x, f.y, f.z, 3);
        triple(w, "v", f.vx, f.vy, f.vz, 3);
        w.name("eye").value(round(f.eyeY, 3));
        w.name("yaw").value(round(f.yaw, 1));
        w.name("pitch").value(round(f.pitch, 1));
        w.name("hp").value(round(f.hp, 2));
        w.name("abs").value(round(f.absorption, 2));
        w.name("reach").value(round(f.reach, 2));
        w.name("fire").value(f.fireTicks);
        w.name("hurt").value(f.hurtTime);
        w.name("air").value(f.air);
        w.name("ground").value(f.onGround);
        w.name("lava").value(f.inLava);
        w.name("held").value(f.held);
        w.name("off").value(f.offhand);
        w.name("armour").beginArray();
        for (String a : f.armour) w.value(a);
        w.endArray();
        w.name("effects").beginArray();
        for (String e : f.effects) w.value(e);
        w.endArray();
        w.name("bag").beginObject();
        for (Map.Entry<String, Integer> e : f.bag.entrySet()) w.name(e.getKey()).value(e.getValue());
        w.endObject();
        if (f.damage != null) {
            w.name("dmg").value(f.damage);
            if (f.damageBy != null) w.name("by").value(f.damageBy);
            if (f.damageById >= 0) w.name("byId").value(f.damageById);
        }
        w.endObject();
        w.name("ents").beginArray();
        for (SnapshotFrame.Ent e : f.entities) {
            w.beginObject();
            w.name("k").value(e.type);
            w.name("id").value(e.id);
            triple(w, "p", e.x, e.y, e.z, 3);
            triple(w, "v", e.vx, e.vy, e.vz, 3);
            w.name("eye").value(round(e.eye, 3));
            w.name("w").value(round(e.width, 3));
            w.name("h").value(round(e.height, 3));
            if (e.hp >= 0) w.name("hp").value(round(e.hp, 2));
            if (e.fire) w.name("fire").value(true);
            if (e.angry) w.name("angry").value(true);
            if (e.los) w.name("los").value(true);
            if (e.charge != 0) w.name("charge").value(round(e.charge, 2));
            w.endObject();
        }
        w.endArray();
        if (f.cells != null) {
            w.name("blocks").beginObject();
            w.name("o").beginArray().value(f.ox).value(f.oy).value(f.oz).endArray();
            w.name("s").beginArray().value(f.sx).value(f.sy).value(f.sz).endArray();
            if (before != null && sameBox(before, f)) {
                w.name("same").value(true);
            } else {
                w.name("cells").value(runs(f.cells));
                w.name("below").value(runs(f.below));
            }
            w.endObject();
        }
        if (f.chain != null) w.name("chain").value(f.chain);
        strings(w, "tasks", f.tasks);
        if (f.leaf != null) w.name("leaf").value(f.leaf);
        strings(w, "user", f.userTasks);
        if (f.combat != null) w.name("combat").value(f.combat);
        if (f.fight != null) w.name("fight").value(f.fight);
        w.name("target").value(f.target);
        w.name("picks").beginArray();
        for (SnapshotPick p : f.picks) pick(w, p);
        w.endArray();
        w.endObject();
    }

    private static void pick(JsonWriter w, SnapshotPick p) throws IOException {
        w.beginObject();
        w.name("kind").value(p.kind);
        w.name("t").value(p.tick);
        if (p.cell != null) cell(w, "cell", p.cell);
        if (!p.cells.isEmpty()) {
            w.name("cells").beginObject();
            for (Map.Entry<String, int[]> e : p.cells.entrySet()) cell(w, e.getKey(), e.getValue());
            w.endObject();
        }
        if (!p.blocks.isEmpty()) {
            w.name("blocks").beginArray();
            for (int[] b : p.blocks) w.beginArray().value(b[0]).value(b[1]).value(b[2]).endArray();
            w.endArray();
        }
        if (p.spawner != null) cell(w, "spawner", p.spawner);
        if (p.target >= 0) w.name("target").value(p.target);
        if (p.targetType != null) w.name("targetType").value(p.targetType);
        if (p.eye != null) triple(w, "eye", p.eye[0], p.eye[1], p.eye[2], 4);
        if (p.box != null) {
            w.name("box").beginArray();
            for (double v : p.box) w.value(round(v, 4));
            w.endArray();
        }
        if (p.reach > 0) w.name("reach").value(round(p.reach, 3));
        w.endObject();
    }

    static boolean sameBox(SnapshotFrame a, SnapshotFrame b) {
        return a.cells != null && a.ox == b.ox && a.oy == b.oy && a.oz == b.oz && a.sx == b.sx && a.sy == b.sy && a.sz == b.sz
                && (a.cells == b.cells || Arrays.equals(a.cells, b.cells)) && (a.below == b.below || Arrays.equals(a.below, b.below));
    }

    // "3*120,5,7*3"
    public static String runs(short[] v) {
        StringBuilder sb = new StringBuilder(v.length / 4);
        int i = 0;
        while (i < v.length) {
            int j = i + 1;
            while (j < v.length && v[j] == v[i]) j++;
            if (sb.length() > 0) sb.append(',');
            sb.append(v[i]);
            if (j - i > 1) sb.append('*').append(j - i);
            i = j;
        }
        return sb.toString();
    }

    public static String runs(int[] v) {
        StringBuilder sb = new StringBuilder();
        if (v == null) return "";
        int i = 0;
        while (i < v.length) {
            int j = i + 1;
            while (j < v.length && v[j] == v[i]) j++;
            if (sb.length() > 0) sb.append(',');
            sb.append(v[i]);
            if (j - i > 1) sb.append('*').append(j - i);
            i = j;
        }
        return sb.toString();
    }

    // the runs back into n numbers
    public static int[] unruns(String s, int n) {
        int[] out = new int[n];
        if (s == null || s.isEmpty()) return out;
        int at = 0;
        for (String part : s.split(",")) {
            int star = part.indexOf('*');
            int value = Integer.parseInt(star < 0 ? part : part.substring(0, star));
            int count = star < 0 ? 1 : Integer.parseInt(part.substring(star + 1));
            for (int k = 0; k < count && at < n; k++) out[at++] = value;
        }
        return out;
    }

    private static void strings(JsonWriter w, String name, List<String> list) throws IOException {
        if (list == null || list.isEmpty()) return;
        w.name(name).beginArray();
        for (String s : list) w.value(s);
        w.endArray();
    }

    private static void cell(JsonWriter w, String name, int[] c) throws IOException {
        w.name(name).beginArray().value(c[0]).value(c[1]).value(c[2]).endArray();
    }

    private static void triple(JsonWriter w, String name, double a, double b, double c, int places) throws IOException {
        w.name(name).beginArray().value(round(a, places)).value(round(b, places)).value(round(c, places)).endArray();
    }

    private static double round(double v, int places) {
        if (!Double.isFinite(v)) return 0;
        double s = Math.pow(10, places);
        return Math.round(v * s) / s;
    }
}
