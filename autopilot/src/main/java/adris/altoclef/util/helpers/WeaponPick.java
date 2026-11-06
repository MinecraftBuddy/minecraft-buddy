package adris.altoclef.util.helpers;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

// which melee weapon to hold. a sword over any axe: it swings faster, it sweeps a crowd, and the charge wait before each
// swing costs it less. an axe hits harder per swing but the zombies in a cave come three at a time. the axe is left for
// the logs (and for when there is no sword at all, then it still beats a fist)
public final class WeaponPick {

    private WeaponPick() {
    }

    // what we know about a stack without carrying the stack around, so the ranking can be tested without an inventory
    public record Candidate(Item item, int damage, int maxDamage) {
        public static Candidate of(ItemStack stack) {
            return new Candidate(stack.getItem(), stack.getDamageValue(), stack.getMaxDamage());
        }
    }

    public static boolean isWeapon(Item item) {
        return ItemHelper.isSword(item) || ItemHelper.isAxe(item);
    }

    // the weapon to hold, or null if there is no sword or axe in the list. a healthy weapon always beats a worn one, then
    // any sword beats any axe, then the harder hit wins, then the faster swing, and the first one listed wins
    // what is left (hand first, so two equal weapons never get swapped back and forth)
    public static Item best(Iterable<Candidate> candidates) {
        Candidate best = null;
        for (Candidate c : candidates) {
            if (c == null || !isWeapon(c.item())) continue;
            if (best == null || beats(c, best)) {
                best = c;
            }
        }
        return best == null ? null : best.item();
    }

    // same pick, but an axe wins over any sword when one is carried: a calm neutral mob is near the target and an axe swing
    // never sweeps. null if there is no axe, the caller falls back to the plain pick
    public static Item bestAxe(Iterable<Candidate> candidates) {
        Candidate best = null;
        for (Candidate c : candidates) {
            if (c == null || !ItemHelper.isAxe(c.item()) || ToolWear.wornOut(c.damage(), c.maxDamage())) continue;
            if (best == null || beats(c, best)) {
                best = c;
            }
        }
        return best == null ? null : best.item();
    }

    // pickaxes, shovels and whatever else hits harder than a fist, the same ranking as the weapons (the harder hit, hand first
    // so ties never swap). a worn one is not on the list at all: every swing costs a tool two uses, and the last of the only
    // pickaxe is worth more than a fight we can run from
    public static Item bestTool(Iterable<Candidate> candidates) {
        Candidate best = null;
        for (Candidate c : candidates) {
            if (c == null || isWeapon(c.item()) || !ItemHelper.hitsHarderThanFists(c.item())) continue;
            if (ToolWear.wornOut(c.damage(), c.maxDamage())) continue;
            if (best == null || beats(c, best)) {
                best = c;
            }
        }
        return best == null ? null : best.item();
    }

    // a swap costs the whole cooldown (vanilla resets the ticker for any change of item type), so once a weapon is in hand
    // it stays there until the swing lands. a pick that flips with the crowd count would otherwise keep the cooldown
    // from ever filling. this much charge or less counts as just swung: the swap is nearly free then, the new weapon starts
    // its own cooldown from about where the old one is
    public static final double JUST_SWUNG_TICKS = 2;

    // what to have in hand this tick, given what we hold, what the pick says, and how many ticks of cooldown are charged
    // (scale times the delay of what we hold). the pick is null when there is nothing to pick
    public static Item hold(Item inHand, Item pick, double chargedTicks) {
        return hold(inHand, pick, chargedTicks, false);
    }

    // swungSinceSwap: a swing went out since the last time the hand changed. the tick right after a swing is a narrow window
    // and a kill task that is mid hop misses it, so any swing since the last swap opens the door once, and a pick that flips
    // back and forth then moves the hand once per swing and no more
    public static Item hold(Item inHand, Item pick, double chargedTicks, boolean swungSinceSwap) {
        if (pick == null || pick == inHand) {
            return inHand;
        }
        // a bucket, a pick or food has no cooldown worth protecting
        if (!isWeapon(inHand) || swungSinceSwap || chargedTicks <= JUST_SWUNG_TICKS) {
            return pick;
        }
        return inHand;
    }

    // moves the hand to hold. a failed axe falls back to the plain pick. true only when an equip actually went through, so a
    // failed one lets the caller swing with what is in hand instead of waiting on a swap that never works
    public static boolean swapHand(java.util.function.Predicate<Item> equip, Item hold, Item inHand, boolean wasAxe,
                                   java.util.function.Supplier<Item> plain) {
        if (equip.test(hold)) return true;
        if (!wasAxe) return false;
        Item fallback = plain.get();
        return fallback != null && fallback != inHand && equip.test(fallback);
    }

    private static boolean beats(Candidate a, Candidate b) {
        boolean aWorn = ToolWear.wornOut(a.damage(), a.maxDamage());
        boolean bWorn = ToolWear.wornOut(b.damage(), b.maxDamage());
        if (aWorn != bWorn) {
            return bWorn;
        }
        boolean aSword = ItemHelper.isSword(a.item());
        if (aSword != ItemHelper.isSword(b.item())) {
            return aSword;
        }
        float aDamage = ItemHelper.getAttackDamage(a.item());
        float bDamage = ItemHelper.getAttackDamage(b.item());
        if (aDamage != bDamage) {
            return aDamage > bDamage;
        }
        return ItemHelper.getAttackSpeed(a.item()) > ItemHelper.getAttackSpeed(b.item());
    }
}
