package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.speedrun.gamer.GolemRules.Inputs;
import adris.altoclef.tasks.speedrun.gamer.GolemRules.Verdict;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.tasks.GolemFightTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.utils.Dimension;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// 3 to 5 iron ingots stand around in every village, they just hit hard. while iron is the thing we are short on and a
// calm golem is close, GolemFightTask kills one from a pillar. in memory only: a relog forgets which golems we tried,
// which is fine, the attempts cap is per instance and a relog is a fresh run of the phase anyway
public final class GolemHunt {
    private static final int CHECK_EVERY_TICKS = 10;
    private static final double NEAR_BLOCKS = 6;

    private final Set<Integer> tried = new HashSet<>();
    // golems we backed out on for a reason that was about the day (no blocks, monsters), id -> game tick they are fair game
    // again. a resource abort used to burn the golem for the whole run, which is how a 4 block tunnel cost us a village's iron
    private final Map<Integer, Long> cooldown = new HashMap<>();
    private int attempts;
    private int refunds;
    private int ticks;
    private GolemFightTask task;

    public void onExit() {
        task = null;
    }

    // a fight that already started has to be finished by us (PrepSupport asks first so a chest does not pull us off the
    // pillar mid fight)
    public boolean active() {
        return task != null;
    }

    public String hud() {
        return task == null ? null : task.hud();
    }

    // the fight task while there is one running, a new one when the trigger says go, otherwise null
    public Task tick(AltoClef mod, GamerContext ctx, KitNeed current) {
        if (task != null) {
            if (!task.isFinished(mod)) {
                return task;
            }
            ctx.log(task.killed() ? "iron golem down" : "done with the golem");
            settle(task, ctx.facts().gameTime());
            task = null;
        }
        if (++ticks % CHECK_EVERY_TICKS != 0 || ctx.facts().dimension() != Dimension.OVERWORLD) {
            return null;
        }
        OverworldConfig cfg = ctx.cfg().overworld;
        LocalPlayer player = mod.getPlayer();
        long now = ctx.facts().gameTime();
        // the nearest golem we could actually fight, not the nearest golem: an angry one or one up a cliff used to win this
        // and then fail the verdict every 10 ticks while a calm one stood right behind it
        IronGolem golem = mod.getEntityTracker().getClosestEntity(e -> e instanceof IronGolem g && g.isAlive()
                && e.closerThan(player, cfg.golemHuntRadius)
                && GolemRules.eligible(g.isAggressive(), tried.contains(e.getId()), GolemRules.coolingDown(now, cooldown.get(e.getId())),
                launchNeed(player, g, cfg)), IronGolem.class)
                .map(e -> (IronGolem) e).orElse(null);
        // decided here and not at the foot of the golem: that is where the fight used to find out it was 1 block short
        int need = golem == null ? 0 : launchNeed(player, golem, cfg);
        // iron need only: a hunt for the 4th pickaxe nobody asked for is not what a golem is for
        boolean ironNeeded = current != null && "iron_ingot".equals(current.catalogueName()) && current.count() > 0;
        Inputs in = new Inputs(ironNeeded, true, golem != null, golem != null && golem.isAggressive(),
                false, attempts, cfg.golemMaxAttempts, hasWeapon(ctx.facts()),
                ctx.facts().buildBlocks(), cfg.golemMinBlocks, player.getHealth(), (float) cfg.golemMinHealth,
                // the monsters check is the expensive one, so it only runs when everything else already said yes
                golem != null && ironNeeded && GolemFightTask.monstersNear(mod, cfg.golemHostileRadius, golem.getId()),
                player.onGround(), player.isInWater() || player.isInLava(), need);
        Verdict verdict = GolemRules.shouldHunt(in);
        if (!verdict.go()) {
            return null;
        }
        tried.add(golem.getId());
        attempts++;
        adris.altoclef.Debug.logInternal("golem: launching, buildBlocks " + ctx.facts().buildBlocks() + " need " + need
                + " (wanted " + GolemRules.blocksWanted(need, cfg.golemMinBlocks) + "), us y=" + player.getY() + " golem y=" + golem.getY());
        task = new GolemFightTask(golem.getId(), ctx);
        ctx.progress("hunting an iron golem");
        return task;
    }

    // within a couple of pillar-starts of it our own Y is what the fight will stack from, further out it is just where we are
    private static int launchNeed(LocalPlayer player, IronGolem golem, OverworldConfig cfg) {
        boolean near = Math.hypot(golem.getX() - player.getX(), golem.getZ() - player.getZ()) <= NEAR_BLOCKS;
        return GolemRules.launchNeed(player.getY(), golem.getY(), cfg.golemSafeMargin, cfg.golemMaxPillar, near);
    }

    // a fight that backed out because of the day (blocks, monsters, a pillar that went nowhere) hands the golem back after a
    // cooldown and gives the attempt back, a fight that was about the golem (it got away, we hit it) stays spent
    private void settle(GolemFightTask finished, long now) {
        GolemRules.Abort why = finished.abort();
        if (!GolemRules.refund(why, refunds)) {
            return;
        }
        refunds++;
        attempts--;
        tried.remove(finished.golemId());
        cooldown.put(finished.golemId(), now + Math.round(GolemRules.RETRY_COOLDOWN_SECONDS * 20));
        adris.altoclef.Debug.logInternal("golem: backed out (" + why + "), it can be tried again in "
                + Math.round(GolemRules.RETRY_COOLDOWN_SECONDS) + " s (" + refunds + " of " + GolemRules.MAX_REFUNDS + " refunds used)");
    }

    private static boolean hasWeapon(GamerFacts facts) {
        for (Item sword : GolemFightTask.SWORDS) {
            if (facts.has(sword)) {
                return true;
            }
        }
        for (Item axe : GolemFightTask.AXES) {
            if (facts.has(axe)) {
                return true;
            }
        }
        return false;
    }
}
