package net.milenco.buddy.client;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * A recording of every run, for a page to play back later in the panel's 3D view: the world, where everything was, and a
 * timeline of what the buddy was doing and considering. For practice servers: it is off unless the file
 * {@code recordings/on} exists in the exchange folder (looked for every five seconds), and while it is off nothing is
 * different. Nothing here ever deletes a recording.
 * <p>
 * While on, {@link World3d} keeps up as if a page watched (its change files as usual) and hands every copy, every change
 * file and a positions frame every second tick (ten a second) to this class, on its writer thread.
 * <p>
 * <b>Segments</b>: {@code recordings/seg-<epoch ms of its start>.bin}, one gzip stream each, closed and the next one
 * begun at the first frame after 60 s. It is flushed every second, so that a player can follow a run while it is
 * recorded; the last one of a game that stopped short may lack its gzip end: read what is there. A segment is records,
 * one after the other, big-endian:
 * {@code u8 kind, i64 ms, i32 tk, u32 length, length bytes of payload}: {@code ms} the wall clock and {@code tk} the
 * game's tick count ({@code tk} in {@code world3d-ent.json}) when the record was made. The kinds, ASCII:
 * <ul>
 * <li>{@code C} a copy: exactly what {@code world3d.bin} holds once gunzipped ("MQ3D", see {@link World3d}). It begins a
 * chain: its JSON's {@code base} and {@code seq}.</li>
 * <li>{@code L} a change: exactly what a {@code world3d-live/<seq>.bin} holds once gunzipped ("MQ3L"). Its JSON's
 * {@code base} is its chain's copy's, {@code prev} the {@code seq} of the record before it in the chain (the C or the
 * L before), {@code seq} its own; applied in order onto the copy they give the world. Every change of a chain is
 * recorded, so a gap in prev and seq is an error.</li>
 * <li>{@code K} a key frame: UTF-8 JSON, the positions file whole as a page gets it, and {@code breaks} (the blocks
 * broken since the frame before).</li>
 * <li>{@code D} a delta frame: UTF-8 JSON, what changed since the frame before it, K or D, in the same segment
 * ({@link FrameDelta} says how). K and then the Ds in order give every frame exactly.</li>
 * <li>{@code T} a line of the timeline: UTF-8 JSON {@code {t, type, ...}}, {@code t} when it happened: the lines of
 * events.jsonl of the types in {@link #EVENTS} as they are written there; those of {@link #STATES} ({@code inventory}:
 * {@code free_hotbar}, {@code free_backpack} and {@code things}, the slots as "0:iron_sword(246) 2:cobblestonex31
 * armor0:iron_boots(190)"; {@code status}: hp, food, the hand and more) when they differ from the last one of their type
 * (their {@code t} aside), and the last of each again right after every segment's first K (with its own, older
 * {@code t}), so that a segment read alone knows them; {@code {"type":"plan", "tasks", "by",
 * "by_tasks"}}, the order's plan as the panel shows it (rows of {@code t} task and {@code s} state), when it changes
 * (only its numbers: at most every 10 s; without {@code tasks}, no plan); and {@code {"type":"think", "text", "n"}},
 * the autopilot's log lines ({@link ThinkLines}: n 1 for a new line, a count with {@code first} for its repeats).</li>
 * </ul>
 * A segment begins with a K; the first segment, and one a copy opens, with the C and then the K. After every C a K comes
 * before the next D, and a K every 10 s. Records follow in the order they were made (the timeline's own {@code t} can
 * be a little earlier: a count is told after its last line).
 * <p>
 * <b>Copies</b> are forced at the start, when the dimension changes, when the buddy moved more than 128 blocks between
 * two frames (a teleport), after 8 MB of change files (gzipped) since the copy, and at least every ten minutes; a page
 * can ask for one too. Each is an ordinary copy of {@link World3d}: a new {@code base} (the copy's time, at least one
 * more than the last), the change files of the copy before wiped, a page that watches loads it. {@code seq} grows over
 * copies and changes alike.
 * <p>
 * <b>{@code recordings/index.jsonl}</b>: a line per closed segment, {@code {file, from_ms, to_ms, from_tk, to_tk, copy:
 * {file, offset}, dim, bytes}}: {@code copy} the C its chain starts from ({@code offset}: where its record begins in that
 * file's gunzipped stream; the C can be in an earlier segment), {@code dim} the dimension at its first frame, {@code
 * bytes} the file's size. To show a moment: that C, every L after it up to the moment (through the segments in
 * between), the segment's last K before the moment and the Ds after it. {@code tools/recording.py} reads it all.
 * <p>
 * A segment that cannot be opened or written (a full disk, a folder that is not writable) ends where it is, with
 * {@code "broken": true} in its index line when that can still be written, and its chain with it; at a frame at least
 * {@link #RETRY_MS} later a new segment is tried, and once it opens, a new copy begins it.
 * <p>
 * Nothing is written on the game's thread. When the writer falls behind (more than {@link #MAX_BACKLOG} jobs), frames
 * are dropped (never a C, an L or a T); the next one written is a D against the last one written. Once a minute the game
 * log gets {@code record: <KB> KB, <frames> frames, <µs> µs a tick spent building frames, <n> dropped}.
 */
public final class RunRecorder {
	private RunRecorder() {}

	static final long SEGMENT_MS = 60_000, KEY_MS = 10_000, COPY_MS = 600_000, CHECK_MS = 5_000, FLUSH_MS = 1_000, RETRY_MS = 10_000;
	/** Change files (gzipped) since the copy past which the next round makes a new copy: about one copy's size. */
	static final long CHAIN_BYTES = 8L << 20;
	/** Farther than this between two frames is a teleport: a new copy. */
	static final double JUMP = 128;
	static final int MAX_BACKLOG = 200;
	/** The events that go on the timeline as they are. */
	static final Set<String> EVENTS = Set.of("doing", "alto", "obtain", "damage", "death_cause");
	/** The events that say how things stand (the inventory, the vitals): on the timeline when they differ from the last
	 *  one of their type, and again after every segment's first K. */
	static final Set<String> STATES = Set.of("inventory", "status");
	private static final org.slf4j.Logger LOG = LogUtils.getLogger();
	private static final Gson GSON = new Gson();
	/** The index says null where it knows nothing (no frame, no copy). */
	private static final Gson INDEX = new com.google.gson.GsonBuilder().serializeNulls().create();
	private static final ThinkLines THINK = new ThinkLines();

	/** The switch: the writer looks for the file, the game's thread follows. */
	private static volatile boolean wanted, checking, on;
	/** The game's tick count, for the records made on other threads. */
	private static volatile long ticks;
	private static final AtomicInteger pending = new AtomicInteger(), dropped = new AtomicInteger();
	/** Set by the writer once the changes since the copy pass {@link #CHAIN_BYTES}. */
	private static volatile boolean chainFull;

	// the game thread's own
	private static long checkAt, copyAt, minuteAt, buildNanos, planTold;
	private static int minuteTicks;
	private static boolean starting, placed, hooked;
	private static ClientLevel copyLevel;
	private static double lastX, lastY, lastZ;
	private static String planText = "", planKey = "";

	// the writer's own
	private static volatile Segments segs;

	static boolean on() { return on; }

	private static Path dir() { return Paths.get(AfkConfig.get().exchangeDir).resolve("recordings"); }

	/** Once a tick, first of World3d's: the switch, the plan, the minute's line. Whether it records. */
	static boolean tick(Minecraft mc, long tk, long now) {
		ticks = tk;
		if (now >= checkAt && !checking) {
			checkAt = now + CHECK_MS;
			checking = true;
			World3d.writer(() -> {
				try { wanted = Files.exists(dir().resolve("on")); } catch (RuntimeException ignored) { } finally { checking = false; }
			});
		}
		if (wanted != on) {
			if (wanted) start(now);
			else stop(now);
		}
		if (!on) return false;
		minuteTicks++;
		if (now - minuteAt >= 60_000) report(now);
		if (tk % 20 == 0) {
			plan(now);
			THINK.flush(now, RunRecorder::thought);
		}
		return true;
	}

	private static void start(long now) {
		starting = true;
		placed = false;
		copyLevel = null;
		minuteAt = now;
		buildNanos = 0;
		minuteTicks = 0;
		planText = planKey = "";
		chainFull = false;
		dropped.set(0);
		on = true;
		listen(true);
		job(() -> { if (segs == null) segs = new Segments(dir()); segs.begin(); });
		// the inventory and vitals as they stand, which the game only tells again when they change
		synchronized (lastState) {
			toldState.clear();
			for (var e : List.copyOf(lastState.entrySet())) state(e.getKey(), e.getValue(), true);
		}
		if (!hooked) {
			hooked = true;
			// a game that quits ends its segment: whole and in the index
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				CountDownLatch done = new CountDownLatch(1);
				World3d.writer(() -> { try { if (segs != null) segs.end(); } catch (IOException ignored) { } finally { done.countDown(); } });
				try { done.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
			}, "buddy-recording-end"));
		}
		LOG.info("record: on");
	}

	private static void stop(long now) {
		on = false;
		// the last count of a line that repeats, then the end
		THINK.flush(now + ThinkLines.SAME_MS + 1, RunRecorder::thought);
		listen(false);
		job(() -> { if (segs != null) segs.end(); });
		LOG.info("record: off");
	}

	/** The autopilot's log lines, while it is there (its jar can be missing, or older than this). */
	private static void listen(boolean yes) {
		if (!Alto.present()) return;
		try {
			adris.altoclef.Debug.listen(yes ? RunRecorder::think : null);
		} catch (Throwable ignored) { }
	}

	private static void report(long now) {
		long kb = segs == null ? 0 : segs.bytes.getAndSet(0) / 1024;
		int frames = segs == null ? 0 : segs.frames.getAndSet(0);
		long us = minuteTicks == 0 ? 0 : buildNanos / 1000 / minuteTicks;
		LOG.info("record: {} KB, {} frames, {} µs a tick spent building frames, {} dropped", kb, frames, us, dropped.getAndSet(0));
		minuteAt = now;
		buildNanos = 0;
		minuteTicks = 0;
	}

	/** Whether this round of World3d is to make a new copy first (the game's thread, at each frame). */
	static boolean copyDue(ClientLevel level, double x, double y, double z, long now) {
		boolean jump = placed && (x - lastX) * (x - lastX) + (y - lastY) * (y - lastY) + (z - lastZ) * (z - lastZ) > JUMP * JUMP;
		lastX = x; lastY = y; lastZ = z;
		placed = true;
		Segments sg = segs;
		boolean retry = sg != null && sg.wantsCopy && now - copyAt >= RETRY_MS;
		return starting || jump || level != copyLevel || now - copyAt >= COPY_MS || chainFull && now - copyAt > 5_000 || retry;
	}

	/** World3d made a copy (the game's thread): the reasons for one start again. */
	static void copied(ClientLevel level, long now) {
		if (!on) return;
		starting = false;
		copyLevel = level;
		copyAt = now;
		chainFull = false;
	}

	// ---------- what comes in ----------

	/** A frame from World3d, the game's thread; nanos is what building it took there. */
	static void frame(Map<String, Object> ents, long ms, long tk, long nanos) {
		buildNanos += nanos;
		if (pending.get() + World3d.backlog() > MAX_BACKLOG) { dropped.incrementAndGet(); return; }
		job(() -> { if (segs != null) segs.frame(ms, tk, GSON.toJsonTree(ents).getAsJsonObject()); });
	}

	/** A copy, on World3d's writer, after its file: the body writes what world3d.bin holds ungzipped. */
	static void copy(long ms, long tk, World3d.Body body) {
		try {
			if (on && segs != null) segs.copy(ms, tk, body);
		} catch (IOException | RuntimeException e) { failed(e); }
	}

	/** A change file, on World3d's writer, after the file: its body, and the file's size. */
	static void change(long ms, long tk, World3d.Body body, long gz) {
		try {
			if (on && segs != null && segs.change(ms, tk, body, gz)) chainFull = true;
		} catch (IOException | RuntimeException e) { failed(e); }
	}

	/** A line of events.jsonl, as Exchange writes it (any thread). */
	static void event(String type, JsonObject o) {
		if (STATES.contains(type)) { state(type, o, false); return; }
		if (!on || !EVENTS.contains(type)) return;
		line(GSON.toJson(o));
	}

	/** The last line of each of {@link #STATES}, also while off (a recording begins with them), and the last one told
	 *  of each without its time. */
	private static final Map<String, JsonObject> lastState = new java.util.HashMap<>();
	private static final Map<String, String> toldState = new java.util.HashMap<>();

	/** A line of one of {@link #STATES}: on the timeline when it differs from the last one told, or always at the start. */
	private static void state(String type, JsonObject o, boolean always) {
		synchronized (lastState) {
			lastState.put(type, o);
			if (!on) return;
			JsonObject k = o.deepCopy();
			k.remove("t");
			String key = GSON.toJson(k);
			if (!always && key.equals(toldState.get(type))) return;
			toldState.put(type, key);
		}
		line(GSON.toJson(o), type);
	}

	/** A line of the autopilot's log (any thread). */
	static void think(String raw) {
		if (on) THINK.offer(raw, System.currentTimeMillis(), RunRecorder::thought);
	}

	private static void thought(long ms, String text, int n, long first) {
		JsonObject o = new JsonObject();
		o.addProperty("t", ms);
		o.addProperty("type", "think");
		o.addProperty("text", text);
		o.addProperty("n", n);
		if (first != 0) o.addProperty("first", first);
		line(GSON.toJson(o));
	}

	/** The order's plan, once a second: a line when it changed, when only its numbers did at most every 10 s. */
	private static void plan(long now) {
		if (!Alto.present()) return;
		Map<String, Object> pl;
		try { pl = Alto.plan(); } catch (Throwable t) { return; }
		String text = pl == null ? "" : GSON.toJson(pl);
		if (text.equals(planText)) return;
		String key = ThinkLines.key(text);
		if (key.equals(planKey) && now - planTold < ThinkLines.SAME_MS) return;
		planText = text;
		planKey = key;
		planTold = now;
		Map<String, Object> o = new LinkedHashMap<>();
		o.put("t", now);
		o.put("type", "plan");
		if (pl != null) o.putAll(pl);
		line(GSON.toJson(o));
	}

	private static void line(String json) { line(json, null); }

	private static void line(String json, String state) {
		byte[] b = json.getBytes(StandardCharsets.UTF_8);
		long ms = System.currentTimeMillis(), tk = ticks;
		job(() -> { if (segs != null) segs.line(ms, tk, b, state); });
	}

	/** The writer's: when the last failure was told, and how many since. */
	private static long failedAt;
	private static int failures;

	private static void job(World3d.Job r) {
		pending.incrementAndGet();
		World3d.writer(() -> {
			try {
				r.run();
			} catch (Exception e) {
				failed(e);
			} finally {
				pending.decrementAndGet();
			}
		});
	}

	/** In the game log, once a minute at most (the writer's thread): a full disk fails every frame. */
	private static void failed(Exception e) {
		failures++;
		long now = System.currentTimeMillis();
		if (now - failedAt < 60_000) return;
		LOG.warn("record: {} ({} failures)", String.valueOf(e), failures);
		failedAt = now;
		failures = 0;
	}

	// ---------- the writer thread ----------

	/**
	 * The segment files of one recordings folder and their index. Of one thread only (World3d's writer); without the
	 * game, so a test can write a recording.
	 */
	static final class Segments {
		private static final int HEADER = 1 + 8 + 4 + 4;
		private final Path dir;
		private DataOutputStream out;
		private Counting file;
		private String name, dim, copyFile, startFile;
		private long opened, offset, lastName, lastFlush, keyAt, chain, seen;
		private long copyOffset = -1, startOffset = -1, failedAt = Long.MIN_VALUE / 2;
		private long fromMs, toMs, fromTk, toTk;
		private JsonObject prev;
		private boolean keyNext = true, ended;
		/** Timeline lines made while no frame could go before them: written after the next K. */
		private final List<Object[]> held = new ArrayList<>();
		/** The last line written of each of {@link #STATES}, repeated after every segment's first K. */
		private final Map<String, Object[]> states = new LinkedHashMap<>();
		final AtomicLong bytes = new AtomicLong();
		final AtomicInteger frames = new AtomicInteger();
		/** A segment is open again after a failure, but the chain is gone: the next frame on the game's thread makes a copy. */
		volatile boolean wantsCopy;

		Segments(Path dir) { this.dir = dir; }

		/** A recording begins (again). */
		void begin() {
			ended = false;
			states.clear();
			bytes.set(0);
			frames.set(0);
		}

		/** A copy: a new chain. A segment past its minute ends first, so that the next one begins with this. */
		void copy(long ms, long tk, World3d.Body body) throws IOException {
			if (out != null && ms - opened >= SEGMENT_MS) close();
			// the chain before ends here, whatever becomes of this copy
			copyFile = null;
			copyOffset = -1;
			if (out == null) open(ms);
			long at = offset;
			record('C', ms, tk, body);
			copyFile = name;
			copyOffset = at;
			chain = 0;
			keyNext = true;
			wantsCopy = false;
		}

		/** A change of the chain; whether the chain's changes now pass {@link #CHAIN_BYTES}. Without its copy, nothing. */
		boolean change(long ms, long tk, World3d.Body body, long gz) throws IOException {
			if (out == null || copyFile == null) return false;
			record('L', ms, tk, body);
			chain += gz;
			return chain > CHAIN_BYTES;
		}

		/**
		 * A frame: a K at a segment's start, after a copy and every {@link #KEY_MS}, else a D. Nothing before a copy. With no
		 * segment open (one failed), a new one is tried at most every {@link #RETRY_MS}, and a copy asked for.
		 */
		void frame(long ms, long tk, JsonObject cur) throws IOException {
			if (out != null && ms - opened >= SEGMENT_MS) close();
			if (out == null) {
				if (ms - failedAt < RETRY_MS) return;
				open(ms);
			}
			if (copyFile == null) { wantsCopy = true; return; }
			if (keyNext || prev == null || ms - keyAt >= KEY_MS) {
				boolean first = startFile == null;
				if (first) { startFile = copyFile; startOffset = copyOffset; }
				if (dim == null && cur.has("dim")) dim = cur.get("dim").getAsString();
				record('K', ms, tk, GSON.toJson(cur).getBytes(StandardCharsets.UTF_8));
				keyAt = ms;
				keyNext = false;
				// a segment read alone knows the inventory and the vitals
				if (first) for (Object[] st : List.copyOf(states.values())) record('T', ms, tk, (byte[]) st[2]);
			} else {
				record('D', ms, tk, GSON.toJson(FrameDelta.encode(prev, cur)).getBytes(StandardCharsets.UTF_8));
			}
			prev = cur;
			frames.incrementAndGet();
			for (Object[] h : List.copyOf(held)) written(h);
			held.clear();
		}

		/** A line of the timeline; held while a K is due (at most a few thousand, before the first frame). */
		void line(long ms, long tk, byte[] json) throws IOException { line(ms, tk, json, null); }

		/** The same, of one of {@link #STATES} when {@code state} names it. */
		void line(long ms, long tk, byte[] json, String state) throws IOException {
			if (ended) return;
			Object[] h = { ms, tk, json, state };
			if (out == null || keyNext) {
				if (held.size() < 5000) held.add(h);
				return;
			}
			written(h);
		}

		private void written(Object[] h) throws IOException {
			record('T', (long) h[0], (long) h[1], (byte[]) h[2]);
			if (h[3] != null) states.put((String) h[3], h);
		}

		/** The recording ends: the segment closes and the chain with it (the next one begins with a copy). */
		void end() throws IOException {
			ended = true;
			held.clear();
			try {
				close();
			} finally {
				copyFile = null;
				copyOffset = -1;
			}
		}

		private void open(long ms) throws IOException {
			OutputStream f = null;
			try {
				Files.createDirectories(dir);
				long n = Math.max(ms, lastName + 1);
				lastName = n;
				name = "seg-" + n + ".bin";
				f = Files.newOutputStream(dir.resolve(name), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
				file = new Counting(f);
				// syncFlush: what was flushed can be read while the segment is still open, or after the game stopped short
				out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(file, 1 << 16, true), 1 << 16));
			} catch (IOException | RuntimeException e) {
				// no segment: the next try at a frame after RETRY_MS, and the chain begins again with a copy then
				if (f != null) try { f.close(); } catch (IOException ignored) { }
				out = null;
				failedAt = ms;
				copyFile = null;
				copyOffset = -1;
				throw e;
			}
			opened = lastFlush = ms;
			offset = seen = 0;
			fromMs = fromTk = Long.MAX_VALUE;
			toMs = toTk = Long.MIN_VALUE;
			startFile = null;
			startOffset = -1;
			dim = null;
			prev = null;
			keyNext = true;
		}

		private void close() throws IOException {
			if (out == null) return;
			try {
				out.close();
			} finally {
				out = null;
				prev = null;
				tally();
			}
			index(false);
		}

		/** A write failed: the segment ends as it is (its index line says so) and the chain with it; a new segment and a
		 *  copy follow at a frame after {@link #RETRY_MS}. */
		private void broke(long ms) {
			failedAt = ms;
			copyFile = null;
			copyOffset = -1;
			if (out == null) return;
			try { out.close(); } catch (IOException | RuntimeException ignored) { }
			out = null;
			prev = null;
			tally();
			try { index(true); } catch (IOException | RuntimeException ignored) { }
		}

		private void index(boolean broken) throws IOException {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("file", name);
			m.put("from_ms", fromMs == Long.MAX_VALUE ? opened : fromMs);
			m.put("to_ms", toMs == Long.MIN_VALUE ? opened : toMs);
			m.put("from_tk", fromTk == Long.MAX_VALUE ? 0 : fromTk);
			m.put("to_tk", toTk == Long.MIN_VALUE ? 0 : toTk);
			String cf = startFile != null ? startFile : copyFile;
			long co = startFile != null ? startOffset : copyOffset;
			m.put("copy", cf == null ? null : Map.of("file", cf, "offset", co));
			m.put("dim", dim);
			m.put("bytes", file.count);
			if (broken) m.put("broken", true);
			Files.writeString(dir.resolve("index.jsonl"), INDEX.toJson(m) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}

		private void header(char kind, long ms, long tk, long length) throws IOException {
			out.writeByte(kind);
			out.writeLong(ms);
			out.writeInt((int) tk);
			out.writeInt((int) length);
			offset += HEADER + length;
			fromMs = Math.min(fromMs, ms);
			toMs = Math.max(toMs, ms);
			fromTk = Math.min(fromTk, tk);
			toTk = Math.max(toTk, tk);
		}

		private void record(char kind, long ms, long tk, byte[] payload) throws IOException {
			try {
				header(kind, ms, tk, payload.length);
				out.write(payload);
				flushed(ms);
			} catch (IOException | RuntimeException e) {
				broke(ms);
				throw e;
			}
		}

		/** A record whose payload the body writes: once to know its length, once into the segment. */
		private void record(char kind, long ms, long tk, World3d.Body body) throws IOException {
			try {
				Counting size = new Counting(OutputStream.nullOutputStream());
				try (DataOutputStream d = new DataOutputStream(new BufferedOutputStream(size, 1 << 16))) { body.write(d); }
				header(kind, ms, tk, size.count);
				// into the segment's stream, which stays open and unflushed
				DataOutputStream seg = out;
				Counting into = new Counting(new OutputStream() {
					@Override public void write(int b) throws IOException { seg.write(b); }
					@Override public void write(byte[] b, int off, int len) throws IOException { seg.write(b, off, len); }
				});
				try (DataOutputStream d = new DataOutputStream(new BufferedOutputStream(into, 1 << 16))) { body.write(d); }
				if (into.count != size.count) throw new IOException(kind + " record wrote " + into.count + " bytes of " + size.count);
				flushed(ms);
			} catch (IOException | RuntimeException e) {
				// a record cut short spoils the rest of the segment, and a change left out the chain
				broke(ms);
				throw e;
			}
		}

		/** After a record: flushed every {@link #FLUSH_MS}, and what reached the file counted for the minute's line. */
		private void flushed(long ms) throws IOException {
			if (ms - lastFlush >= FLUSH_MS) {
				lastFlush = ms;
				out.flush();
			}
			tally();
		}

		private void tally() {
			bytes.addAndGet(file.count - seen);
			seen = file.count;
		}
	}

	/** Counts what goes through. */
	private static final class Counting extends FilterOutputStream {
		long count;

		Counting(OutputStream o) { super(o); }

		@Override public void write(int b) throws IOException { out.write(b); count++; }
		@Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
	}
}
