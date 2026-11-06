/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */


package baritone.altoclef;

import adris.altoclef.AltoClef;
import adris.altoclef.chains.MLGBucketFallChain;
import adris.altoclef.chains.MobDefenseChain;
import adris.altoclef.control.InputControls;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.ClearLiquidTask;
import adris.altoclef.tasks.construction.PlaceBlockNearbyTask;
import adris.altoclef.tasks.construction.PlaceBlockTask;
import adris.altoclef.tasks.construction.PlaceObsidianBucketTask;
import adris.altoclef.tasks.construction.PlaceSignTask;
import adris.altoclef.tasks.construction.PlaceStationTask;
import adris.altoclef.tasks.entity.AbstractDoToEntityTask;
import adris.altoclef.tasks.entity.GiveItemToPlayerTask;
import adris.altoclef.tasks.entity.ShootArrowSimpleProjectileTask;
import adris.altoclef.tasks.resources.CollectBucketLiquidTask;
import adris.altoclef.tasks.speedrun.KillEnderDragonTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.CombatCommit;
import adris.altoclef.util.helpers.CombatRules;
import baritone.api.utils.input.Input;

// when autoTorch may click while altoclef has the bot. torches only go down while it is plainly mining, gathering or
// walking. a fight, a fall or a meal needs the hands and the camera this tick, and a task that places or clicks
// things itself would have its aim and its hotbar slot pulled out from under it by our torch
final class TorchGate {

    private TorchGate() {
    }

    static boolean allows(AltoClef ac) {
        return survivalQuiet(ac) && handsFree(ac.getInputControls()) && chainQuiet(ac);
    }

    private static boolean survivalQuiet(AltoClef ac) {
        MobDefenseChain defense = ac.getMobDefenseChain();
        if (defense.combatMode() != CombatCommit.Mode.NONE || defense.getCombatStance(ac) != CombatRules.Stance.CALM
                || ac.getFoodChain().isTryingToEat()) {
            return false;
        }
        MLGBucketFallChain mlg = ac.getMLGBucketChain();
        return !mlg.isFallingOhNo(ac) && mlg.doneMLG() && !mlg.isChorusFruiting();
    }

    private static boolean handsFree(InputControls controls) {
        return !controls.isHeldDown(Input.CLICK_RIGHT) && !controls.isHeldDown(Input.CLICK_LEFT);
    }

    // the winning chain has to be the user's, every survival chain (mob defense, mlg, world survival, food) winning means
    // the bot has something better to do. except mob defense at 0 or below: that's its idle offer, and with the runner
    // going and no user task it wins the wheel at 0 since nobody bids higher. it isn't fighting anything, survivalQuiet
    // already checked that. null is the runner not having picked anything yet. the heal-up hold is quiet too: it stands still
    // and waits, usually in the dark cave it ran out of, which is exactly where a torch is worth it (only the stand still
    // itself: a re-run or a creeper step in the middle of the hold has the wheel for something else)
    private static boolean chainQuiet(AltoClef ac) {
        TaskRunner runner = ac.getTaskRunner();
        TaskChain chain = runner.getCurrentTaskChain();
        MobDefenseChain defense = ac.getMobDefenseChain();
        if (chain == null || (chain == defense && (runner.winnerPriority() <= 0 || defense.isHealingUp()))) {
            return true;
        }
        if (chain != ac.getUserTaskChain()) {
            return false;
        }
        for (Task task : chain.getTasks()) {
            if (clicksThings(task)) {
                return false;
            }
        }
        return true;
    }

    // DestroyBlockTask and the resource tasks stay off this list on purpose, mining is exactly when we want torches.
    // the left click guard in TorchProcess keeps us out of the way mid dig
    private static boolean clicksThings(Task task) {
        return task instanceof AbstractDoToEntityTask || task instanceof PlaceBlockTask || task instanceof PlaceBlockNearbyTask
                || task instanceof PlaceStationTask || task instanceof PlaceSignTask || task instanceof PlaceObsidianBucketTask
                || task instanceof InteractWithBlockTask || task instanceof ShootArrowSimpleProjectileTask
                || task instanceof CollectBucketLiquidTask || task instanceof ClearLiquidTask
                || task instanceof KillEnderDragonTask || task instanceof GiveItemToPlayerTask;
    }
}
