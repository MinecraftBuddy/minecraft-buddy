package adris.altoclef.util.helpers;

import adris.altoclef.util.snapshot.SnapshotFrame;
import adris.altoclef.util.snapshot.SnapshotPalette;
import adris.altoclef.util.snapshot.SnapshotPick;
import adris.altoclef.util.snapshot.SnapshotWriter;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

// a snapshot file read back (SnapshotWriter has the format): the header, the palette and the frames, a "same" box filled in from
// the frame before
final class SnapshotFile {
    final String name;
    final String reason;
    final String build;
    final Map<String, String> tuning = new LinkedHashMap<>();
    final double avgMicros;
    final double maxMicros;
    final List<SnapshotPalette.Entry> palette = new ArrayList<>();
    final List<SnapshotFrame> frames = new ArrayList<>();
    // the checks' note of the last frame with a spawner near (SnapshotChecks.lastSpawnerFrame)
    Integer lastSpawner;

    private SnapshotFile(String name, JsonObject o) {
        this.name = name;
        int format = o.get("format").getAsInt();
        if (format != SnapshotWriter.FORMAT) throw new IllegalArgumentException("format " + format + ", this harness reads " + SnapshotWriter.FORMAT);
        reason = str(o, "reason");
        build = str(o, "build");
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("tuning").entrySet()) tuning.put(e.getKey(), e.getValue().getAsString());
        JsonObject cost = o.getAsJsonObject("cost");
        avgMicros = cost.get("avgMicros").getAsDouble();
        maxMicros = cost.get("maxMicros").getAsDouble();
        for (JsonElement el : o.getAsJsonArray("palette")) {
            JsonObject p = el.getAsJsonObject();
            palette.add(new SnapshotPalette.Entry(str(p, "id"), p.has("p") ? str(p, "p") : "", str(p, "w").charAt(0), str(p, "c").charAt(0),
                    p.get("f").getAsInt() == 1));
        }
        SnapshotFrame before = null;
        for (JsonElement el : o.getAsJsonArray("frames")) {
            SnapshotFrame f = frame(el.getAsJsonObject(), before);
            frames.add(f);
            before = f;
        }
    }

    static SnapshotFile read(Path path) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(path))) {
            return read(path.getFileName().toString(), in);
        }
    }

    static SnapshotFile read(String name, InputStream gunzipped) {
        JsonObject o = JsonParser.parseReader(new InputStreamReader(gunzipped, StandardCharsets.UTF_8)).getAsJsonObject();
        return new SnapshotFile(name, o);
    }

    double tuning(String key, double fallback) {
        String v = tuning.get(key);
        if (v == null) return fallback;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // seconds from this frame to the file's last one (the death, or the end of a near miss)
    double before(SnapshotFrame f) {
        return (frames.get(frames.size() - 1).tick - f.tick) / 20.0;
    }

    private static SnapshotFrame frame(JsonObject o, SnapshotFrame before) {
        SnapshotFrame f = new SnapshotFrame();
        f.tick = o.get("t").getAsLong();
        f.gameTime = o.get("gt").getAsLong();
        f.wallMillis = o.get("ms").getAsLong();
        JsonObject me = o.getAsJsonObject("me");
        double[] p = triple(me, "p");
        f.x = p[0];
        f.y = p[1];
        f.z = p[2];
        double[] v = triple(me, "v");
        f.vx = v[0];
        f.vy = v[1];
        f.vz = v[2];
        f.eyeY = me.get("eye").getAsDouble();
        f.yaw = me.get("yaw").getAsFloat();
        f.pitch = me.get("pitch").getAsFloat();
        f.hp = me.get("hp").getAsFloat();
        f.absorption = me.get("abs").getAsFloat();
        f.reach = me.get("reach").getAsDouble();
        f.fireTicks = me.get("fire").getAsInt();
        f.hurtTime = me.get("hurt").getAsInt();
        f.air = me.get("air").getAsInt();
        f.onGround = me.get("ground").getAsBoolean();
        f.inLava = me.get("lava").getAsBoolean();
        f.held = str(me, "held");
        f.offhand = str(me, "off");
        JsonArray armour = me.getAsJsonArray("armour");
        f.armour = new String[armour.size()];
        for (int i = 0; i < armour.size(); i++) f.armour[i] = armour.get(i).getAsString();
        for (JsonElement e : me.getAsJsonArray("effects")) f.effects.add(e.getAsString());
        for (Map.Entry<String, JsonElement> e : me.getAsJsonObject("bag").entrySet()) f.bag.put(e.getKey(), e.getValue().getAsInt());
        if (me.has("dmg")) {
            f.damage = str(me, "dmg");
            f.damageBy = me.has("by") ? str(me, "by") : null;
            f.damageById = me.has("byId") ? me.get("byId").getAsInt() : -1;
        }
        for (JsonElement el : o.getAsJsonArray("ents")) {
            JsonObject e = el.getAsJsonObject();
            SnapshotFrame.Ent n = new SnapshotFrame.Ent();
            n.type = str(e, "k");
            n.id = e.get("id").getAsInt();
            double[] ep = triple(e, "p");
            n.x = ep[0];
            n.y = ep[1];
            n.z = ep[2];
            double[] ev = triple(e, "v");
            n.vx = ev[0];
            n.vy = ev[1];
            n.vz = ev[2];
            n.eye = e.get("eye").getAsDouble();
            n.width = e.get("w").getAsDouble();
            n.height = e.get("h").getAsDouble();
            n.hp = e.has("hp") ? e.get("hp").getAsFloat() : -1;
            n.fire = e.has("fire") && e.get("fire").getAsBoolean();
            n.angry = e.has("angry") && e.get("angry").getAsBoolean();
            n.los = e.has("los") && e.get("los").getAsBoolean();
            n.charge = e.has("charge") ? e.get("charge").getAsFloat() : 0;
            f.entities.add(n);
        }
        if (o.has("blocks")) {
            JsonObject b = o.getAsJsonObject("blocks");
            JsonArray origin = b.getAsJsonArray("o"), size = b.getAsJsonArray("s");
            f.ox = origin.get(0).getAsInt();
            f.oy = origin.get(1).getAsInt();
            f.oz = origin.get(2).getAsInt();
            f.sx = size.get(0).getAsInt();
            f.sy = size.get(1).getAsInt();
            f.sz = size.get(2).getAsInt();
            if (b.has("same") && before != null) {
                f.cells = before.cells;
                f.below = before.below;
            } else {
                int[] cells = SnapshotWriter.unruns(str(b, "cells"), f.sx * f.sy * f.sz);
                f.cells = new short[cells.length];
                for (int i = 0; i < cells.length; i++) f.cells[i] = (short) cells[i];
                f.below = SnapshotWriter.unruns(str(b, "below"), f.sx * f.sz);
            }
        }
        f.chain = o.has("chain") ? str(o, "chain") : null;
        strings(o, "tasks", f.tasks);
        f.leaf = o.has("leaf") ? str(o, "leaf") : null;
        strings(o, "user", f.userTasks);
        f.combat = o.has("combat") ? str(o, "combat") : null;
        f.fight = o.has("fight") ? str(o, "fight") : null;
        f.target = o.has("target") ? o.get("target").getAsInt() : -1;
        for (JsonElement el : o.getAsJsonArray("picks")) f.picks.add(pick(el.getAsJsonObject()));
        return f;
    }

    private static SnapshotPick pick(JsonObject o) {
        SnapshotPick p = new SnapshotPick(str(o, "kind"));
        p.tick = o.get("t").getAsLong();
        if (o.has("cell")) p.cell = cell(o.getAsJsonArray("cell"));
        if (o.has("cells")) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("cells").entrySet()) p.cells.put(e.getKey(), cell(e.getValue().getAsJsonArray()));
        }
        if (o.has("blocks")) for (JsonElement b : o.getAsJsonArray("blocks")) p.blocks.add(cell(b.getAsJsonArray()));
        if (o.has("spawner")) p.spawner = cell(o.getAsJsonArray("spawner"));
        if (o.has("target")) p.target = o.get("target").getAsInt();
        if (o.has("targetType")) p.targetType = str(o, "targetType");
        if (o.has("eye")) p.eye = triple(o, "eye");
        if (o.has("box")) {
            JsonArray b = o.getAsJsonArray("box");
            p.box = new double[b.size()];
            for (int i = 0; i < b.size(); i++) p.box[i] = b.get(i).getAsDouble();
        }
        if (o.has("reach")) p.reach = o.get("reach").getAsDouble();
        return p;
    }

    private static int[] cell(JsonArray a) {
        return new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
    }

    private static double[] triple(JsonObject o, String name) {
        JsonArray a = o.getAsJsonArray(name);
        return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
    }

    private static void strings(JsonObject o, String name, List<String> into) {
        if (!o.has(name)) return;
        for (JsonElement e : o.getAsJsonArray(name)) into.add(e.getAsString());
    }

    private static String str(JsonObject o, String name) {
        JsonElement e = o.get(name);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }
}
