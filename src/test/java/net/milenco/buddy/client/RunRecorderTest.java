package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The run recording's format: the frames' deltas, the autopilot's lines, and segments written and read back. */
class RunRecorderTest {
	private static JsonObject json(String s) { return JsonParser.parseString(s.replace('\'', '"')).getAsJsonObject(); }

	/** A few frames: an entity comes, moves, changes its gear, the buddy crouches, the entity goes, a player comes. */
	private static final List<JsonObject> FRAMES = List.of(
		json("{'dim':'overworld','x':1.5,'y':64,'z':2.25,'yaw':90.5,'tk':100,'players':[],"
			+ "'entities':[{'id':7,'x':3,'y':64,'z':3,'kind':'zombie','hp':20}]}"),
		json("{'dim':'overworld','x':1.75,'y':64,'z':2.25,'yaw':91,'tk':102,'players':[],"
			+ "'entities':[{'id':7,'x':3.5,'y':64,'z':3,'kind':'zombie','hp':20},{'id':9,'x':0,'y':70,'z':0,'kind':'skeleton','hp':20,'hand':'bow'}]}"),
		json("{'dim':'overworld','x':1.75,'y':64,'z':2.25,'yaw':91,'tk':104,'crouch':true,'players':[],"
			+ "'entities':[{'id':7,'x':3.5,'y':64,'z':3,'kind':'zombie','hp':16,'hurt':true,'hand':'iron_sword','armor':[null,'iron',null,null]},"
			+ "{'id':9,'x':0,'y':70,'z':0,'kind':'skeleton','hp':20,'hand':'bow'}]}"),
		json("{'dim':'overworld','x':1.75,'y':64,'z':2.25,'yaw':91,'tk':106,'players':[{'id':3,'name':'Steve','x':5,'y':64,'z':5}],"
			+ "'entities':[{'id':7,'x':3.5,'y':64,'z':3,'kind':'zombie','hp':16,'armor':[null,'iron',null,null]}],'breaks':[[1,63,2,'minecraft:stone']]}"),
		json("{'dim':'overworld','x':1.75,'y':64,'z':2.25,'yaw':91,'tk':108,'players':[{'id':3,'name':'Steve','x':5,'y':64,'z':6}],"
			+ "'entities':[{'id':11,'x':9,'y':64,'z':9,'kind':'item','item':'stone'},{'id':7,'x':3.5,'y':64,'z':3,'kind':'zombie','hp':16,'armor':[null,'iron',null,null]}]}"));

	@Test
	void deltasGiveEveryFrameBack() {
		JsonObject at = FRAMES.get(0);
		for (int i = 1; i < FRAMES.size(); i++) {
			JsonObject d = FrameDelta.encode(FRAMES.get(i - 1), FRAMES.get(i));
			// through text and back, as in a file
			at = FrameDelta.apply(at, json(d.toString()));
			assertEquals(FRAMES.get(i), at, "frame " + i);
		}
	}

	@Test
	void deltasSayOnlyWhatChanged() {
		JsonObject d1 = FrameDelta.encode(FRAMES.get(0), FRAMES.get(1));
		assertEquals(json("{'x':1.75,'yaw':91,'tk':102,'entities':{'+':[{'id':9,'x':0,'y':70,'z':0,'kind':'skeleton','hp':20,'hand':'bow'}],"
			+ "'~':{'7':{'x':3.5}}}}"), d1);
		JsonObject d3 = FrameDelta.encode(FRAMES.get(2), FRAMES.get(3));
		assertEquals(json("{'hp':16,'-':['hurt','hand']}").get("-"), d3.getAsJsonObject("entities").getAsJsonObject("~").getAsJsonObject("7").get("-"));
		assertEquals(json("{'a':[9]}").get("a"), d3.getAsJsonObject("entities").get("-"));
		assertEquals(json("{'a':['crouch']}").get("a"), d3.get("-"));
		assertFalse(d3.getAsJsonObject("entities").has("order"));
		// a new one ahead of one that stays: the order is told
		JsonObject d4 = FrameDelta.encode(FRAMES.get(3), FRAMES.get(4));
		assertEquals(json("{'a':[11,7]}").get("a"), d4.getAsJsonObject("entities").get("order"));
		assertEquals(json("{'a':['breaks']}").get("a"), d4.get("-"));
		assertTrue(FrameDelta.encode(FRAMES.get(4), FRAMES.get(4)).isEmpty());
	}

	private record Line(long ms, String text, int n, long first) {}

	@Test
	void thinkLinesCountRepeats() {
		ThinkLines t = new ThinkLines();
		List<Line> out = new ArrayList<>();
		ThinkLines.Out to = (ms, text, n, first) -> out.add(new Line(ms, text, n, first));
		t.offer("rods: let the blaze go, out of reach (it was 9.5 off)", 0, to);
		t.offer("rods: let the blaze go, out of reach (it was 12.1 off)", 1_000, to);
		t.offer("rods: let the blaze go, out of reach (it was 8.0 off)", 2_000, to);
		t.offer("baritone: Took 53ms, 1200 movements considered", 2_100, to);
		t.offer("vitals hp=20.0 abs=0.0", 2_200, to);
		t.offer("baritone: No path found =(", 3_000, to);
		assertEquals(List.of(new Line(0, "rods: let the blaze go, out of reach (it was 9.5 off)", 1, 0),
			new Line(2_000, "rods: let the blaze go, out of reach (it was 8.0 off)", 3, 0),
			new Line(3_000, "baritone: No path found =(", 1, 0)), out);
		// the autopilot's own count of a line it held back counts for that line
		t.offer("baritone: No path found =( x49 over 3000t", 4_000, to);
		t.flush(5_000, to);
		assertEquals(3, out.size());
		t.flush(14_100, to);
		assertEquals(new Line(4_000, "baritone: No path found =(", 49, 3_000), out.get(3));
		// a line again after it went quiet is a new one
		t.offer("baritone: No path found =(", 30_000, to);
		assertEquals(new Line(30_000, "baritone: No path found =(", 1, 0), out.get(4));
		// a line that goes on and on is told every ten seconds
		for (int s = 31; s <= 45; s++) t.offer("baritone: No path found =(", s * 1000L, to);
		assertEquals(new Line(40_000, "baritone: No path found =(", 11, 30_000), out.get(5));
		assertEquals(6, out.size());
	}

	// ---------- a recording written and read back ----------

	private record Rec(String file, long offset, char kind, long ms, int tk, byte[] payload) {
		String text() { return new String(payload, StandardCharsets.UTF_8); }
	}

	/** Every record of a segment, as tools/recording.py reads them. */
	private static List<Rec> read(Path f) throws IOException {
		List<Rec> out = new ArrayList<>();
		try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(f)))) {
			long offset = 0;
			while (true) {
				int kind = in.read();
				if (kind < 0) break;
				long ms = in.readLong();
				int tk = in.readInt();
				int len = in.readInt();
				byte[] b = new byte[len];
				in.readFully(b);
				out.add(new Rec(f.getFileName().toString(), offset, (char) kind, ms, tk, b));
				offset += 17 + len;
			}
		} catch (EOFException cutShort) { }
		return out;
	}

	/** A copy's or a change's payload: its magic, its JSON as World3d writes it, and made-up sections. */
	private static World3d.Body body(String magic, String meta, int size) {
		byte[] m = meta.replace('\'', '"').getBytes(StandardCharsets.UTF_8);
		return out -> { out.writeBytes(magic); out.writeInt(m.length); out.write(m); for (int i = 0; i < size; i++) out.writeByte(i * 31); };
	}

	@Test
	void segmentsAreReadBack(@TempDir Path tmp) throws IOException {
		// RECORDING_TEST_OUT keeps the recording for a look with tools/recording.py
		String keep = System.getenv("RECORDING_TEST_OUT");
		Path dir = keep != null ? Paths.get(keep) : tmp.resolve("recordings");
		RunRecorder.Segments s = new RunRecorder.Segments(dir);
		long t0 = 1_700_000_000_000L;
		s.line(t0 - 5, 1, "{\"t\":1,\"type\":\"obtain\",\"action\":\"start\"}".getBytes(StandardCharsets.UTF_8));
		// a frame before any copy has nothing to be drawn in
		s.frame(t0, 1, FRAMES.get(0));
		String copy1 = "{'base':1000,'seq':1000}";
		s.copy(t0, 2, body("MQ3D", copy1, 5000));
		for (int i = 0; i < FRAMES.size(); i++) {
			s.frame(t0 + 100 + i * 100, 100 + 2 * i, FRAMES.get(i));
			assertFalse(s.change(t0 + 100 + i * 100, 100 + 2 * i, body("MQ3L", "{'base':1000,'seq':" + (1001 + i) + ",'prev':" + (1000 + i) + "}", 100 + i), 50));
		}
		s.line(t0 + 700, 120, "{\"t\":2,\"type\":\"think\",\"text\":\"No path found =(\",\"n\":1}".getBytes(StandardCharsets.UTF_8));
		// past the minute: the next frame begins a segment of its own with a key frame
		s.frame(t0 + 61_000, 1300, FRAMES.get(1));
		s.change(t0 + 61_000, 1300, body("MQ3L", "{'base':1000,'seq':1006,'prev':1005}", 7), 50);
		s.frame(t0 + 61_100, 1302, FRAMES.get(2));
		// a new copy within the minute: C, then a K before the next D
		s.copy(t0 + 62_000, 1320, body("MQ3D", "{'base':2000,'seq':2000}", 300));
		s.frame(t0 + 62_100, 1322, FRAMES.get(3));
		s.frame(t0 + 62_200, 1324, FRAMES.get(4));
		s.end();

		List<String> index = Files.readAllLines(dir.resolve("index.jsonl"));
		assertEquals(2, index.size());
		JsonObject i1 = json(index.get(0)), i2 = json(index.get(1));
		String f1 = i1.get("file").getAsString(), f2 = i2.get("file").getAsString();
		assertEquals("seg-" + t0 + ".bin", f1);
		assertEquals(json("{'file':'" + f1 + "','offset':0}"), i1.getAsJsonObject("copy"));
		assertEquals(json("{'file':'" + f1 + "','offset':0}"), i2.getAsJsonObject("copy"));
		assertEquals("overworld", i1.get("dim").getAsString());
		assertEquals(Files.size(dir.resolve(f1)), i1.get("bytes").getAsLong());
		assertEquals(t0 + 61_000, i2.get("from_ms").getAsLong());
		assertEquals(1324, i2.get("to_tk").getAsLong());

		List<Rec> a = read(dir.resolve(f1)), b = read(dir.resolve(f2));
		StringBuilder kinds = new StringBuilder();
		for (Rec r : a) kinds.append(r.kind());
		// the line from before the first frame comes after the first key frame
		assertEquals("CKTLDLDLDLDLT", kinds.toString());
		kinds.setLength(0);
		for (Rec r : b) kinds.append(r.kind());
		assertEquals("KLDCKD", kinds.toString());
		assertEquals("MQ3D", new String(a.get(0).payload(), 0, 4, StandardCharsets.US_ASCII));
		assertEquals(4 + 4 + copy1.length() + 5000, a.get(0).payload().length);
		assertEquals("MQ3L", new String(a.get(3).payload(), 0, 4, StandardCharsets.US_ASCII));
		assertEquals(t0 - 5, a.get(2).ms());

		// every frame back from its key frame and the deltas after it
		List<JsonObject> got = new ArrayList<>();
		JsonObject at = null;
		for (Rec r : a) if (r.kind() == 'K') got.add(at = json(r.text())); else if (r.kind() == 'D') got.add(at = FrameDelta.apply(at, json(r.text())));
		assertEquals(FRAMES, got);
		got.clear();
		for (Rec r : b) if (r.kind() == 'K') got.add(at = json(r.text())); else if (r.kind() == 'D') got.add(at = FrameDelta.apply(at, json(r.text())));
		assertEquals(List.of(FRAMES.get(1), FRAMES.get(2), FRAMES.get(3), FRAMES.get(4)), got);
		// the second copy's place, for a later segment's index
		Rec c2 = b.get(3);
		assertEquals('C', c2.kind());
		assertEquals(17 + b.get(0).payload().length + 17 + b.get(1).payload().length + 17 + b.get(2).payload().length, c2.offset());
	}

	@Test
	void theInventoryAndVitalsBeginEverySegment(@TempDir Path tmp) throws IOException {
		RunRecorder.Segments s = new RunRecorder.Segments(tmp);
		byte[] inv1 = "{\"t\":1,\"type\":\"inventory\",\"things\":\"0:iron_sword(246)\"}".getBytes(StandardCharsets.UTF_8);
		byte[] inv2 = "{\"t\":5,\"type\":\"inventory\",\"things\":\"0:iron_sword(245)\"}".getBytes(StandardCharsets.UTF_8);
		byte[] vit = "{\"t\":2,\"type\":\"status\",\"hp\":20.0}".getBytes(StandardCharsets.UTF_8);
		byte[] other = "{\"t\":3,\"type\":\"doing\",\"action\":\"obtain\"}".getBytes(StandardCharsets.UTF_8);
		long t0 = 1_000_000;
		s.copy(t0, 1, body("MQ3D", "{'base':1,'seq':1}", 10));
		s.line(t0 + 10, 1, inv1, "inventory");
		s.frame(t0 + 100, 2, FRAMES.get(0));
		s.line(t0 + 150, 3, vit, "status");
		s.line(t0 + 160, 3, other);
		s.frame(t0 + 61_000, 100, FRAMES.get(1));
		s.line(t0 + 61_050, 101, inv2, "inventory");
		s.frame(t0 + 122_000, 200, FRAMES.get(2));
		s.end();
		List<String> files = new ArrayList<>();
		for (String l : Files.readAllLines(tmp.resolve("index.jsonl"))) files.add(json(l).get("file").getAsString());
		assertEquals(3, files.size());
		List<Rec> a = read(tmp.resolve(files.get(0))), b = read(tmp.resolve(files.get(1))), c = read(tmp.resolve(files.get(2)));
		// the first: the inventory held for the first K; the next ones begin with the last of each again
		assertEquals("CKTTT", kinds(a));
		assertEquals(new String(inv1, StandardCharsets.UTF_8), a.get(2).text());
		assertEquals("KTTT", kinds(b));
		assertEquals(new String(inv1, StandardCharsets.UTF_8), b.get(1).text());
		assertEquals(new String(vit, StandardCharsets.UTF_8), b.get(2).text());
		assertEquals(new String(inv2, StandardCharsets.UTF_8), b.get(3).text());
		assertEquals("KTT", kinds(c));
		assertEquals(new String(inv2, StandardCharsets.UTF_8), c.get(1).text());
		assertEquals(new String(vit, StandardCharsets.UTF_8), c.get(2).text());
	}

	@Test
	void aSegmentThatCannotBeOpenedIsTriedAgain(@TempDir Path tmp) throws IOException {
		// a file where the folder should be: no segment can be opened
		Path dir = tmp.resolve("recordings");
		Files.writeString(dir, "not a folder");
		RunRecorder.Segments s = new RunRecorder.Segments(dir);
		long t0 = 1_000_000;
		assertThrows(IOException.class, () -> s.copy(t0, 1, body("MQ3D", "{'base':1,'seq':1}", 10)));
		s.frame(t0 + 5_000, 100, FRAMES.get(0));
		// ten seconds on it is tried again, and fails again
		assertThrows(IOException.class, () -> s.frame(t0 + 10_000, 200, FRAMES.get(0)));
		Files.delete(dir);
		s.frame(t0 + 15_000, 300, FRAMES.get(0));
		assertFalse(Files.exists(dir));
		assertFalse(s.wantsCopy);
		// open at last: no frame without a copy, which is asked for
		s.frame(t0 + 20_000, 400, FRAMES.get(0));
		assertTrue(s.wantsCopy);
		s.line(t0 + 20_050, 401, "{\"t\":9,\"type\":\"doing\"}".getBytes(StandardCharsets.UTF_8));
		s.copy(t0 + 20_100, 402, body("MQ3D", "{'base':2,'seq':2}", 10));
		assertFalse(s.wantsCopy);
		s.frame(t0 + 20_200, 404, FRAMES.get(1));
		s.end();
		List<String> index = Files.readAllLines(dir.resolve("index.jsonl"));
		assertEquals(1, index.size());
		assertEquals("CKT", kinds(read(dir.resolve(json(index.get(0)).get("file").getAsString()))));
	}

	private static String kinds(List<Rec> recs) {
		StringBuilder b = new StringBuilder();
		for (Rec r : recs) b.append(r.kind());
		return b.toString();
	}

	@Test
	void aChangeWithoutItsCopyIsLeftOut(@TempDir Path tmp) throws IOException {
		RunRecorder.Segments s = new RunRecorder.Segments(tmp);
		assertFalse(s.change(1, 1, body("MQ3L", "{}", 3), 50));
		s.end();
		assertFalse(Files.exists(tmp.resolve("index.jsonl")));
		s.copy(10, 1, body("MQ3D", "{}", 3));
		assertTrue(s.change(20, 2, body("MQ3L", "{}", 3), RunRecorder.CHAIN_BYTES + 1));
		s.end();
		JsonObject i = json(Files.readAllLines(tmp.resolve("index.jsonl")).get(0));
		assertTrue(i.get("dim").isJsonNull());
		assertEquals(json("{'file':'" + i.get("file").getAsString() + "','offset':0}"), i.getAsJsonObject("copy"));
	}
}
