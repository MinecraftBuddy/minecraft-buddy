package adris.altoclef.tasks.squashed;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.container.UpgradeInSmithingTableTask;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.StorageHelper;
import org.apache.commons.lang3.ArrayUtils;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

public class CataloguedResourceTask extends ResourceTask {


    private final TaskSquasher _squasher;
    private final ItemTarget[] _targets;
    private final List<ResourceTask> _tasksToComplete;

    public CataloguedResourceTask(boolean squash, ItemTarget... targets) {
        super(targets);
        _squasher = new TaskSquasher();
        _targets = targets;
        _tasksToComplete = new ArrayList<>(targets.length);

        for (ItemTarget target : targets) {
            if (target != null) {
                _tasksToComplete.add(TaskCatalogue.getItemTask(target));
            }
        }

        // two ores mined in list order walk past one to get the other, then walk back. as one task the nearest short one wins
        oresTogether(_tasksToComplete,
                task -> task instanceof MineAndCollectTask mine && mine.isOre(),
                ores -> MineAndCollectTask.together(ores.stream().map(MineAndCollectTask.class::cast).toList()));

        if (squash) {
            squashTasks(_tasksToComplete);
        }
    }

    public CataloguedResourceTask(ItemTarget... targets) {
        this(true, targets);
    }

    @Override
    protected void onResourceStart(AltoClef mod) {

    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // the pick counts what sits in an open crafting grid too. a sibling's craft borrowing four of our planks for its table
        // made those planks the first unmet target, which pulled them back out, which made the sibling unmet again. forever.
        // isFinished stays inventory only, the grid gets emptied before anybody calls this done
        return firstUnmet(_tasksToComplete, ResourceTask::getItemTargets, target -> mod.getItemStorage().getItemCount(target.getMatches()));
    }

    // the first task with a target short of `held`, or null when they are all met. generic so the test does not have to build a
    // ResourceTask, which wants a game
    static <T> T firstUnmet(List<T> tasks, Function<T, ItemTarget[]> targetsOf, ToIntFunction<ItemTarget> held) {
        for (T task : tasks) {
            for (ItemTarget target : targetsOf.apply(task)) {
                if (held.applyAsInt(target) < target.getTargetCount()) return task;
            }
        }
        return null;
    }

    // the ores, two or more of them, as one task in the first one's slot. everything else keeps its place, one ore stays
    // as it is. in place, so the constructor can't end up clearing the list it is copying from. generic for the same
    // reason as firstUnmet
    static <T> void oresTogether(List<T> tasks, Predicate<T> isOre, Function<List<T>, T> together) {
        List<T> ores = new ArrayList<>();
        int first = -1;
        for (int i = 0; i < tasks.size(); i++) {
            if (isOre.test(tasks.get(i))) {
                if (first < 0) first = i;
                ores.add(tasks.get(i));
            }
        }
        if (ores.size() < 2) {
            return;
        }
        T merged = together.apply(ores);
        tasks.removeIf(isOre);
        tasks.add(first, merged);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        for (ResourceTask task : _tasksToComplete) {
            for (ItemTarget target : task.getItemTargets()) {
                if (!StorageHelper.itemTargetsMetInventory(mod, target)) return false;
            }
        }
        // All targets are met.
        return true;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        // Useless
        return false;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CataloguedResourceTask task) {
            return Arrays.equals(task._targets, _targets);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Get catalogued: " + ArrayUtils.toString(_targets);
    }

    private void squashTasks(List<ResourceTask> tasks) {
        _squasher.addTasks(tasks);
        tasks.clear();
        tasks.addAll(_squasher.getSquashed());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static class TaskSquasher {

        private final Map<Class, adris.altoclef.tasks.squashed.TypeSquasher> _squashMap = new HashMap<>();

        private final List<ResourceTask> _unSquashableTasks = new ArrayList<>();

        public TaskSquasher() {
            _squashMap.put(CraftInTableTask.class, new CraftSquasher());
            _squashMap.put(UpgradeInSmithingTableTask.class, new SmithingSquasher());
            //_squashMap.put(MineAndCollectTask.class)
        }

        public void addTask(ResourceTask t) {
            Class type = t.getClass();
            if (_squashMap.containsKey(type)) {
                _squashMap.get(type).add(t);
            } else {
                //Debug.logMessage("Unsquashable: " + type + ": " + t);
                _unSquashableTasks.add(t);
            }
        }

        public void addTasks(List<ResourceTask> tasks) {
            for (ResourceTask task : tasks) {
                addTask(task);
            }
        }

        public List<ResourceTask> getSquashed() {
            List<ResourceTask> result = new ArrayList<>();

            for (Class type : _squashMap.keySet()) {
                result.addAll(_squashMap.get(type).getSquashed());
            }
            result.addAll(_unSquashableTasks);

            return result;
        }
    }


}
