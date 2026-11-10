package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Every static of the buddy's obtain code (Obtain*, Alto, Failures) that can hold state from one order to the next is in
 * RunMemories, reset by Obtain.start itself, or allowed below with a reason. A new one that is neither fails here: put it in
 * RunMemories (measured, and cleared on a fresh run), reset it in Obtain.start, or allow it with the reason it is no memory of
 * the world. Work is not scanned (the buddy's other jobs share it); its bad stands are in RunMemories.
 */
class RunMemoriesGuardTest {
	private static final String C = "net.milenco.buddy.client.";

	/** "Class#field,field" and the reason. */
	private static final Map<String, String> ALLOWED = Map.ofEntries(
		Map.entry("Obtain#item,count,by,since,stepSince,step,exec,startHad,steps,retries,resting,rest,watching",
			"the order under way itself, set by start and stop"),
		Map.entry("Obtain#blocked,tooDeep,fails,placed,kitNoted,kitUnmet,kitRetryAt,furnaceFull,badStations,sopranoEnds,deep,foodAfterHome,"
			+ "foodTriedAt,homeTriedAt,goldTriedAt,boatTriedAt,boatLook,holeOffUntil,holeStarted,upFailedAt,dryFailedAt,armourLook,"
			+ "wasMidFrame,armourFor,fleeFrom,standUntil,beatenAt,junkHeld,thrownId,junkAsk",
			"reset by Obtain.start for every order"),
		Map.entry("Obtain#listed", "the panel's list of items, written once per start of the game"),
		Map.entry("Obtain#last", "the last order's end, for the panel"),
		Map.entry("Obtain#lastPlayer,lastDim", "the player and dimension of the last tick: what the buddy is now, not what it knew"),
		Map.entry("Obtain#nextStatus,junkAskTick,junkWaitFrom,stillTicks,thrownN,thrownPrev,thrownTick,fleeHp",
			"a clock or count of a few seconds within one step"),
		Map.entry("Obtain#wantedLater", "what the order under way wants later, set by its plan"),
		Map.entry("ObtainSafe#DANGER", "the danger marks, cleared by Obtain.start (ObtainSafe.forget) for every order"),
		Map.entry("ObtainSafe#nextWatch,edging,braced", "a clock or a flag of the tick under way"),
		Map.entry("Alto#KIT_FAILED", "reset by Obtain.start (Alto.kitReset) for every order"),
		Map.entry("Alto#orderDanger", "set by Obtain.start for every order"),
		Map.entry("ObtainRoute#on,overworld,through,phase,furnaceHeld,TRIED", "reset by Obtain.start (ObtainRoute.reset) for every order"),
		Map.entry("Alto#present,up,holder,told,toldWait,toldSince,delegated,seenUp,gone,gateOpen,shuts,lastDoing,doingLook,scaledFor,"
			+ "memoriesIn",
			"whether the autopilot is there and holds the buddy now: the link to it, not knowledge of the world"),
		Map.entry("Alto#savedBoats,savedFreeLook,savedBucketFall,savedAvoid,savedBlast,savedInv,savedInvStill,savedImportant",
			"the player's own settings, kept while the autopilot holds the buddy and given back"),
		Map.entry("Alto#wishing,wishedAt,wishedBag,saidStart,saidEnd", "the take-along wish of the order under way"),
		Map.entry("Alto#hungrySince", "since when the buddy is hungry now: the body, not the world"),
		Map.entry("Obtain#flee", "the flight under way, a few seconds; it ends with no foe in sight"),
		Map.entry("ObtainKit#AS_IT_COMES,IRON_UP,STONE_UP", "a constant table (the scan sees an array store beside a read of it)"),
		Map.entry("ObtainKit#sopranoBrews", "whether the autopilot brews, set when it comes up"),
		Map.entry("ObtainPlan#SPECIALS", "the planner's special ways, a table built once"));

	private static Set<String> allowed() {
		Set<String> out = new HashSet<>();
		for (String k : ALLOWED.keySet()) {
			int hash = k.indexOf('#');
			for (String f : k.substring(hash + 1).split(",")) out.add(C + k.substring(0, hash + 1) + f);
		}
		return out;
	}

	private static Set<String> registered() {
		Set<String> out = new HashSet<>();
		for (RunMemories.Memory m : RunMemories.ALL) out.addAll(List.of(m.fields()));
		return out;
	}

	private static Set<String> found() throws Exception {
		Path root = Path.of(Obtain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		return StateStatics.scan(root, "net.milenco.buddy.client",
			n -> n.startsWith("Obtain") || n.startsWith("Alto.") || n.startsWith("Alto$") || n.startsWith("Failures"),
			RunMemoriesGuardTest.class.getClassLoader());
	}

	@Test
	void everyStateStaticIsRegisteredOrAllowed() throws Exception {
		Set<String> reg = registered(), ok = allowed();
		List<String> missing = new ArrayList<>();
		for (String f : found()) if (!reg.contains(f) && !ok.contains(f)) missing.add(f);
		if (!missing.isEmpty()) fail(missing.size() + " static(s) can hold state between orders and are neither in RunMemories "
			+ "nor allowed with a reason:\n" + String.join("\n", missing));
	}

	/** A registration or an allowed name is a static that is there: a rename leaves no stale name behind. */
	@Test
	void everyNamedFieldExists() {
		List<String> names = new ArrayList<>(registered());
		names.addAll(allowed());
		List<String> gone = new ArrayList<>();
		for (String n : names) {
			try {
				Class<?> c = Class.forName(n.substring(0, n.indexOf('#')), false, getClass().getClassLoader());
				if (!Modifier.isStatic(c.getDeclaredField(n.substring(n.indexOf('#') + 1)).getModifiers())) gone.add(n + " (not static)");
			} catch (ClassNotFoundException | NoSuchFieldException e) {
				gone.add(n);
			}
		}
		if (!gone.isEmpty()) fail("named but not found:\n" + String.join("\n", gone));
	}

	@Test
	void everyMemoryHasAName() {
		Set<String> names = new HashSet<>();
		for (RunMemories.Memory m : RunMemories.ALL) if (!names.add(m.name())) fail("twice: " + m.name());
	}
}
