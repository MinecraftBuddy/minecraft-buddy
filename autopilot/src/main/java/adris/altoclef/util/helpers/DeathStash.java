package adris.altoclef.util.helpers;

import baritone.api.utils.Dimension;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

// the last death the game told us about, in a one slot mailbox. it is written from the death packet and not from any task:
// a chain that holds the wheel through a fight, the death and the death screen never ticks the user task, so the user task
// cannot be the one to notice. whoever wants to go back for the pile takes it (once) when the new player shows up.
// alto side on purpose, nothing in here knows what a gamer is
public final class DeathStash {

    // flags and not a cause enum: what the cause means to the pile is the reader's business
    public record Death(Dimension dimension, int x, int y, int z, long gameTime, boolean inLava, boolean outOfWorld, String cause) {
        public String where() {
            return x + " " + y + " " + z;
        }
    }

    // lava damage that landed this recently still makes it a lava death: the killing blow is often the fire after the bath
    public static final long LAVA_WINDOW_TICKS = 60;

    private static Death pending;
    // game time of the last lava hit, and the damage source it came on (the client makes a fresh one per hit)
    private static long lastLavaHit = Long.MIN_VALUE;
    private static DamageSource lastSeen;

    private DeathStash() {
    }

    // two deaths before anyone looked: the later one wins, the first pile was a lost cause by then anyway
    public static synchronized void put(Death death) {
        pending = death;
    }

    // hands it over and forgets it, so one death is one respawn and not two
    public static synchronized Death take() {
        Death death = pending;
        pending = null;
        return death;
    }

    public static synchronized void clear() {
        pending = null;
    }

    // every tick: remember when lava last hurt us. the snapshot only sees the death tick, and a burn death after a lava bath
    // reads as plain fire there
    public static synchronized void watch(LocalPlayer player) {
        DamageSource source = player.getLastDamageSource();
        if (source != null && source != lastSeen && source.is(DamageTypes.LAVA)) {
            lastLavaHit = player.level().getGameTime();
        }
        lastSeen = source;
    }

    // a hit inside the window, and not from the future (another world has its own clock). fire alone is not lava
    public static boolean lavaRecent(long lastHit, long now) {
        return lastHit != Long.MIN_VALUE && now >= lastHit && now - lastHit <= LAVA_WINDOW_TICKS;
    }

    // everything about a player that is dead or dying, read off the player itself. it keeps its level and its health after the
    // respawn swapped ours (the respawn only reads the old one), so this works on the old instance too. the damage source is
    // only good for 40 ticks (getLastDamageSource forgets it), which is why the packet hook reads it right away
    public static Death snapshot(LocalPlayer player) {
        Level level = player.level();
        BlockPos at = player.blockPosition();
        DamageSource source = player.getLastDamageSource();
        // the fluid at our feet and the damage source both count: the killing blow can be fire from a lava bath we already
        // climbed out of
        boolean lava = player.isInLava() || lavaRecent(lastLavaHit, level.getGameTime()) || (source != null && source.is(DamageTypes.LAVA))
                || (level.isLoaded(at) && level.getFluidState(at).is(FluidTags.LAVA));
        boolean out = at.getY() < level.getMinY() || (source != null && source.is(DamageTypes.FELL_OUT_OF_WORLD));
        return new Death(WorldHelper.dimensionOf(level), at.getX(), at.getY(), at.getZ(), level.getGameTime(), lava, out,
                describe(lava, out, source));
    }

    private static String describe(boolean lava, boolean out, DamageSource source) {
        if (lava) {
            return "lava";
        }
        if (out) {
            return "the void";
        }
        if (source == null) {
            return "no damage source";
        }
        Entity by = source.getEntity();
        return by == null ? source.getMsgId() : source.getMsgId() + " by " + BuiltInRegistries.ENTITY_TYPE.getKey(by.getType()).getPath();
    }
}
