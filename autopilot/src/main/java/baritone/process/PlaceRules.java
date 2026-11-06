/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// what a block gets to decide for itself once it's down, and what a movement can't put down without aiming. no
// world in here, the tests ask it straight
public final class PlaceRules {

    // anything with one of these comes out however the click was aimed. by name so a modded log is a log too. "type"
    // is a slab's top or bottom, which half of the face we clicked
    private static final Set<String> AIMED = Set.of("facing", "axis", "half", "type", "rotation", "orientation", "face", "hinge");
    // the path thread asks per node, and there's only so many states
    private static final Map<BlockState, Boolean> AIM_PROOF = new ConcurrentHashMap<>();
    // per block class, there's a lot fewer of those than states
    private static final Map<Class<?>, Boolean> USABLE = new ConcurrentHashMap<>();
    // the two ways a block gets a say in a right click, by name and signature
    private static final String USE_WITHOUT_ITEM = "useWithoutItem";
    private static final Class<?>[] USE_WITHOUT_ITEM_ARGS = {BlockState.class, Level.class, BlockPos.class, Player.class, BlockHitResult.class};
    private static final String USE_ITEM_ON = "useItemOn";
    private static final Class<?>[] USE_ITEM_ON_ARGS = {ItemStack.class, BlockState.class, Level.class, BlockPos.class, Player.class, InteractionHand.class, BlockHitResult.class};
    // if the names ever stop matching (remapped, renamed) every lookup would say "doesn't override" and we'd click chests
    // open unsneaked. so if the base class doesn't have them either, everything counts as usable
    private static final boolean USE_NAMES_OK = declares(BlockBehaviour.class, USE_WITHOUT_ITEM, USE_WITHOUT_ITEM_ARGS)
            && declares(BlockBehaviour.class, USE_ITEM_ON, USE_ITEM_ON_ARGS);

    private PlaceRules() {
    }

    // vanilla works these out from the neighbours on placement and again on every neighbour update, so asking for them
    // is asking for the neighbours. a stair next to a sideways stair is a corner whether the file likes it or not.
    // by block AND property: a fence's north is literally PipeBlock.NORTH, and a vine's north is a face somebody chose
    public static boolean derived(Block block, Property<?> prop) {
        if (prop == BlockStateProperties.WATERLOGGED) {
            // the builder doesn't pour into blocks (it sneaks, the bucket goes one out), so a wet stair the file wants
            // would be a break and place loop forever. dry or wet, a stair is a stair
            return true;
        }
        if (block instanceof StairBlock) {
            return prop == StairBlock.SHAPE;
        }
        if (block instanceof CrossCollisionBlock) { // fences, panes, iron bars
            return prop == CrossCollisionBlock.NORTH || prop == CrossCollisionBlock.EAST
                    || prop == CrossCollisionBlock.SOUTH || prop == CrossCollisionBlock.WEST;
        }
        if (block instanceof WallBlock) {
            return prop == WallBlock.NORTH || prop == WallBlock.EAST || prop == WallBlock.SOUTH
                    || prop == WallBlock.WEST || prop == WallBlock.UP;
        }
        if (block instanceof FenceGateBlock) {
            // open is whoever walked through last, us included (traverse opens gates on the way). same for doors and
            // trapdoors below, and vanilla places all three open if there's power, so the file can't win that one anyway
            return prop == FenceGateBlock.IN_WALL || prop == FenceGateBlock.OPEN;
        }
        if (block instanceof RedstoneWireBlock) {
            return prop == RedstoneWireBlock.NORTH || prop == RedstoneWireBlock.EAST
                    || prop == RedstoneWireBlock.SOUTH || prop == RedstoneWireBlock.WEST
                    || prop == RedstoneWireBlock.POWER;
        }
        if (block instanceof LeavesBlock) {
            // distance is the log count, persistent is "a player put this here", which we are
            return prop == LeavesBlock.DISTANCE || prop == LeavesBlock.PERSISTENT;
        }
        if (block instanceof NoteBlock) {
            // instrument is whatever's under it, powered is the circuit. the pitch is ours (and we don't click it)
            return prop == NoteBlock.INSTRUMENT || prop == NoteBlock.POWERED;
        }
        if (block instanceof DoorBlock) {
            return prop == DoorBlock.POWERED || prop == DoorBlock.OPEN;
        }
        if (block instanceof TrapDoorBlock) {
            return prop == TrapDoorBlock.POWERED || prop == TrapDoorBlock.OPEN;
        }
        if (block instanceof SnowyBlock) {
            return prop == SnowyBlock.SNOWY;
        }
        if (block instanceof BaseRailBlock rail) {
            // a rail bends towards whatever rails are next to it, on placement and on every neighbour update. the click
            // has no say, so a curve the file wants was a break and place loop till the neighbours happened to agree.
            // powered is redstone (or a minecart for the detector one), not ours either
            return prop == rail.getShapeProperty() || prop == PoweredRailBlock.POWERED || prop == DetectorRailBlock.POWERED;
        }
        return redstone(block, prop);
    }

    // the redstone half of derived, split off so neither half turns into one giant guard chain. same deal though:
    // powered, lit, triggered and friends are whatever the circuit (or an entity, or the last few ticks) says right
    // now. the file saved one moment of a working machine, and asking for that moment back was a break and place loop.
    // the piston was the famous one: the file's own redstone pushes it out, extended reads wrong, we break it, put it
    // back retracted, the circuit pushes it out again
    private static boolean redstone(Block block, Property<?> prop) {
        if (block instanceof PistonBaseBlock) { // sticky too
            return prop == PistonBaseBlock.EXTENDED;
        }
        if (block instanceof RedstoneTorchBlock || block instanceof RedstoneLampBlock) { // wall torch too
            return prop == BlockStateProperties.LIT;
        }
        if (block instanceof RedStoneOreBlock) {
            return prop == RedStoneOreBlock.LIT; // we light it up just by walking on it lol
        }
        if (block instanceof DiodeBlock) {
            // locked is the repeater next to it. delay and comparator mode are clicks somebody chose, those still count
            return prop == DiodeBlock.POWERED || (block instanceof RepeaterBlock && prop == RepeaterBlock.LOCKED);
        }
        if (block instanceof ObserverBlock) {
            return prop == ObserverBlock.POWERED; // two ticks of "something changed", like us placing next to it
        }
        if (block instanceof DispenserBlock) { // dropper too
            return prop == DispenserBlock.TRIGGERED;
        }
        if (block instanceof CrafterBlock) {
            return prop == CrafterBlock.TRIGGERED || prop == CrafterBlock.CRAFTING;
        }
        if (block instanceof HopperBlock) {
            return prop == HopperBlock.ENABLED;
        }
        if (block instanceof DaylightDetectorBlock) {
            // power is the sun. inverted is a click, that one's still on the file
            return prop == DaylightDetectorBlock.POWER;
        }
        if (block instanceof TargetBlock) {
            return prop == BlockStateProperties.POWER; // whoever shot it last
        }
        return redstoneComponent(block, prop);
    }

    // the stuff that gets pressed, stood on, rung or tripped. no lever: that one's a click, somebody chose it
    private static boolean redstoneComponent(Block block, Property<?> prop) {
        if (block instanceof LightningRodBlock) {
            return prop == LightningRodBlock.POWERED;
        }
        if (block instanceof BellBlock) {
            return prop == BellBlock.POWERED;
        }
        if (block instanceof LecternBlock) {
            return prop == LecternBlock.POWERED;
        }
        if (block instanceof ButtonBlock) {
            return prop == ButtonBlock.POWERED;
        }
        if (block instanceof PressurePlateBlock) {
            return prop == PressurePlateBlock.POWERED; // we stand on it to place the next block, of course it's down
        }
        if (block instanceof WeightedPressurePlateBlock) {
            return prop == WeightedPressurePlateBlock.POWER;
        }
        if (block instanceof TripWireHookBlock) {
            return prop == TripWireHookBlock.POWERED || prop == TripWireHookBlock.ATTACHED;
        }
        if (block instanceof TripWireBlock) {
            // attached and the four sides come from the hooks and the string next to it. disarmed is shears, not
            // redstone, but we never shear anything, so asking for it is just a stall
            return prop == TripWireBlock.POWERED || prop == TripWireBlock.ATTACHED || prop == TripWireBlock.DISARMED
                    || prop == TripWireBlock.NORTH || prop == TripWireBlock.EAST
                    || prop == TripWireBlock.SOUTH || prop == TripWireBlock.WEST;
        }
        if (block instanceof SculkSensorBlock) { // calibrated too
            return prop == SculkSensorBlock.PHASE || prop == SculkSensorBlock.POWER; // and we're the loudest thing nearby
        }
        if (block instanceof CopperBulbBlock) {
            // lit flips on every rising edge, so it's the pulse history, not even the current power. good luck to the
            // file on that one
            return prop == CopperBulbBlock.POWERED || prop == CopperBulbBlock.LIT;
        }
        return false;
    }

    // a right click on this does something with a block in hand (opens, flips, eats, ties a lead), so it only turns into a
    // placement sneaking. on purpose by "the class has its own use method" and not a list: a fence takes a lead and gets
    // a sneak it didn't need, but a list that forgot one clicks a chest open and stares into it
    public static boolean usable(BlockState state) {
        if (state.hasBlockEntity()) {
            return true; // same call MovementHelper.opensWhenClicked makes, signs and beds and friends
        }
        return !USE_NAMES_OK || USABLE.computeIfAbsent(state.getBlock().getClass(), PlaceRules::overridesUse);
    }

    private static boolean overridesUse(Class<?> type) {
        for (Class<?> c = type; c != null && c != BlockBehaviour.class; c = c.getSuperclass()) {
            if (declares(c, USE_WITHOUT_ITEM, USE_WITHOUT_ITEM_ARGS) || declares(c, USE_ITEM_ON, USE_ITEM_ON_ARGS)) {
                return true;
            }
        }
        return false;
    }

    private static boolean declares(Class<?> c, String name, Class<?>[] args) {
        try {
            c.getDeclaredMethod(name, args);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        } catch (RuntimeException | LinkageError e) {
            return true; // couldn't tell, so it's usable. a sneak too many is just a crouch
        }
    }

    // the placement itself asks whether we're sneaking: a chest joins a neighbour or doesn't, a candle goes into the one
    // we clicked or next to it, scaffolding stacks or goes where we aimed. for these the planner tries both
    public static boolean readsSneak(Block block) {
        return block instanceof ChestBlock || block instanceof CeilingHangingSignBlock || block instanceof SpeleothemBlock
                || block instanceof CandleBlock || block instanceof SeaPickleBlock || block instanceof SegmentableBlock
                || block instanceof TurtleEggBlock || block instanceof ScaffoldingBlock;
    }

    // a block that comes out right whichever face a movement happens to click: a full cube with nothing to aim.
    // a vertical log is a full cube too and a side click lays it down, so having the property at all is a no
    public static boolean aimProof(BlockState state) {
        return AIM_PROOF.computeIfAbsent(state, PlaceRules::computeAimProof);
    }

    // has something that comes off which way the click was aimed. a fence or a wall is not a full cube, but nothing on it
    // depends on where we were looking (the sides are derived). a slab does, its type is which half we clicked
    public static boolean aimed(BlockState state) {
        for (Property<?> prop : state.getProperties()) {
            if (AIMED.contains(prop.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean computeAimProof(BlockState state) {
        if (aimed(state)) {
            return false;
        }
        VoxelShape shape = collision(state);
        return shape != null && Block.isShapeFullBlock(shape);
    }

    // null when the block wanted a real world to answer, which is a no
    private static VoxelShape collision(BlockState state) {
        try {
            return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (Exception e) {
            return null;
        }
    }
}
