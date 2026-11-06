package adris.altoclef.util.helpers;

// when a tool counts as used up. one line for the whole bot, so the kit planner (which stops counting a worn pick as owned and
// makes the next one while a table is still around) and the weapon pick (which swings something healthy first) agree on what
// "worn" means. it used to be a 0.85 in each of them. pure
public final class ToolWear {
    // past this much of its durability a tool is as good as gone. stone gets 131 uses, so this is about a hundred blocks of
    // mining, and then we would rather make a fresh one at a table than find out in a cave
    public static final double WORN_FRACTION = 0.85;

    private ToolWear() {
    }

    // maxDamage 0 is an item that does not wear at all, never worn out
    public static boolean wornOut(int damage, int maxDamage) {
        return maxDamage > 0 && damage >= WORN_FRACTION * maxDamage;
    }
}
