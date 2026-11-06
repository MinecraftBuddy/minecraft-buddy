package adris.altoclef.takealong;

import adris.altoclef.tasks.speedrun.gamer.config.TakeAlongConfig;

import net.minecraft.world.entity.item.ItemEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

// what a caller that runs Soprano's user tasks (the buddy's obtain mode) says is worth taking along on the way, and what
// TakeAlongChain is up to. the caller says what is worth what: the wanted items per tier and the seconds one raw item saves;
// the chain turns the items into raw leaves with counts through the same bill GatherBillTask works out (RecipeBill), so what
// is taken along is what Soprano would otherwise walk for. nothing here touches the game but the caller's word on a drop
public final class TakeAlong {
    // now = the items the gate ahead and the current step lack, later = what the rest of the order needs in this dimension,
    // both as bag counts to hold ("hold this many in total", an item in both counts the larger); values = seconds one raw item
    // saves, by raw item ("raw_iron", "coal", "flint", "log", a namespace is fine). id goes up when any of it changes
    public record Order(long id, Map<String, Integer> now, Map<String, Integer> later, Map<String, Double> values) {
        // the two tiers as one bill's targets
        public Map<String, Integer> all() {
            Map<String, Integer> out = new LinkedHashMap<>(now);
            later.forEach((item, count) -> out.merge(item, count, Math::max));
            return out;
        }
    }

    // one detour, for the caller's log. items = the raw items ("raw_iron+coal"), blocks = the blocks counted, value and cost
    // in seconds as decided, seconds = game seconds since it began, got = what came of each raw item, state = "running" or how
    // it ended (done, capped, timeout, strayed, blocked, preempted, stopped), why = the words for that
    // drops = the detour was for drops on the floor, blocks then counts the drops; for animals (items "food") blocks counts
    // the animals and got is in food points
    public record Status(long id, String items, int blocks, double value, double cost, double seconds, Map<String, Integer> got,
                         String state, String why, boolean drops) {
        public boolean running() {
            return "running".equals(state);
        }
    }

    // food in points as the food task counts them (raw meat at its cooked worth): needNow = the bag count under which a food
    // run comes, needAll = what a food run gathers to, value = the seconds one point saves (what a food run costs a point)
    public record Food(int needNow, int needAll, double value) {
    }

    private static volatile Order _order;
    private static volatile Food _food;
    private static volatile String _hold;
    private static volatile Predicate<ItemEntity> _thrown = e -> false;
    private static volatile TakeAlongConfig _config = new TakeAlongConfig();
    private static volatile Status _current;
    private static volatile Status _last;
    private static long _orders;
    private static long _detours;

    private TakeAlong() {
    }

    // what is worth taking along from now on. the same order again keeps its id, so the chain only works the bill out again
    // when something changed (it also does every few seconds, the bag changes under it)
    public static synchronized void wishes(Map<String, Integer> now, Map<String, Integer> later, Map<String, Double> values) {
        Map<String, Integer> n = clean(now);
        Map<String, Integer> l = clean(later);
        Map<String, Double> v = TakeAlongRules.values(values);
        Order old = _order;
        if (old != null && old.now().equals(n) && old.later().equals(l) && old.values().equals(v)) {
            return;
        }
        _order = new Order(++_orders, n, l, v);
    }

    // nothing is worth taking along: the chain ends a detour under way and starts none
    public static synchronized void off() {
        _order = null;
        _food = null;
    }

    // what food is worth taking along on the way, null for none (animals are then left alone)
    public static void food(Food food) {
        _food = food == null || food.needAll() <= 0 || food.value() <= 0 ? null : food;
    }

    public static Food food() {
        return _food;
    }

    public static Order order() {
        return _order;
    }

    // the caller's own reason to leave detours alone right now (a portal under way, the boat, the trip home, a food run, foes
    // it sees), null to allow them again. a detour under way ends on it
    public static void hold(String why) {
        _hold = why;
    }

    public static String hold() {
        return _hold;
    }

    // the caller's own throws (junk it threw away): a drop it says yes to is never taken along, or it goes round, thrown
    // and picked up again
    public static void thrown(Predicate<ItemEntity> thrown) {
        _thrown = thrown == null ? e -> false : thrown;
    }

    public static boolean thrown(ItemEntity drop) {
        try {
            return _thrown.test(drop);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static TakeAlongConfig config() {
        return _config;
    }

    public static void config(TakeAlongConfig config) {
        _config = config == null ? new TakeAlongConfig() : config;
    }

    // the detour under way, null when there is none
    public static Status current() {
        return _current;
    }

    // the last detour that ended, null before the first
    public static Status last() {
        return _last;
    }

    // ---- the chain's side

    public static synchronized long nextId() {
        return ++_detours;
    }

    public static void running(Status s) {
        _current = s;
    }

    public static void ended(Status s) {
        _current = null;
        _last = s;
    }

    // the item names as catalogue names: no namespace, no empty counts
    private static Map<String, Integer> clean(Map<String, Integer> items) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (items != null) {
            items.forEach((name, count) -> {
                if (name == null || count == null || count <= 0) {
                    return;
                }
                String n = name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
                out.merge(n, count, Math::max);
            });
        }
        return out;
    }
}
