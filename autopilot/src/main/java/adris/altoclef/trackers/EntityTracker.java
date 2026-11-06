package adris.altoclef.trackers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.PlayerCollidedWithEntityEvent;
import baritone.Baritone;
import baritone.utils.accessor.IPersistentProjectile;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.baritone.CachedProjectile;
import adris.altoclef.util.helpers.BaritoneHelper;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.FoeRules;
import adris.altoclef.util.helpers.ItemPickupRules;
import adris.altoclef.util.helpers.MobReachability;
import adris.altoclef.util.helpers.Provocations;
import adris.altoclef.util.helpers.ProjectileHelper;
import adris.altoclef.util.helpers.WorldHelper;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps track of entities so we can search/grab them.
 */
@SuppressWarnings("rawtypes")
public class EntityTracker extends Tracker {

    private final HashMap<Item, List<ItemEntity>> _itemDropLocations = new HashMap<>();
    private final HashMap<Class, List<Entity>> _entityMap = new HashMap<>();

    private final List<Entity> _closeEntities = new ArrayList<>();
    private final List<Entity> _hostiles = new ArrayList<>();

    private final List<CachedProjectile> _projectiles = new ArrayList<>();

    private final HashMap<String, Player> _playerMap = new HashMap<>();
    private final HashMap<String, Vec3> _playerLastCoordinates = new HashMap<>();

    private final MobReachability _mobReach = new MobReachability();
    // who we hit and who hit us lately, the only honest "it is angry at us" the client can get (see NeutralMobs)
    private final Provocations _provocations = new Provocations();
    // the last damage source noteAttackers turned into a "hit us" mark, by identity (see there)
    private DamageSource _lastNotedSource;
    // entity ids we already said we were skipping, so the log gets one line per drop and not one per tick
    private final Set<Integer> _skippedWetDrops = new HashSet<>();
    private final Set<Integer> _skippedLavaDrops = new HashSet<>();
    // the ender pearl item log for the pearl hunt (see PearlWatch)
    private final PearlWatch _pearlWatch = new PearlWatch();
    // isPickupSafe can be ~45 block lookups per drop, so each drop's verdict lives a few ticks instead of being redone
    // every tick. rebuilt each update from the drops that were actually asked about, so dead ones fall out by themselves
    private HashMap<Integer, PickupVerdict> _pickupVerdicts = new HashMap<>();
    private static final int PICKUP_RECHECK_TICKS = 10;

    private record PickupVerdict(long pos, long tick, boolean safe, boolean lava) {
    }

    private final HashMap<Player, Set<Entity>> _entitiesCollidingWithPlayerAccumulator = new HashMap<>();
    private final HashMap<Player, HashSet<Entity>> _entitiesCollidingWithPlayer = new HashMap<>();

    public EntityTracker(TrackerManager manager) {
        super(manager);

        // Listen for player collisions
        EventBus.subscribe(PlayerCollidedWithEntityEvent.class, evt -> registerPlayerCollision(evt.player, evt.other));
    }

    /**
     * Squash a class that may have sub classes into one distinguishable class type.
     * For ease of use.
     *
     * @param type: An entity class that may have a 'simpler' class to squash to
     * @return what the given entity class should be read as/catalogued as.
     */
    private static Class squashType(Class type) {
        // Squash types for ease of use
        if (Player.class.isAssignableFrom(type)) {
            return Player.class;
        }
        return type;
    }

    private void registerPlayerCollision(Player player, Entity entity) {
        // only updateState drains this and it only runs while altoclef drives, so nothing gets to pile up while idle
        if (!AltoClef.isRunning()) {
            return;
        }
        if (!_entitiesCollidingWithPlayerAccumulator.containsKey(player)) {
            _entitiesCollidingWithPlayerAccumulator.put(player, new HashSet<>());
        }
        _entitiesCollidingWithPlayerAccumulator.get(player).add(entity);
    }

    public boolean isCollidingWithPlayer(Player player, Entity entity) {
        return _entitiesCollidingWithPlayer.containsKey(player) && _entitiesCollidingWithPlayer.get(player).contains(entity);
    }

    public boolean isCollidingWithPlayer(Entity entity) {
        return isCollidingWithPlayer(_mod.getPlayer(), entity);
    }

    public Optional<ItemEntity> getClosestItemDrop(Item... items) {
        return getClosestItemDrop(_mod.getPlayer().position(), items);
    }

    public Optional<ItemEntity> getClosestItemDrop(Vec3 position, Item... items) {
        return getClosestItemDrop(position, entity -> true, items);
    }

    public Optional<ItemEntity> getClosestItemDrop(Vec3 position, ItemTarget... items) {
        return getClosestItemDrop(position, entity -> true, items);
    }

    public Optional<ItemEntity> getClosestItemDrop(Predicate<ItemEntity> acceptPredicate, Item... items) {
        return getClosestItemDrop(_mod.getPlayer().position(), acceptPredicate, items);
    }

    public Optional<ItemEntity> getClosestItemDrop(Vec3 position, Predicate<ItemEntity> acceptPredicate, Item... items) {
        ensureUpdated();
        ItemTarget[] tempTargetList = new ItemTarget[items.length];
        for (int i = 0; i < items.length; ++i) {
            tempTargetList[i] = new ItemTarget(items[i], 9999999);
        }
        return getClosestItemDrop(position, acceptPredicate, tempTargetList);
    }

    public Optional<ItemEntity> getClosestItemDrop(Vec3 position, Predicate<ItemEntity> acceptPredicate, ItemTarget... targets) {
        ensureUpdated();
        if (targets.length == 0) {
            Debug.logError("You asked for the drop position of zero items... Most likely a typo.");
            return Optional.empty();
        }
        if (!itemDropped(targets)) {
            return Optional.empty();
        }

        ItemEntity closestEntity = null;
        float minCost = Float.POSITIVE_INFINITY;
        for (ItemTarget target : targets) {
            for (Item item : target.getMatches()) {
                if (!itemDropped(item)) continue;
                ItemEntity best = closest(_mod.getBans(), _itemDropLocations.get(item), Entity::getId,
                        entity -> entity.getItem().getItem().equals(item) && acceptPredicate.test(entity),
                        entity -> (float) BaritoneHelper.calculateGenericHeuristic(position, entity.position()));
                if (best != null) {
                    float cost = (float) BaritoneHelper.calculateGenericHeuristic(position, best.position());
                    if (cost < minCost) {
                        minCost = cost;
                        closestEntity = best;
                    }
                }
            }
        }
        return Optional.ofNullable(closestEntity);
    }

    public Optional<Entity> getClosestEntity(Class... entityTypes) {
        return getClosestEntity(_mod.getPlayer().position(), entityTypes);
    }

    public Optional<Entity> getClosestEntity(Vec3 position, Class... entityTypes) {
        return this.getClosestEntity(position, (entity) -> true, entityTypes);
    }

    public Optional<Entity> getClosestEntity(Predicate<Entity> acceptPredicate, Class... entityTypes) {
        return getClosestEntity(_mod.getPlayer().position(), acceptPredicate, entityTypes);
    }

    public Optional<Entity> getClosestEntity(Vec3 position, Predicate<Entity> acceptPredicate, Class... entityTypes) {
        Entity closestEntity = null;
        double minCost = Float.POSITIVE_INFINITY;
        for (Class toFind : entityTypes) {
            synchronized (BaritoneHelper.MINECRAFT_LOCK) {
                if (_entityMap.containsKey(toFind)) {
                    // Don't accept entities that no longer exist
                    Entity best = closest(_mod.getBans(), _entityMap.get(toFind), Entity::getId,
                            entity -> entity.isAlive() && acceptPredicate.test(entity), entity -> entity.distanceToSqr(position));
                    if (best != null) {
                        double cost = best.distanceToSqr(position);
                        if (cost < minCost) {
                            minCost = cost;
                            closestEntity = best;
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(closestEntity);
    }

    public boolean itemDropped(Item... items) {
        ensureUpdated();
        for (Item item : items) {
            if (_itemDropLocations.containsKey(item)) {
                // Find a non-blacklisted item
                for (ItemEntity entity : _itemDropLocations.get(item)) {
                    if (!_mod.getBans().entityBanned(entity.getId())) return true;
                }
            }
        }
        return false;
    }

    public boolean itemDropped(ItemTarget... targets) {
        ensureUpdated();
        for (ItemTarget target : targets) {
            if (itemDropped(target.getMatches())) return true;
        }
        return false;
    }

    public List<ItemEntity> getDroppedItems() {
        ensureUpdated();
        return _itemDropLocations.values().stream().reduce(new ArrayList<>(), (result, drops) -> {
            result.addAll(drops);
            return result;
        });
    }

    public boolean entityFound(Predicate<Entity> shouldAccept, Class... types) {
        ensureUpdated();
        for (Class type : types) {
            synchronized (BaritoneHelper.MINECRAFT_LOCK) {
                for (Entity entity : _entityMap.getOrDefault(type, Collections.emptyList())) {
                    if (shouldAccept.test(entity))
                        return true;
                }
            }
        }
        return false;
    }

    public boolean entityFound(Class... types) {
        return entityFound(check -> true, types);
    }

    public <T extends Entity> List<T> getTrackedEntities(Class<T> type) {
        ensureUpdated();
        if (!entityFound(type)) {
            return Collections.emptyList();
        }
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            //noinspection unchecked
            return (List<T>) _entityMap.get(type);
        }
    }

    /**
     * Gets all entities that are within our interact range
     */
    public List<Entity> getCloseEntities() {
        ensureUpdated();
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            return _closeEntities;
        }
    }

    /**
     * Gets a list of projectiles that we've cached/stored information about.
     */
    public List<CachedProjectile> getProjectiles() {
        ensureUpdated();
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            return _projectiles;
        }
    }

    public List<Entity> getHostiles() {
        ensureUpdated();
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            return _hostiles;
        }
    }

    /**
     * Is a player loaded/within render distance?
     *
     * @param name Username on a multiplayer server
     */
    public boolean isPlayerLoaded(String name) {
        ensureUpdated();
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            return _playerMap.containsKey(name);
        }
    }

    /**
     * Get where we last saw a player, if we saw them at all.
     *
     * @return Username on a multiplayer server.
     */
    public Optional<Vec3> getPlayerMostRecentPosition(String name) {
        ensureUpdated();
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            return Optional.ofNullable(_playerLastCoordinates.getOrDefault(name, null));
        }
    }

    /**
     * Gets the player entity corresponding to a username, if they're loaded/within render distance.
     *
     * @param name Username on a multiplayer server.
     */
    public Optional<Player> getPlayerEntity(String name) {
        if (isPlayerLoaded(name)) {
            synchronized (BaritoneHelper.MINECRAFT_LOCK) {
                return Optional.of(_playerMap.get(name));
            }
        }
        return Optional.empty();
    }

    // we could not get to it. the fourth time (none of them from closer than the last) it is banned, see BanPolicy.entityStrike
    public void requestEntityUnreachable(Entity entity) {
        BanPolicy.entityStrike(_mod.getBans(), entity.getId(), 3, entity.position().distanceToSqr(_mod.getPlayer().position()),
                "couldn't reach " + describe(entity));
    }

    // false while anybody's ban on it holds
    public boolean isEntityReachable(Entity entity) {
        return !_mod.getBans().entityBanned(entity.getId());
    }

    // what a ban line calls it: the item for a drop, the mob type otherwise
    public static String describe(Entity entity) {
        if (entity instanceof ItemEntity drop) {
            return BuiltInRegistries.ITEM.getKey(drop.getItem().getItem()).getPath();
        }
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
    }

    // the nearest unbanned one that passes, by cost. the one loop both lookups go through, so a ban is a ban for every caller
    static <T> T closest(Bans bans, Iterable<T> candidates, ToIntFunction<T> id, Predicate<T> accept, ToDoubleFunction<T> cost) {
        T best = null;
        double min = Double.POSITIVE_INFINITY;
        for (T c : candidates) {
            if (bans.entityBanned(id.applyAsInt(c)) || !accept.test(c)) continue;
            double v = cost.applyAsDouble(c);
            if (v < min) {
                min = v;
                best = c;
            }
        }
        return best;
    }

    // not baked into getHostiles on purpose: "it is angry" is still true for the run away and eating checks even when it
    // cannot get to us. only the decisions about fighting ask this
    public MobReachability getMobReachability() {
        return _mobReach;
    }

    public Provocations getProvocations() {
        return _provocations;
    }

    // we just hit this one (or it just hit us). anything neutral stops being calm for a while
    public void noteProvoked(Entity entity) {
        if (entity instanceof Mob && Minecraft.getInstance().level != null) {
            _provocations.mark(entity.getId(), Minecraft.getInstance().level.getGameTime());
        }
    }

    // the damage source and the harmful effects we had last tick, for blaming a witch (see noteWitch)
    private DamageSource _lastMagicSource;
    private boolean _hadHarmfulEffect;

    // a witch's poison arrives as magic damage with nobody attached, and gaining poison, weakness or slowness has no sender at
    // all. without a name the "it hit us" memory never hears about the witch, and a fight that stalled on it stays given up on
    // for as long as the poison runs. the nearest witch with a line to us inside FoeRules.WITCH_HIT_RANGE gets the blame
    private void noteWitch(LocalPlayer player, DamageSource source) {
        boolean harmfulNow = player.hasEffect(MobEffects.POISON) || player.hasEffect(MobEffects.WEAKNESS) || player.hasEffect(MobEffects.SLOWNESS);
        boolean gained = harmfulNow && !_hadHarmfulEffect;
        _hadHarmfulEffect = harmfulNow;
        boolean magic = source != null && source != _lastMagicSource && FoeRules.unattributedMagic(source.getMsgId(), source.getEntity() != null);
        if (source != null) _lastMagicSource = source;
        if (!gained && !magic) return;
        List<FoeRules.WitchLook> looks = new ArrayList<>();
        Map<Integer, Witch> byId = new HashMap<>();
        for (Entity entity : Minecraft.getInstance().level.entitiesForRendering()) {
            if (entity instanceof Witch witch && witch.isAlive()) {
                looks.add(new FoeRules.WitchLook(witch.getId(), witch.distanceTo(player), witch.hasLineOfSight(player)));
                byId.put(witch.getId(), witch);
            }
        }
        FoeRules.WitchLook culprit = FoeRules.witchBehind(true, looks);
        if (culprit != null) {
            Witch witch = byId.get(culprit.id());
            noteProvoked(witch);
            noteHitUs(witch);
        }
    }

    private void noteAttackers() {
        if (_mod.getPlayer() == null) return;
        DamageSource source = _mod.getPlayer().getLastDamageSource();
        if (Minecraft.getInstance().level != null) noteWitch(_mod.getPlayer(), source);
        if (source == null) return;
        // the attacker for melee and a shot's shooter, and the thing that actually touched us
        noteProvoked(source.getEntity());
        noteProvoked(source.getDirectEntity());
        // the client keeps one source for two seconds, but every hit brings a new object. the first time we see an object
        // is the hit, the other forty ticks are just the same hit still being around
        if (source != _lastNotedSource) {
            _lastNotedSource = source;
            noteHitUs(source.getEntity());
            noteHitUs(source.getDirectEntity());
        }
    }

    // the strict book: this mob hit us, right now. a hit by an arrow is the shooter's hit (the arrow is no Mob)
    private void noteHitUs(Entity entity) {
        if (entity instanceof Mob && Minecraft.getInstance().level != null) {
            _provocations.markHit(entity.getId(), Minecraft.getInstance().level.getGameTime());
            // it got to us, so "no path to it" was wrong or is over
            _mod.getBans().hitBy(entity.getId());
        }
    }

    @Override
    protected synchronized void updateState() {
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            _itemDropLocations.clear();
            _entityMap.clear();
            _closeEntities.clear();
            _projectiles.clear();
            _hostiles.clear();
            _playerMap.clear();
            if (Minecraft.getInstance().level == null) return;

            // whatever hurt us last (the client keeps the source for two seconds) is after us, neutral or not. goes before
            // the hostile pass below so a piglin that just hit us is a hostile on the same tick
            noteAttackers();

            // Store/Register All accumulated player collisions for this frame.
            _entitiesCollidingWithPlayer.clear();
            for (Map.Entry<Player, Set<Entity>> collisions : _entitiesCollidingWithPlayerAccumulator.entrySet()) {
                _entitiesCollidingWithPlayer.put(collisions.getKey(), new HashSet<>());
                _entitiesCollidingWithPlayer.get(collisions.getKey()).addAll(collisions.getValue());
            }
            _entitiesCollidingWithPlayerAccumulator.clear();

            boolean allowWetDrops = Baritone.settings().altoPickupItemsInWater.value;
            long now = Minecraft.getInstance().level.getGameTime();
            HashMap<Integer, PickupVerdict> verdicts = new HashMap<>();

            // Loop through all entities and track 'em
            for (Entity entity : Minecraft.getInstance().level.entitiesForRendering()) {

                // Catalogue based on type. Some types may get "squashed" or combined into one.
                Class type = entity.getClass();
                type = squashType(type);

                //noinspection ConstantConditions
                if (entity == null || !entity.isAlive()) continue;

                // Don't catalogue our own player.
                if (type == Player.class && entity.equals(_mod.getPlayer())) continue;

                if (!_entityMap.containsKey(type)) {
                    _entityMap.put(type, new ArrayList<>());
                }
                _entityMap.get(type).add(entity);

                if (_mod.getControllerExtras().inRange(entity)) {
                    _closeEntities.add(entity);
                }

                if (entity instanceof ItemEntity ientity) {
                    Item droppedItem = ientity.getItem().getItem();
                    // (an item is none of the mob / projectile / player kinds below, so this used to just continue)
                    String listed = "airborne";

                    // Only cared about GROUNDED item entities
                    if (ientity.onGround() || ientity.isInWater() || WorldHelper.isSolid(_mod, ientity.blockPosition().below(2)) || WorldHelper.isSolid(_mod, ientity.blockPosition().below(3))) {
                        // every drop lookup in altoclef goes through this map, so skipping wet items here covers all
                        // of them at once, and updateState only runs once a tick so it doubles as the cache
                        PickupVerdict verdict = pickupVerdict(ientity, now, verdicts, allowWetDrops);
                        // lava is not a setting, nothing is worth dying for. it comes back by itself once the lava
                        // is gone, the verdict is redone every few ticks
                        if (verdict.lava()) {
                            noteSkippedLavaDrop(ientity);
                            listed = "lava";
                        } else if (!verdict.safe()) {
                            noteSkippedWetDrop(ientity);
                            listed = "wet";
                        } else {
                            if (!_itemDropLocations.containsKey(droppedItem)) {
                                _itemDropLocations.put(droppedItem, new ArrayList<>());
                            }
                            _itemDropLocations.get(droppedItem).add(ientity);
                            listed = "listed";
                        }
                    }
                    if (droppedItem == Items.ENDER_PEARL) {
                        String line = _pearlWatch.see(ientity.getId(), ientity.getX(), ientity.getY(), ientity.getZ(), ientity.onGround(), ientity.isInLava(), listed);
                        if (line != null) {
                            Debug.logInternal(line);
                        }
                    }
                    continue;
                }
                if (entity instanceof Mob) {
                    // distance first: isAngryAtPlayer does a line of sight raycast for endermen, and most mobs
                    // in render distance are nowhere near close enough to care
                    if (entity.closerThan(_mod.getPlayer(), 16) && EntityHelper.isAngryAtPlayer(_mod, entity)) {
                        _hostiles.add(entity);
                    }
                } else if (entity instanceof Projectile projEntity) {
                    if (!_mod.getBehaviour().shouldAvoidDodgingProjectile(entity)) {
                        CachedProjectile proj = new CachedProjectile();

                        boolean inGround = false;
                        // Get projectile "inGround" variable
                        if (entity instanceof AbstractArrow) {
                            inGround = ((IPersistentProjectile) entity).callIsInGround();
                        }

                        // Ignore some of the harlmess projectiles
                        if (projEntity instanceof FishingHook || projEntity instanceof ThrownEnderpearl || projEntity instanceof ThrownExperienceBottle)
                            continue;

                        if (!inGround) {
                            proj.position = projEntity.position();
                            proj.velocity = projEntity.getDeltaMovement();
                            proj.gravity = ProjectileHelper.hasGravity(projEntity) ? ProjectileHelper.ARROW_GRAVITY_ACCEL : 0;
                            proj.projectileType = projEntity.getClass();
                            _projectiles.add(proj);
                        }
                    }
                } else if (entity instanceof Player player) {
                    String name = player.getName().getString();
                    _playerMap.put(name, player);
                    _playerLastCoordinates.put(name, player.position());
                }
            }
            _pickupVerdicts = verdicts;
            sweepPearls(now);
        }
    }

    // the pearl log's end of pass. the bag is only counted while a pearl is around or a gone one waits on its count
    private void sweepPearls(long now) {
        if (_pearlWatch.idle()) {
            return;
        }
        Player me = _mod.getPlayer();
        if (me == null) {
            return;
        }
        int pearls = 0;
        Inventory inv = me.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(Items.ENDER_PEARL)) {
                pearls += stack.getCount();
            }
        }
        for (String line : _pearlWatch.sweep(me.getX(), me.getY(), me.getZ(), pearls, now)) {
            Debug.logInternal(line);
        }
    }

    // redo the check when the drop moves to another block or the verdict is old enough that the water or lava may have
    // changed. the water half is skipped (counts as safe) when wet drops are allowed
    private PickupVerdict pickupVerdict(ItemEntity drop, long now, HashMap<Integer, PickupVerdict> verdicts, boolean allowWetDrops) {
        long pos = drop.blockPosition().asLong();
        PickupVerdict verdict = _pickupVerdicts.get(drop.getId());
        if (verdict == null || verdict.pos() != pos || now - verdict.tick() >= PICKUP_RECHECK_TICKS) {
            verdict = new PickupVerdict(pos, now, allowWetDrops || ItemPickupRules.worthSwimming(drop) || ItemPickupRules.isPickupSafe(drop), ItemPickupRules.lavaBlocksPickup(drop));
        }
        verdicts.put(drop.getId(), verdict);
        return verdict;
    }

    private void noteSkippedLavaDrop(ItemEntity drop) {
        if (_skippedLavaDrops.add(drop.getId())) {
            BlockPos at = drop.blockPosition();
            Debug.logInternal("pickup: skipping " + BuiltInRegistries.ITEM.getKey(drop.getItem().getItem()).getPath()
                    + " at " + at.getX() + " " + at.getY() + " " + at.getZ() + ", it is next to lava");
        }
    }

    private void noteSkippedWetDrop(ItemEntity drop) {
        if (_skippedWetDrops.add(drop.getId())) {
            Debug.logInternal("Skipping " + drop.getItem().getItem().getDescriptionId() + " in water at " + drop.blockPosition().toShortString());
        }
    }

    @Override
    protected void reset() {
        // Dirty clears everything else. the bans are cleared by the tracker manager, they are not ours
        _mobReach.reset();
        _skippedWetDrops.clear();
        _skippedLavaDrops.clear();
        _pickupVerdicts.clear();
        _pearlWatch.reset();
        // these are keyed by LocalPlayer and hold entities, so each of them pins a whole ClientLevel. gone with the world
        _entitiesCollidingWithPlayerAccumulator.clear();
        _entitiesCollidingWithPlayer.clear();
        _playerLastCoordinates.clear();
    }
}
