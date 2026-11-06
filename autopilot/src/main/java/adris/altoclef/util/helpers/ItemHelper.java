package adris.altoclef.util.helpers;

import baritone.Baritone;
import baritone.process.TorchProcess;
import adris.altoclef.AltoSettings;
import adris.altoclef.AltoClef;
import adris.altoclef.util.WoodType;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
import net.minecraft.world.level.material.MapColor;

/**
 * Helper functions and definitions for useful groupings of items
 */
public class ItemHelper {
    public static final Item[] COPPER_BLOCKS = new Item[]{Items.COPPER_BLOCK.weathering().unaffected(), Items.COPPER_BLOCK.weathering().exposed(),
            Items.COPPER_BLOCK.weathering().weathered(), Items.COPPER_BLOCK.weathering().oxidized(),
            Items.CUT_COPPER.weathering().unaffected(), Items.CUT_COPPER.weathering().exposed(),
            Items.CUT_COPPER.weathering().weathered(), Items.CUT_COPPER.weathering().oxidized(),
            Items.COPPER_BLOCK.waxed().unaffected(), Items.COPPER_BLOCK.waxed().exposed(),
            Items.COPPER_BLOCK.waxed().weathered(), Items.COPPER_BLOCK.waxed().oxidized(),
            Items.CUT_COPPER.waxed().unaffected(), Items.CUT_COPPER.waxed().exposed(),
            Items.CUT_COPPER.waxed().weathered(), Items.CUT_COPPER.waxed().oxidized()};
    public static final Item[] SAPLINGS = new Item[]{Items.OAK_SAPLING, Items.SPRUCE_SAPLING, Items.BIRCH_SAPLING,
            Items.JUNGLE_SAPLING, Items.ACACIA_SAPLING, Items.DARK_OAK_SAPLING, Items.MANGROVE_PROPAGULE,
            Items.CHERRY_SAPLING, Items.PALE_OAK_SAPLING, Items.POPLAR_SAPLING};
    public static final Block[] SAPLING_SOURCES = new Block[]{Blocks.OAK_LEAVES, Blocks.SPRUCE_LEAVES,
            Blocks.BIRCH_LEAVES, Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES,
            Blocks.MANGROVE_PROPAGULE, Blocks.CHERRY_LEAVES, Blocks.PALE_OAK_LEAVES, Blocks.ORANGE_POPLAR_LEAVES, Blocks.RED_POPLAR_LEAVES, Blocks.YELLOW_POPLAR_LEAVES};
    public static final Item[] HOSTILE_MOB_DROPS = new Item[]{Items.BLAZE_ROD, Items.FEATHER, Items.CHICKEN,
            Items.COOKED_CHICKEN, Items.ROTTEN_FLESH, Items.ZOMBIE_HEAD, Items.GUNPOWDER, Items.CREEPER_HEAD,
            Items.TOTEM_OF_UNDYING, Items.EMERALD, Items.PORKCHOP, Items.COOKED_PORKCHOP, Items.LEATHER,
            Items.MAGMA_CREAM, Items.PHANTOM_MEMBRANE, Items.ARROW, Items.SADDLE, Items.SHULKER_SHELL, Items.BONE,
            Items.SKELETON_SKULL, Items.SLIME_BALL, Items.STRING, Items.SPIDER_EYE, Items.SCULK_CATALYST,
            Items.GLASS_BOTTLE, Items.GLOWSTONE_DUST, Items.REDSTONE, Items.STICK, Items.SUGAR, Items.POTION,
            Items.NETHER_STAR, Items.COAL, Items.WITHER_SKELETON_SKULL, Items.GHAST_TEAR, Items.IRON_INGOT,
            Items.CARROT, Items.POTATO, Items.BAKED_POTATO, Items.COPPER_INGOT};
    public static final Item[] DIRTS = new Item[]{Items.DIRT, Items.DIRT_PATH, Items.COARSE_DIRT, Items.ROOTED_DIRT};
    public static final Item[] PLANKS = new Item[]{Items.ACACIA_PLANKS, Items.BIRCH_PLANKS, Items.CRIMSON_PLANKS,
            Items.DARK_OAK_PLANKS, Items.OAK_PLANKS, Items.JUNGLE_PLANKS, Items.SPRUCE_PLANKS, Items.WARPED_PLANKS,
            Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS, Items.PALE_OAK_PLANKS, Items.POPLAR_PLANKS, Items.BAMBOO_PLANKS};
    public static final Item[] LEAVES = new Item[]{Items.ACACIA_LEAVES, Items.BIRCH_LEAVES, Items.DARK_OAK_LEAVES,
            Items.OAK_LEAVES, Items.JUNGLE_LEAVES, Items.SPRUCE_LEAVES, Items.MANGROVE_LEAVES, Items.CHERRY_LEAVES, Items.PALE_OAK_LEAVES, Items.ORANGE_POPLAR_LEAVES, Items.RED_POPLAR_LEAVES, Items.YELLOW_POPLAR_LEAVES};
    public static final Item[] WOOD = new Item[]{Items.ACACIA_WOOD, Items.BIRCH_WOOD, Items.CRIMSON_HYPHAE,
            Items.DARK_OAK_WOOD, Items.OAK_WOOD, Items.JUNGLE_WOOD, Items.SPRUCE_WOOD, Items.WARPED_HYPHAE,
            Items.MANGROVE_WOOD};
    public static final Item[] WOOD_BUTTON = new Item[]{Items.ACACIA_BUTTON, Items.BIRCH_BUTTON, Items.CRIMSON_BUTTON,
            Items.DARK_OAK_BUTTON, Items.OAK_BUTTON, Items.JUNGLE_BUTTON, Items.SPRUCE_BUTTON, Items.WARPED_BUTTON,
            Items.MANGROVE_BUTTON, Items.BAMBOO_BUTTON, Items.CHERRY_BUTTON, Items.PALE_OAK_BUTTON, Items.POPLAR_BUTTON};
    public static final Item[] WOOD_SIGN = new Item[]{Items.ACACIA_SIGN, Items.BIRCH_SIGN, Items.CRIMSON_SIGN,
            Items.DARK_OAK_SIGN, Items.OAK_SIGN, Items.JUNGLE_SIGN, Items.SPRUCE_SIGN, Items.WARPED_SIGN,
            Items.MANGROVE_SIGN, Items.BAMBOO_SIGN, Items.CHERRY_SIGN, Items.PALE_OAK_SIGN, Items.POPLAR_SIGN};
    public static final Item[] WOOD_HANGING_SIGN = new Item[]{Items.ACACIA_HANGING_SIGN, Items.BIRCH_HANGING_SIGN,
            Items.CRIMSON_HANGING_SIGN, Items.DARK_OAK_HANGING_SIGN, Items.OAK_HANGING_SIGN, Items.JUNGLE_HANGING_SIGN,
            Items.SPRUCE_HANGING_SIGN, Items.WARPED_HANGING_SIGN, Items.MANGROVE_HANGING_SIGN, Items.BAMBOO_HANGING_SIGN,
            Items.CHERRY_HANGING_SIGN, Items.PALE_OAK_HANGING_SIGN, Items.POPLAR_HANGING_SIGN};
    public static final Item[] WOOD_PRESSURE_PLATE = new Item[]{Items.ACACIA_PRESSURE_PLATE, Items.BIRCH_PRESSURE_PLATE,
            Items.CRIMSON_PRESSURE_PLATE, Items.DARK_OAK_PRESSURE_PLATE, Items.OAK_PRESSURE_PLATE,
            Items.JUNGLE_PRESSURE_PLATE, Items.SPRUCE_PRESSURE_PLATE, Items.WARPED_PRESSURE_PLATE,
            Items.MANGROVE_PRESSURE_PLATE, Items.BAMBOO_PRESSURE_PLATE, Items.CHERRY_PRESSURE_PLATE, Items.PALE_OAK_PRESSURE_PLATE, Items.POPLAR_PRESSURE_PLATE};
    public static final Item[] WOOD_FENCE = new Item[]{Items.ACACIA_FENCE, Items.BIRCH_FENCE, Items.DARK_OAK_FENCE,
            Items.OAK_FENCE, Items.JUNGLE_FENCE, Items.SPRUCE_FENCE, Items.CRIMSON_FENCE, Items.WARPED_FENCE,
            Items.MANGROVE_FENCE, Items.BAMBOO_FENCE, Items.CHERRY_FENCE, Items.PALE_OAK_FENCE, Items.POPLAR_FENCE};
    public static final Item[] WOOD_FENCE_GATE = new Item[]{Items.ACACIA_FENCE_GATE, Items.BIRCH_FENCE_GATE,
            Items.DARK_OAK_FENCE_GATE, Items.OAK_FENCE_GATE, Items.JUNGLE_FENCE_GATE, Items.SPRUCE_FENCE_GATE,
            Items.CRIMSON_FENCE_GATE, Items.WARPED_FENCE_GATE, Items.MANGROVE_FENCE_GATE, Items.BAMBOO_FENCE_GATE,
            Items.CHERRY_FENCE_GATE, Items.PALE_OAK_FENCE_GATE, Items.POPLAR_FENCE_GATE};
    public static final Item[] WOOD_BOAT = new Item[]{Items.ACACIA_BOAT, Items.BIRCH_BOAT, Items.DARK_OAK_BOAT,
            Items.OAK_BOAT, Items.JUNGLE_BOAT, Items.SPRUCE_BOAT, Items.MANGROVE_BOAT, Items.CHERRY_BOAT, Items.PALE_OAK_BOAT, Items.POPLAR_BOAT};
    public static final Item[] WOOD_DOOR = new Item[]{Items.ACACIA_DOOR, Items.BIRCH_DOOR, Items.CRIMSON_DOOR,
            Items.DARK_OAK_DOOR, Items.OAK_DOOR, Items.JUNGLE_DOOR, Items.SPRUCE_DOOR, Items.WARPED_DOOR,
            Items.MANGROVE_DOOR, Items.BAMBOO_DOOR, Items.CHERRY_DOOR, Items.PALE_OAK_DOOR, Items.POPLAR_DOOR};
    public static final Item[] WOOD_SLAB = new Item[]{Items.ACACIA_SLAB, Items.BIRCH_SLAB, Items.CRIMSON_SLAB,
            Items.DARK_OAK_SLAB, Items.OAK_SLAB, Items.JUNGLE_SLAB, Items.SPRUCE_SLAB, Items.WARPED_SLAB,
            Items.MANGROVE_SLAB, Items.BAMBOO_SLAB, Items.CHERRY_SLAB, Items.PALE_OAK_SLAB, Items.POPLAR_SLAB};
    public static final Item[] WOOD_STAIRS = new Item[]{Items.ACACIA_STAIRS, Items.BIRCH_STAIRS, Items.CRIMSON_STAIRS,
            Items.DARK_OAK_STAIRS, Items.OAK_STAIRS, Items.JUNGLE_STAIRS, Items.SPRUCE_STAIRS, Items.WARPED_STAIRS,
            Items.MANGROVE_STAIRS, Items.BAMBOO_STAIRS, Items.CHERRY_STAIRS, Items.PALE_OAK_STAIRS, Items.POPLAR_STAIRS};
    public static final Item[] WOOD_TRAPDOOR = new Item[]{Items.ACACIA_TRAPDOOR, Items.BIRCH_TRAPDOOR,
            Items.CRIMSON_TRAPDOOR, Items.DARK_OAK_TRAPDOOR, Items.OAK_TRAPDOOR, Items.JUNGLE_TRAPDOOR,
            Items.SPRUCE_TRAPDOOR, Items.WARPED_TRAPDOOR, Items.MANGROVE_TRAPDOOR, Items.BAMBOO_TRAPDOOR,
            Items.CHERRY_TRAPDOOR, Items.PALE_OAK_TRAPDOOR, Items.POPLAR_TRAPDOOR};
    public static final Item[] LOG = new Item[]{Items.ACACIA_LOG, Items.BIRCH_LOG, Items.DARK_OAK_LOG, Items.OAK_LOG, Items.JUNGLE_LOG, Items.SPRUCE_LOG,
            Items.ACACIA_WOOD, Items.BIRCH_WOOD, Items.DARK_OAK_WOOD, Items.OAK_WOOD, Items.JUNGLE_WOOD, Items.SPRUCE_WOOD,
            Items.STRIPPED_ACACIA_LOG, Items.STRIPPED_BIRCH_LOG, Items.STRIPPED_DARK_OAK_LOG, Items.STRIPPED_OAK_LOG, Items.STRIPPED_JUNGLE_LOG, Items.STRIPPED_SPRUCE_LOG,
            Items.STRIPPED_ACACIA_WOOD, Items.STRIPPED_BIRCH_WOOD, Items.STRIPPED_DARK_OAK_WOOD, Items.STRIPPED_OAK_WOOD, Items.STRIPPED_JUNGLE_WOOD, Items.STRIPPED_SPRUCE_WOOD,
            Items.CRIMSON_STEM, Items.WARPED_STEM, Items.CRIMSON_HYPHAE, Items.WARPED_HYPHAE,
            Items.STRIPPED_CRIMSON_STEM, Items.STRIPPED_WARPED_STEM, Items.STRIPPED_CRIMSON_HYPHAE,
            Items.STRIPPED_WARPED_HYPHAE, Items.MANGROVE_LOG, Items.MANGROVE_WOOD, Items.STRIPPED_MANGROVE_LOG,
            Items.STRIPPED_MANGROVE_WOOD, Items.CHERRY_LOG, Items.PALE_OAK_LOG, Items.POPLAR_LOG, Items.CHERRY_WOOD, Items.PALE_OAK_WOOD, Items.POPLAR_WOOD, Items.STRIPPED_CHERRY_LOG, Items.STRIPPED_PALE_OAK_LOG, Items.STRIPPED_POPLAR_LOG,
            Items.STRIPPED_CHERRY_WOOD, Items.STRIPPED_PALE_OAK_WOOD, Items.STRIPPED_POPLAR_WOOD};
    public static final Item[] STRIPPED_LOGS = new Item[]{Items.STRIPPED_ACACIA_LOG, Items.STRIPPED_BIRCH_LOG,
            Items.STRIPPED_DARK_OAK_LOG, Items.STRIPPED_OAK_LOG, Items.STRIPPED_JUNGLE_LOG, Items.STRIPPED_SPRUCE_LOG,
            Items.STRIPPED_CRIMSON_STEM, Items.STRIPPED_WARPED_STEM, Items.STRIPPED_MANGROVE_LOG,
            Items.STRIPPED_CHERRY_LOG, Items.STRIPPED_PALE_OAK_LOG, Items.STRIPPED_POPLAR_LOG};
    public static final Item[] STRIPPABLE_LOGS = new Item[]{Items.ACACIA_LOG, Items.BIRCH_LOG, Items.DARK_OAK_LOG,
            Items.OAK_LOG, Items.JUNGLE_LOG, Items.SPRUCE_LOG, Items.CRIMSON_STEM, Items.WARPED_STEM, Items.MANGROVE_LOG,
            Items.CHERRY_LOG, Items.PALE_OAK_LOG, Items.POPLAR_LOG};
    public static final Item[] DYE = new Item[]{Items.DYE.white(), Items.DYE.black(), Items.DYE.blue(), Items.DYE.brown(), Items.DYE.cyan(), Items.DYE.gray(), Items.DYE.green(), Items.DYE.lightBlue(), Items.DYE.lightGray(), Items.DYE.lime(), Items.DYE.magenta(), Items.DYE.orange(), Items.DYE.pink(), Items.DYE.purple(), Items.DYE.red(), Items.DYE.yellow()};
    public static final Item[] WOOL = new Item[]{Items.WOOL.white(), Items.WOOL.black(), Items.WOOL.blue(), Items.WOOL.brown(), Items.WOOL.cyan(), Items.WOOL.gray(), Items.WOOL.green(), Items.WOOL.lightBlue(), Items.WOOL.lightGray(), Items.WOOL.lime(), Items.WOOL.magenta(), Items.WOOL.orange(), Items.WOOL.pink(), Items.WOOL.purple(), Items.WOOL.red(), Items.WOOL.yellow()};
    public static final Item[] BED = new Item[]{Items.BED.white(), Items.BED.black(), Items.BED.blue(), Items.BED.brown(), Items.BED.cyan(), Items.BED.gray(), Items.BED.green(), Items.BED.lightBlue(), Items.BED.lightGray(), Items.BED.lime(), Items.BED.magenta(), Items.BED.orange(), Items.BED.pink(), Items.BED.purple(), Items.BED.red(), Items.BED.yellow()};
    public static final Item[] CARPET = new Item[]{Items.CARPET.white(), Items.CARPET.black(), Items.CARPET.blue(), Items.CARPET.brown(), Items.CARPET.cyan(), Items.CARPET.gray(), Items.CARPET.green(), Items.CARPET.lightBlue(), Items.CARPET.lightGray(), Items.CARPET.lime(), Items.CARPET.magenta(), Items.CARPET.orange(), Items.CARPET.pink(), Items.CARPET.purple(), Items.CARPET.red(), Items.CARPET.yellow()};
    public static final Item[] SHULKER_BOXES = new Item[]{Items.DYED_SHULKER_BOX.white(), Items.DYED_SHULKER_BOX.black(), Items.DYED_SHULKER_BOX.blue(), Items.DYED_SHULKER_BOX.brown(), Items.DYED_SHULKER_BOX.cyan(), Items.DYED_SHULKER_BOX.gray(), Items.DYED_SHULKER_BOX.green(), Items.DYED_SHULKER_BOX.lightBlue(), Items.DYED_SHULKER_BOX.lightGray(), Items.DYED_SHULKER_BOX.lime(), Items.DYED_SHULKER_BOX.magenta(), Items.DYED_SHULKER_BOX.orange(), Items.DYED_SHULKER_BOX.pink(), Items.DYED_SHULKER_BOX.purple(), Items.DYED_SHULKER_BOX.red(), Items.DYED_SHULKER_BOX.yellow()};
    public static final Item[] FLOWER = new Item[]{Items.ALLIUM, Items.AZURE_BLUET, Items.BLUE_ORCHID, Items.CORNFLOWER, Items.DANDELION, Items.LILAC, Items.LILY_OF_THE_VALLEY, Items.ORANGE_TULIP, Items.OXEYE_DAISY, Items.PINK_TULIP, Items.POPPY, Items.PEONY, Items.RED_TULIP, Items.ROSE_BUSH, Items.SUNFLOWER, Items.WHITE_TULIP};
    // what vanilla's suspicious_stew_from_* recipes take (the small_flowers tag). FLOWER above is wider: it has lilac, peony, rose bush and sunflower
    public static final Item[] SUSPICIOUS_STEW_FLOWERS = new Item[]{Items.ALLIUM, Items.AZURE_BLUET, Items.BLUE_ORCHID, Items.CLOSED_EYEBLOSSOM, Items.CORNFLOWER, Items.DANDELION,
            Items.LILY_OF_THE_VALLEY, Items.OPEN_EYEBLOSSOM, Items.ORANGE_TULIP, Items.OXEYE_DAISY, Items.PINK_TULIP, Items.POPPY, Items.RED_TULIP, Items.TORCHFLOWER,
            Items.WHITE_TULIP, Items.WITHER_ROSE};
    public static final Item[] LEATHER_ARMORS =new Item[]{Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_HELMET, Items.LEATHER_BOOTS};
    public static final Item[] GOLDEN_ARMORS = new Item[]{Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_HELMET, Items.GOLDEN_BOOTS};
    public static final Item[] IRON_ARMORS = new Item[]{Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_HELMET, Items.IRON_BOOTS};
    public static final Item[] DIAMOND_ARMORS = new Item[]{Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_HELMET, Items.DIAMOND_BOOTS};
    public static final Item[] NETHERITE_ARMORS = new Item[]{Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_HELMET, Items.NETHERITE_BOOTS};
    public static final Item[] WOODEN_TOOLS = new Item[]{Items.WOODEN_PICKAXE, Items.WOODEN_SHOVEL, Items.WOODEN_SWORD, Items.WOODEN_AXE, Items.WOODEN_HOE};
    public static final Item[] STONE_TOOLS = new Item[]{Items.STONE_PICKAXE, Items.STONE_SHOVEL, Items.STONE_SWORD, Items.STONE_AXE, Items.STONE_HOE};
    public static final Item[] IRON_TOOLS = new Item[]{Items.IRON_PICKAXE, Items.IRON_SHOVEL, Items.IRON_SWORD, Items.IRON_AXE, Items.IRON_HOE};
    public static final Item[] GOLDEN_TOOLS = new Item[]{Items.GOLDEN_PICKAXE, Items.GOLDEN_SHOVEL, Items.GOLDEN_SWORD, Items.GOLDEN_AXE, Items.GOLDEN_HOE};
    public static final Item[] DIAMOND_TOOLS = new Item[]{Items.DIAMOND_PICKAXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_SWORD, Items.DIAMOND_AXE, Items.DIAMOND_HOE};
    public static final Item[] NETHERITE_TOOLS = new Item[]{Items.NETHERITE_PICKAXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_SWORD, Items.NETHERITE_AXE, Items.NETHERITE_HOE};
    public static final Block[] WOOD_SIGNS_ALL = new Block[]{Blocks.ACACIA_SIGN, Blocks.BIRCH_SIGN, Blocks.DARK_OAK_SIGN,
            Blocks.OAK_SIGN, Blocks.JUNGLE_SIGN, Blocks.SPRUCE_SIGN, Blocks.ACACIA_WALL_SIGN, Blocks.BIRCH_WALL_SIGN,
            Blocks.DARK_OAK_WALL_SIGN, Blocks.OAK_WALL_SIGN, Blocks.JUNGLE_WALL_SIGN, Blocks.SPRUCE_WALL_SIGN,
            Blocks.MANGROVE_SIGN, Blocks.MANGROVE_WALL_SIGN, Blocks.BAMBOO_SIGN, Blocks.BAMBOO_WALL_SIGN,
            Blocks.CHERRY_SIGN, Blocks.PALE_OAK_SIGN, Blocks.POPLAR_SIGN, Blocks.CHERRY_WALL_SIGN, Blocks.PALE_OAK_WALL_SIGN, Blocks.POPLAR_WALL_SIGN,
            Blocks.CRIMSON_SIGN, Blocks.CRIMSON_WALL_SIGN, Blocks.WARPED_SIGN, Blocks.WARPED_WALL_SIGN};
    public static final Block[] WOOD_HANGING_SIGNS_ALL = new Block[]{Blocks.ACACIA_HANGING_SIGN,
            Blocks.ACACIA_WALL_HANGING_SIGN, Blocks.BIRCH_HANGING_SIGN, Blocks.BIRCH_WALL_HANGING_SIGN,
            Blocks.DARK_OAK_HANGING_SIGN, Blocks.DARK_OAK_WALL_HANGING_SIGN, Blocks.OAK_HANGING_SIGN,
            Blocks.OAK_WALL_HANGING_SIGN, Blocks.JUNGLE_HANGING_SIGN, Blocks.JUNGLE_WALL_HANGING_SIGN,
            Blocks.SPRUCE_HANGING_SIGN, Blocks.SPRUCE_WALL_HANGING_SIGN, Blocks.MANGROVE_HANGING_SIGN,
            Blocks.MANGROVE_WALL_HANGING_SIGN, Blocks.BAMBOO_HANGING_SIGN, Blocks.BAMBOO_WALL_HANGING_SIGN,
            Blocks.CHERRY_HANGING_SIGN, Blocks.PALE_OAK_HANGING_SIGN, Blocks.POPLAR_HANGING_SIGN, Blocks.CHERRY_WALL_HANGING_SIGN, Blocks.PALE_OAK_WALL_HANGING_SIGN, Blocks.POPLAR_WALL_HANGING_SIGN,
            Blocks.CRIMSON_HANGING_SIGN, Blocks.CRIMSON_WALL_HANGING_SIGN, Blocks.WARPED_HANGING_SIGN, Blocks.WARPED_WALL_HANGING_SIGN};
    private static final Map<Item, Item> _logToPlanks = new HashMap<>() {
        {
            put(Items.CHERRY_LOG, Items.CHERRY_PLANKS);
            put(Items.PALE_OAK_LOG, Items.PALE_OAK_PLANKS);
            put(Items.POPLAR_LOG, Items.POPLAR_PLANKS);
            put(Items.CHERRY_WOOD, Items.CHERRY_PLANKS);
            put(Items.PALE_OAK_WOOD, Items.PALE_OAK_PLANKS);
            put(Items.POPLAR_WOOD, Items.POPLAR_PLANKS);
            put(Items.STRIPPED_CHERRY_LOG, Items.CHERRY_PLANKS);
            put(Items.STRIPPED_PALE_OAK_LOG, Items.PALE_OAK_PLANKS);
            put(Items.STRIPPED_POPLAR_LOG, Items.POPLAR_PLANKS);
            put(Items.STRIPPED_CHERRY_WOOD, Items.CHERRY_PLANKS);
            put(Items.STRIPPED_PALE_OAK_WOOD, Items.PALE_OAK_PLANKS);
            put(Items.STRIPPED_POPLAR_WOOD, Items.POPLAR_PLANKS);
            put(Items.MANGROVE_LOG, Items.MANGROVE_PLANKS);
            put(Items.MANGROVE_WOOD, Items.MANGROVE_PLANKS);
            put(Items.STRIPPED_MANGROVE_LOG, Items.MANGROVE_PLANKS);
            put(Items.STRIPPED_MANGROVE_WOOD, Items.MANGROVE_PLANKS);
            put(Items.ACACIA_LOG, Items.ACACIA_PLANKS);
            put(Items.BIRCH_LOG, Items.BIRCH_PLANKS);
            put(Items.CRIMSON_STEM, Items.CRIMSON_PLANKS);
            put(Items.DARK_OAK_LOG, Items.DARK_OAK_PLANKS);
            put(Items.OAK_LOG, Items.OAK_PLANKS);
            put(Items.JUNGLE_LOG, Items.JUNGLE_PLANKS);
            put(Items.SPRUCE_LOG, Items.SPRUCE_PLANKS);
            put(Items.WARPED_STEM, Items.WARPED_PLANKS);
            put(Items.STRIPPED_ACACIA_LOG, Items.ACACIA_PLANKS);
            put(Items.STRIPPED_BIRCH_LOG, Items.BIRCH_PLANKS);
            put(Items.STRIPPED_CRIMSON_STEM, Items.CRIMSON_PLANKS);
            put(Items.STRIPPED_DARK_OAK_LOG, Items.DARK_OAK_PLANKS);
            put(Items.STRIPPED_OAK_LOG, Items.OAK_PLANKS);
            put(Items.STRIPPED_JUNGLE_LOG, Items.JUNGLE_PLANKS);
            put(Items.STRIPPED_SPRUCE_LOG, Items.SPRUCE_PLANKS);
            put(Items.STRIPPED_WARPED_STEM, Items.WARPED_PLANKS);
            put(Items.ACACIA_WOOD, Items.ACACIA_PLANKS);
            put(Items.BIRCH_WOOD, Items.BIRCH_PLANKS);
            put(Items.CRIMSON_HYPHAE, Items.CRIMSON_PLANKS);
            put(Items.DARK_OAK_WOOD, Items.DARK_OAK_PLANKS);
            put(Items.OAK_WOOD, Items.OAK_PLANKS);
            put(Items.JUNGLE_WOOD, Items.JUNGLE_PLANKS);
            put(Items.SPRUCE_WOOD, Items.SPRUCE_PLANKS);
            put(Items.WARPED_HYPHAE, Items.WARPED_PLANKS);
            put(Items.STRIPPED_ACACIA_WOOD, Items.ACACIA_PLANKS);
            put(Items.STRIPPED_BIRCH_WOOD, Items.BIRCH_PLANKS);
            put(Items.STRIPPED_CRIMSON_HYPHAE, Items.CRIMSON_PLANKS);
            put(Items.STRIPPED_DARK_OAK_WOOD, Items.DARK_OAK_PLANKS);
            put(Items.STRIPPED_OAK_WOOD, Items.OAK_PLANKS);
            put(Items.STRIPPED_JUNGLE_WOOD, Items.JUNGLE_PLANKS);
            put(Items.STRIPPED_SPRUCE_WOOD, Items.SPRUCE_PLANKS);
            put(Items.STRIPPED_WARPED_HYPHAE, Items.WARPED_PLANKS);
        }
    };
    private static final Map<Item, Item> _planksToLogs = new HashMap<>() {
        {
            put(Items.CHERRY_PLANKS, Items.CHERRY_LOG);
            put(Items.PALE_OAK_PLANKS, Items.PALE_OAK_LOG);
            put(Items.POPLAR_PLANKS, Items.POPLAR_LOG);
            put(Items.MANGROVE_PLANKS, Items.MANGROVE_LOG);
            put(Items.ACACIA_PLANKS, Items.ACACIA_LOG);
            put(Items.BIRCH_PLANKS, Items.BIRCH_LOG);
            put(Items.CRIMSON_PLANKS, Items.CRIMSON_STEM);
            put(Items.DARK_OAK_PLANKS, Items.DARK_OAK_LOG);
            put(Items.OAK_PLANKS, Items.OAK_LOG);
            put(Items.JUNGLE_PLANKS, Items.JUNGLE_LOG);
            put(Items.SPRUCE_PLANKS, Items.SPRUCE_LOG);
            put(Items.WARPED_PLANKS, Items.WARPED_STEM);
        }
    };
    private static final Map<Item, Item> _strippedToLogs = new HashMap<>() {
        {
            put(Items.STRIPPED_CHERRY_LOG, Items.CHERRY_LOG);
            put(Items.STRIPPED_PALE_OAK_LOG, Items.PALE_OAK_LOG);
            put(Items.STRIPPED_POPLAR_LOG, Items.POPLAR_LOG);
            put(Items.STRIPPED_MANGROVE_LOG, Items.MANGROVE_LOG);
            put(Items.STRIPPED_ACACIA_LOG, Items.ACACIA_LOG);
            put(Items.STRIPPED_BIRCH_LOG, Items.BIRCH_LOG);
            put(Items.STRIPPED_CRIMSON_STEM, Items.CRIMSON_STEM);
            put(Items.STRIPPED_DARK_OAK_LOG, Items.DARK_OAK_LOG);
            put(Items.STRIPPED_OAK_LOG, Items.OAK_LOG);
            put(Items.STRIPPED_JUNGLE_LOG, Items.JUNGLE_LOG);
            put(Items.STRIPPED_SPRUCE_LOG, Items.SPRUCE_LOG);
            put(Items.STRIPPED_WARPED_STEM, Items.WARPED_STEM);
        }
    };
    // This is kinda jank ngl
    private static final Map<MapColor, ColorfulItems> _colorMap = new HashMap<MapColor, ColorfulItems>() {
        {
            p(DyeColor.RED, "red", Items.DYE.red(), Items.WOOL.red(), Items.BED.red(), Items.CARPET.red(), Items.STAINED_GLASS.red(), Items.STAINED_GLASS_PANE.red(), Items.DYED_TERRACOTTA.red(), Items.GLAZED_TERRACOTTA.red(), Items.CONCRETE.red(), Items.CONCRETE_POWDER.red(), Items.BANNER.red(), Items.DYED_SHULKER_BOX.red(), Blocks.WALL_BANNER.red());
            p(DyeColor.WHITE, "white", Items.DYE.white(), Items.WOOL.white(), Items.BED.white(), Items.CARPET.white(), Items.STAINED_GLASS.white(), Items.STAINED_GLASS_PANE.white(), Items.DYED_TERRACOTTA.white(), Items.GLAZED_TERRACOTTA.white(), Items.CONCRETE.white(), Items.CONCRETE_POWDER.white(), Items.BANNER.white(), Items.DYED_SHULKER_BOX.white(), Blocks.WALL_BANNER.white());
            p(DyeColor.BLACK, "black", Items.DYE.black(), Items.WOOL.black(), Items.BED.black(), Items.CARPET.black(), Items.STAINED_GLASS.black(), Items.STAINED_GLASS_PANE.black(), Items.DYED_TERRACOTTA.black(), Items.GLAZED_TERRACOTTA.black(), Items.CONCRETE.black(), Items.CONCRETE_POWDER.black(), Items.BANNER.black(), Items.DYED_SHULKER_BOX.black(), Blocks.WALL_BANNER.black());
            p(DyeColor.BLUE, "blue", Items.DYE.blue(), Items.WOOL.blue(), Items.BED.blue(), Items.CARPET.blue(), Items.STAINED_GLASS.blue(), Items.STAINED_GLASS_PANE.blue(), Items.DYED_TERRACOTTA.blue(), Items.GLAZED_TERRACOTTA.blue(), Items.CONCRETE.blue(), Items.CONCRETE_POWDER.blue(), Items.BANNER.blue(), Items.DYED_SHULKER_BOX.blue(), Blocks.WALL_BANNER.blue());
            p(DyeColor.BROWN, "brown", Items.DYE.brown(), Items.WOOL.brown(), Items.BED.brown(), Items.CARPET.brown(), Items.STAINED_GLASS.brown(), Items.STAINED_GLASS_PANE.brown(), Items.DYED_TERRACOTTA.brown(), Items.GLAZED_TERRACOTTA.brown(), Items.CONCRETE.brown(), Items.CONCRETE_POWDER.brown(), Items.BANNER.brown(), Items.DYED_SHULKER_BOX.brown(), Blocks.WALL_BANNER.brown());
            p(DyeColor.CYAN, "cyan", Items.DYE.cyan(), Items.WOOL.cyan(), Items.BED.cyan(), Items.CARPET.cyan(), Items.STAINED_GLASS.cyan(), Items.STAINED_GLASS_PANE.cyan(), Items.DYED_TERRACOTTA.cyan(), Items.GLAZED_TERRACOTTA.cyan(), Items.CONCRETE.cyan(), Items.CONCRETE_POWDER.cyan(), Items.BANNER.cyan(), Items.DYED_SHULKER_BOX.cyan(), Blocks.WALL_BANNER.cyan());
            p(DyeColor.GRAY, "gray", Items.DYE.gray(), Items.WOOL.gray(), Items.BED.gray(), Items.CARPET.gray(), Items.STAINED_GLASS.gray(), Items.STAINED_GLASS_PANE.gray(), Items.DYED_TERRACOTTA.gray(), Items.GLAZED_TERRACOTTA.gray(), Items.CONCRETE.gray(), Items.CONCRETE_POWDER.gray(), Items.BANNER.gray(), Items.DYED_SHULKER_BOX.gray(), Blocks.WALL_BANNER.gray());
            p(DyeColor.GREEN, "green", Items.DYE.green(), Items.WOOL.green(), Items.BED.green(), Items.CARPET.green(), Items.STAINED_GLASS.green(), Items.STAINED_GLASS_PANE.green(), Items.DYED_TERRACOTTA.green(), Items.GLAZED_TERRACOTTA.green(), Items.CONCRETE.green(), Items.CONCRETE_POWDER.green(), Items.BANNER.green(), Items.DYED_SHULKER_BOX.green(), Blocks.WALL_BANNER.green());
            p(DyeColor.LIGHT_BLUE, "light_blue", Items.DYE.lightBlue(), Items.WOOL.lightBlue(), Items.BED.lightBlue(), Items.CARPET.lightBlue(), Items.STAINED_GLASS.lightBlue(), Items.STAINED_GLASS_PANE.lightBlue(), Items.DYED_TERRACOTTA.lightBlue(), Items.GLAZED_TERRACOTTA.lightBlue(), Items.CONCRETE.lightBlue(), Items.CONCRETE_POWDER.lightBlue(), Items.BANNER.lightBlue(), Items.DYED_SHULKER_BOX.lightBlue(), Blocks.WALL_BANNER.lightBlue());
            p(DyeColor.LIGHT_GRAY, "light_gray", Items.DYE.lightGray(), Items.WOOL.lightGray(), Items.BED.lightGray(), Items.CARPET.lightGray(), Items.STAINED_GLASS.lightGray(), Items.STAINED_GLASS_PANE.lightGray(), Items.DYED_TERRACOTTA.lightGray(), Items.GLAZED_TERRACOTTA.lightGray(), Items.CONCRETE.lightGray(), Items.CONCRETE_POWDER.lightGray(), Items.BANNER.lightGray(), Items.DYED_SHULKER_BOX.lightGray(), Blocks.WALL_BANNER.lightGray());
            p(DyeColor.LIME, "lime", Items.DYE.lime(), Items.WOOL.lime(), Items.BED.lime(), Items.CARPET.lime(), Items.STAINED_GLASS.lime(), Items.STAINED_GLASS_PANE.lime(), Items.DYED_TERRACOTTA.lime(), Items.GLAZED_TERRACOTTA.lime(), Items.CONCRETE.lime(), Items.CONCRETE_POWDER.lime(), Items.BANNER.lime(), Items.DYED_SHULKER_BOX.lime(), Blocks.WALL_BANNER.lime());
            p(DyeColor.MAGENTA, "magenta", Items.DYE.magenta(), Items.WOOL.magenta(), Items.BED.magenta(), Items.CARPET.magenta(), Items.STAINED_GLASS.magenta(), Items.STAINED_GLASS_PANE.magenta(), Items.DYED_TERRACOTTA.magenta(), Items.GLAZED_TERRACOTTA.magenta(), Items.CONCRETE.magenta(), Items.CONCRETE_POWDER.magenta(), Items.BANNER.magenta(), Items.DYED_SHULKER_BOX.magenta(), Blocks.WALL_BANNER.magenta());
            p(DyeColor.ORANGE, "orange", Items.DYE.orange(), Items.WOOL.orange(), Items.BED.orange(), Items.CARPET.orange(), Items.STAINED_GLASS.orange(), Items.STAINED_GLASS_PANE.orange(), Items.DYED_TERRACOTTA.orange(), Items.GLAZED_TERRACOTTA.orange(), Items.CONCRETE.orange(), Items.CONCRETE_POWDER.orange(), Items.BANNER.orange(), Items.DYED_SHULKER_BOX.orange(), Blocks.WALL_BANNER.orange());
            p(DyeColor.PINK, "pink", Items.DYE.pink(), Items.WOOL.pink(), Items.BED.pink(), Items.CARPET.pink(), Items.STAINED_GLASS.pink(), Items.STAINED_GLASS_PANE.pink(), Items.DYED_TERRACOTTA.pink(), Items.GLAZED_TERRACOTTA.pink(), Items.CONCRETE.pink(), Items.CONCRETE_POWDER.pink(), Items.BANNER.pink(), Items.DYED_SHULKER_BOX.pink(), Blocks.WALL_BANNER.pink());
            p(DyeColor.PURPLE, "purple", Items.DYE.purple(), Items.WOOL.purple(), Items.BED.purple(), Items.CARPET.purple(), Items.STAINED_GLASS.purple(), Items.STAINED_GLASS_PANE.purple(), Items.DYED_TERRACOTTA.purple(), Items.GLAZED_TERRACOTTA.purple(), Items.CONCRETE.purple(), Items.CONCRETE_POWDER.purple(), Items.BANNER.purple(), Items.DYED_SHULKER_BOX.purple(), Blocks.WALL_BANNER.purple());
            p(DyeColor.RED, "red", Items.DYE.red(), Items.WOOL.red(), Items.BED.red(), Items.CARPET.red(), Items.STAINED_GLASS.red(), Items.STAINED_GLASS_PANE.red(), Items.DYED_TERRACOTTA.red(), Items.GLAZED_TERRACOTTA.red(), Items.CONCRETE.red(), Items.CONCRETE_POWDER.red(), Items.BANNER.red(), Items.DYED_SHULKER_BOX.red(), Blocks.WALL_BANNER.red());
            p(DyeColor.YELLOW, "yellow", Items.DYE.yellow(), Items.WOOL.yellow(), Items.BED.yellow(), Items.CARPET.yellow(), Items.STAINED_GLASS.yellow(), Items.STAINED_GLASS_PANE.yellow(), Items.DYED_TERRACOTTA.yellow(), Items.GLAZED_TERRACOTTA.yellow(), Items.CONCRETE.yellow(), Items.CONCRETE_POWDER.yellow(), Items.BANNER.yellow(), Items.DYED_SHULKER_BOX.yellow(), Blocks.WALL_BANNER.yellow());
        }

        void p(DyeColor color, String colorName, Item dye, Item wool, Item bed, Item carpet, Item stainedGlass, Item stainedGlassPane, Item terracotta, Item glazedTerracotta, Item concrete, Item concretePowder, Item banner, Item shulker, Block wallBanner) {
            put(color.getMapColor(), new ColorfulItems(color, colorName, dye, wool, bed, carpet, stainedGlass, stainedGlassPane, terracotta, glazedTerracotta, concrete, concretePowder, banner, shulker, wallBanner));
        }
    };
    private static final Map<WoodType, WoodItems> _woodMap = new HashMap<WoodType, WoodItems>() {
        {
            p(WoodType.CHERRY, "cherry", Items.CHERRY_PLANKS, Items.CHERRY_LOG, Items.STRIPPED_CHERRY_LOG, Items.STRIPPED_CHERRY_WOOD, Items.CHERRY_WOOD, Items.CHERRY_SIGN, Items.CHERRY_HANGING_SIGN, Items.CHERRY_DOOR, Items.CHERRY_BUTTON, Items.CHERRY_STAIRS, Items.CHERRY_SLAB, Items.CHERRY_FENCE, Items.CHERRY_FENCE_GATE, Items.CHERRY_BOAT, Items.CHERRY_SAPLING, Items.CHERRY_LEAVES, Items.CHERRY_PRESSURE_PLATE, Items.CHERRY_TRAPDOOR);
            p(WoodType.PALE_OAK, "pale_oak", Items.PALE_OAK_PLANKS, Items.PALE_OAK_LOG, Items.STRIPPED_PALE_OAK_LOG, Items.STRIPPED_PALE_OAK_WOOD, Items.PALE_OAK_WOOD, Items.PALE_OAK_SIGN, Items.PALE_OAK_HANGING_SIGN, Items.PALE_OAK_DOOR, Items.PALE_OAK_BUTTON, Items.PALE_OAK_STAIRS, Items.PALE_OAK_SLAB, Items.PALE_OAK_FENCE, Items.PALE_OAK_FENCE_GATE, Items.PALE_OAK_BOAT, Items.PALE_OAK_SAPLING, Items.PALE_OAK_LEAVES, Items.PALE_OAK_PRESSURE_PLATE, Items.PALE_OAK_TRAPDOOR);
            p(WoodType.POPLAR, "poplar", Items.POPLAR_PLANKS, Items.POPLAR_LOG, Items.STRIPPED_POPLAR_LOG, Items.STRIPPED_POPLAR_WOOD, Items.POPLAR_WOOD, Items.POPLAR_SIGN, Items.POPLAR_HANGING_SIGN, Items.POPLAR_DOOR, Items.POPLAR_BUTTON, Items.POPLAR_STAIRS, Items.POPLAR_SLAB, Items.POPLAR_FENCE, Items.POPLAR_FENCE_GATE, Items.POPLAR_BOAT, Items.POPLAR_SAPLING, null, Items.POPLAR_PRESSURE_PLATE, Items.POPLAR_TRAPDOOR);
            p(WoodType.BAMBOO, "bamboo", null, null, Items.STRIPPED_BAMBOO_BLOCK, null, null, Items.BAMBOO_SIGN, Items.BAMBOO_HANGING_SIGN, Items.BAMBOO_DOOR, Items.BAMBOO_BUTTON, Items.BAMBOO_STAIRS, Items.BAMBOO_SLAB, Items.BAMBOO_FENCE, Items.BAMBOO_FENCE_GATE, Items.BAMBOO_RAFT, Items.BAMBOO, null, Items.BAMBOO_PRESSURE_PLATE, Items.BAMBOO_TRAPDOOR);
            p(WoodType.MANGROVE, "mangrove", Items.MANGROVE_PLANKS, Items.MANGROVE_LOG, Items.STRIPPED_MANGROVE_LOG, Items.STRIPPED_MANGROVE_WOOD, Items.MANGROVE_WOOD, Items.MANGROVE_SIGN, Items.MANGROVE_HANGING_SIGN, Items.MANGROVE_DOOR, Items.MANGROVE_BUTTON, Items.MANGROVE_STAIRS, Items.MANGROVE_SLAB, Items.MANGROVE_FENCE, Items.MANGROVE_FENCE_GATE, Items.MANGROVE_BOAT, Items.MANGROVE_PROPAGULE, Items.MANGROVE_LEAVES, Items.MANGROVE_PRESSURE_PLATE, Items.MANGROVE_TRAPDOOR);
            p(WoodType.ACACIA, "acacia", Items.ACACIA_PLANKS, Items.ACACIA_LOG, Items.STRIPPED_ACACIA_LOG, Items.STRIPPED_ACACIA_WOOD, Items.ACACIA_WOOD, Items.ACACIA_SIGN, Items.ACACIA_HANGING_SIGN, Items.ACACIA_DOOR, Items.ACACIA_BUTTON, Items.ACACIA_STAIRS, Items.ACACIA_SLAB, Items.ACACIA_FENCE, Items.ACACIA_FENCE_GATE, Items.ACACIA_BOAT, Items.ACACIA_SAPLING, Items.ACACIA_LEAVES, Items.ACACIA_PRESSURE_PLATE, Items.ACACIA_TRAPDOOR);
            p(WoodType.BIRCH, "birch", Items.BIRCH_PLANKS, Items.BIRCH_LOG, Items.STRIPPED_BIRCH_LOG, Items.STRIPPED_BIRCH_WOOD, Items.BIRCH_WOOD, Items.BIRCH_SIGN, Items.BIRCH_HANGING_SIGN, Items.BIRCH_DOOR, Items.BIRCH_BUTTON, Items.BIRCH_STAIRS, Items.BIRCH_SLAB, Items.BIRCH_FENCE, Items.BIRCH_FENCE_GATE, Items.BIRCH_BOAT, Items.BIRCH_SAPLING, Items.BIRCH_LEAVES, Items.BIRCH_PRESSURE_PLATE, Items.BIRCH_TRAPDOOR);
            p(WoodType.CRIMSON, "crimson", Items.CRIMSON_PLANKS, Items.CRIMSON_STEM, Items.STRIPPED_CRIMSON_STEM, Items.STRIPPED_CRIMSON_HYPHAE, Items.CRIMSON_HYPHAE, Items.CRIMSON_SIGN, Items.CRIMSON_HANGING_SIGN, Items.CRIMSON_DOOR, Items.CRIMSON_BUTTON, Items.CRIMSON_STAIRS, Items.CRIMSON_SLAB, Items.CRIMSON_FENCE, Items.CRIMSON_FENCE_GATE, null, Items.CRIMSON_FUNGUS, null, Items.CRIMSON_PRESSURE_PLATE, Items.CRIMSON_TRAPDOOR);
            p(WoodType.DARK_OAK, "dark_oak", Items.DARK_OAK_PLANKS, Items.DARK_OAK_LOG, Items.STRIPPED_DARK_OAK_LOG, Items.STRIPPED_DARK_OAK_WOOD, Items.DARK_OAK_WOOD, Items.DARK_OAK_SIGN, Items.DARK_OAK_HANGING_SIGN, Items.DARK_OAK_DOOR, Items.DARK_OAK_BUTTON, Items.DARK_OAK_STAIRS, Items.DARK_OAK_SLAB, Items.DARK_OAK_FENCE, Items.DARK_OAK_FENCE_GATE, Items.DARK_OAK_BOAT, Items.DARK_OAK_SAPLING, Items.DARK_OAK_LEAVES, Items.DARK_OAK_PRESSURE_PLATE, Items.DARK_OAK_TRAPDOOR);
            p(WoodType.OAK, "oak", Items.OAK_PLANKS, Items.OAK_LOG, Items.STRIPPED_OAK_LOG, Items.STRIPPED_OAK_WOOD, Items.OAK_WOOD, Items.OAK_SIGN, Items.OAK_HANGING_SIGN, Items.OAK_DOOR, Items.OAK_BUTTON, Items.OAK_STAIRS, Items.OAK_SLAB, Items.OAK_FENCE, Items.OAK_FENCE_GATE, Items.OAK_BOAT, Items.OAK_SAPLING, Items.OAK_LEAVES, Items.OAK_PRESSURE_PLATE, Items.OAK_TRAPDOOR);
            p(WoodType.JUNGLE, "jungle", Items.JUNGLE_PLANKS, Items.JUNGLE_LOG, Items.STRIPPED_JUNGLE_LOG, Items.STRIPPED_JUNGLE_WOOD, Items.JUNGLE_WOOD, Items.JUNGLE_SIGN, Items.JUNGLE_HANGING_SIGN, Items.JUNGLE_DOOR, Items.JUNGLE_BUTTON, Items.JUNGLE_STAIRS, Items.JUNGLE_SLAB, Items.JUNGLE_FENCE, Items.JUNGLE_FENCE_GATE, Items.JUNGLE_BOAT, Items.JUNGLE_SAPLING, Items.JUNGLE_LEAVES, Items.JUNGLE_PRESSURE_PLATE, Items.JUNGLE_TRAPDOOR);
            p(WoodType.SPRUCE, "spruce", Items.SPRUCE_PLANKS, Items.SPRUCE_LOG, Items.STRIPPED_SPRUCE_LOG, Items.STRIPPED_SPRUCE_WOOD, Items.SPRUCE_WOOD, Items.SPRUCE_SIGN, Items.SPRUCE_HANGING_SIGN, Items.SPRUCE_DOOR, Items.SPRUCE_BUTTON, Items.SPRUCE_STAIRS, Items.SPRUCE_SLAB, Items.SPRUCE_FENCE, Items.SPRUCE_FENCE_GATE, Items.SPRUCE_BOAT, Items.SPRUCE_SAPLING, Items.SPRUCE_LEAVES, Items.SPRUCE_PRESSURE_PLATE, Items.SPRUCE_TRAPDOOR);
            p(WoodType.WARPED, "warped", Items.WARPED_PLANKS, Items.WARPED_STEM, Items.STRIPPED_WARPED_STEM, Items.STRIPPED_WARPED_HYPHAE, Items.WARPED_HYPHAE, Items.WARPED_SIGN, Items.WARPED_HANGING_SIGN, Items.WARPED_DOOR, Items.WARPED_BUTTON, Items.WARPED_STAIRS, Items.WARPED_SLAB, Items.WARPED_FENCE, Items.WARPED_FENCE_GATE, null, Items.WARPED_FUNGUS, null, Items.WARPED_PRESSURE_PLATE, Items.WARPED_TRAPDOOR);
        }

        void p(WoodType type, String prefix, Item planks, Item log, Item strippedLog, Item strippedWood, Item wood, Item sign, Item hangingSign, Item door, Item button, Item stairs, Item slab, Item fence, Item fenceGate, Item boat, Item sapling, Item leaves, Item pressurePlate, Item trapdoor) {
            put(type, new WoodItems(prefix, planks, log, strippedLog, strippedWood, wood, sign, hangingSign, door, button, stairs, slab, fence, fenceGate, boat, sapling, leaves, pressurePlate, trapdoor));
        }
    };
    private static final HashMap<Item, Item> _cookableFoodMap = new HashMap<>() {
        {
            put(Items.PORKCHOP, Items.COOKED_PORKCHOP);
            put(Items.BEEF, Items.COOKED_BEEF);
            put(Items.CHICKEN, Items.COOKED_CHICKEN); // chicken is best meat, fight me
            put(Items.MUTTON, Items.COOKED_MUTTON);
            put(Items.RABBIT, Items.COOKED_RABBIT);
            put(Items.SALMON, Items.COOKED_SALMON);
            put(Items.COD, Items.COOKED_COD);
            put(Items.POTATO, Items.BAKED_POTATO);
        }
    };
    public static final Item[] RAW_FOODS = _cookableFoodMap.keySet().toArray(Item[]::new);
    private static Map<Item, Integer> _fuelTimeMap = null;

    public static String stripItemName(Item item) {
        String[] possibilities = new String[]{"item.minecraft.", "block.minecraft."};
        for (String possible : possibilities) {
            if (item.getDescriptionId().startsWith(possible)) {
                return item.getDescriptionId().substring(possible.length());
            }
        }
        return item.getDescriptionId();
    }

    public static Item[] blocksToItems(Block[] blocks) {
        Item[] result = new Item[blocks.length];
        for (int i = 0; i < blocks.length; ++i) {
            result[i] = blocks[i].asItem();
        }
        return result;
    }

    /* Logs:
        ACACIA
        BIRCH
        CRIMSON
        DARK_OAK
        OAK
        JUNGLE
        SPRUCE
        WARPED
     */

    /* Colors:
        WHITE
        BLACK
        BLUE
        BROWN
        CYAN
        GRAY
        GREEN
        LIGHT_BLUE
        LIGHT_GRAY
        LIME
        MAGENTA
        ORANGE
        PINK
        PURPLE
        RED
        YELLOW
     */

    public static Block[] itemsToBlocks(Item[] items) {
        ArrayList<Block> result = new ArrayList<>();
        for (Item item : items) {
            if (item instanceof BlockItem) {
                Block b = Block.byItem(item);
                if (b != null && b != Blocks.AIR) {
                    result.add(b);
                }
            }
        }
        return result.toArray(Block[]::new);
    }

    public static Item logToPlanks(Item logItem) {
        return _logToPlanks.getOrDefault(logItem, null);
    }

    public static Item planksToLog(Item plankItem) {
        return _planksToLogs.getOrDefault(plankItem, null);
    }

    public static Item strippedToLogs(Item logItem) {
        return _strippedToLogs.getOrDefault(logItem, null);
    }

    public static ColorfulItems getColorfulItems(MapColor color) {
        return _colorMap.get(color);
    }

    public static ColorfulItems getColorfulItems(DyeColor color) {
        return getColorfulItems(color.getMapColor());
    }

    public static Collection<ColorfulItems> getColorfulItems() {
        return _colorMap.values();
    }

    public static WoodItems getWoodItems(WoodType type) {
        return _woodMap.get(type);
    }

    public static Collection<WoodItems> getWoodItems() {
        return _woodMap.values();
    }

    public static Optional<Item> getCookedFood(Item rawFood) {
        return Optional.ofNullable(_cookableFoodMap.getOrDefault(rawFood, null));
    }

    public static String trimItemName(String name) {
        if (name.startsWith("block.minecraft.")) {
            name = name.substring("block.minecraft.".length());
        } else if (name.startsWith("item.minecraft.")) {
            name = name.substring("item.minecraft.".length());
        }
        return name;
    }

    public static boolean areShearsEffective(Block b) {
        return b instanceof LeavesBlock
                || b == Blocks.COBWEB
                || b == Blocks.SHORT_GRASS
                || b == Blocks.TALL_GRASS
                || b == Blocks.LILY_PAD
                || b == Blocks.FERN
                || b == Blocks.DEAD_BUSH
                || b == Blocks.VINE
                || b == Blocks.TRIPWIRE
                || isOfBlockType(b, BlockTags.WOOL)
                || b == Blocks.NETHER_SPROUTS;
    }

    public static boolean isOfBlockType(Block b, TagKey<Block> tag) {
        return BuiltInRegistries.BLOCK.wrapAsHolder(b).is(tag);
    }

    static boolean isStackProtected(AltoClef mod, ItemStack stack) {
        if (stack.isEnchanted() && Baritone.settings().altoDontThrowAwayEnchantedItems.value)
            return true;
        if (stack.getItem().components().has(DataComponents.CUSTOM_NAME) && Baritone.settings().altoDontThrowAwayCustomNameItems.value)
            return true;
        // autoTorch on and altoclef tossing the torches as junk would be a short lived feature
        if (Baritone.settings().autoTorch.value && TorchProcess.isTorch(stack))
            return true;
        if (mod.getBehaviour().isProtected(stack.getItem()) || AltoSettings.isImportant(stack.getItem()))
            return true;
        // the count is only read for the core kinds, this is asked about every bag stack
        return isCoreBuildBlock(stack.getItem())
                && GarbageRules.protectsCore(true, StorageHelper.getBuildingMaterialCount(mod), Baritone.settings().altoReservedBuildingBlockCount.value);
    }

    // the blocks worth mining for the reserve (end stone is all the end has). granite, andesite and the
    // rest count as building blocks too but take a slot each for a couple of items, so they are what goes first and never what we go looking for
    public static boolean isCoreBuildBlock(Item item) {
        return item == Items.COBBLESTONE || item == Items.COBBLED_DEEPSLATE || item == Items.NETHERRACK || item == Items.DIRT
                || item == Items.END_STONE;
    }

    public static boolean canThrowAwayStack(AltoClef mod, ItemStack stack) {
        // Can't throw away empty stacks!
        if (stack.isEmpty())
            return false;
        if (isStackProtected(mod, stack))
            return false;
        return AltoSettings.isThrowaway(stack.getItem()) || Baritone.settings().altoThrowAwayUnusedItems.value;
    }

    // canThrowAwayStack plus the food floor: food we cannot spare is not a stack to throw. this is the question every
    // "make room" path asks. canThrowAwayStack alone stays for the ones that only mean "may we touch this" (eating asks FoodChain.mayEat)
    public static boolean canDropStack(AltoClef mod, ItemStack stack) {
        // the bag total is only counted for food, this gets asked about every cursor stack
        return canDropStack(mod, stack, GarbageRules.keepUnits(stack) == 0 ? 0 : StorageHelper.bagFoodUnits(mod));
    }

    // bagUnits is the food the bag holds, passed in when asking about a bag we are about to have (a pile drop swapped in)
    public static boolean canDropStack(AltoClef mod, ItemStack stack, int bagUnits) {
        if (!canThrowAwayStack(mod, stack))
            return false;
        // the gapple is the run saver, it is never the stack that goes
        if (FoodHelper.kindOf(stack.getItem()) == FoodHelper.Kind.RESERVED)
            return false;
        int units = GarbageRules.keepUnits(stack);
        return units == 0 || !GarbageRules.keepsFood(bagUnits, units, Baritone.settings().altoFoodKeepUnits.value);
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean canStackTogether(ItemStack from, ItemStack to) {
        if (to.isEmpty() && from.getCount() <= from.getMaxStackSize())
            return true;
        return to.getItem().equals(from.getItem()) && (from.getCount() + to.getCount() <= to.getMaxStackSize());
    }

    // burn ticks in a plain furnace, by the path of the context_int_provider an item's cooking fuel component points at.
    // those providers are div(ticks, 1 or 2) behind a "is this a smoker" condition, they need a server level and a
    // loot context to evaluate, and the client never gets the registry. so this is the left side of the div from the
    // vanilla data (the plain furnace divides by 1). staying a table for good, FuelTableTest holds it against the jar's data
    private static final Map<String, Integer> FURNACE_BURN_TICKS = Map.ofEntries(
            Map.entry("cooking/time_bamboo", 50),
            Map.entry("cooking/time_blaze_rod", 2400),
            Map.entry("cooking/time_boats", 1200),
            Map.entry("cooking/time_coal", 1600),
            Map.entry("cooking/time_coal_block", 16000),
            Map.entry("cooking/time_dried_kelp_block", 4001),
            Map.entry("cooking/time_dry_plants", 100),
            Map.entry("cooking/time_hanging_signs", 800),
            Map.entry("cooking/time_lava_bucket", 20000),
            Map.entry("cooking/time_roots", 300),
            Map.entry("cooking/time_wood_blocks", 300),
            Map.entry("cooking/time_wood_items_extra_small", 100),
            Map.entry("cooking/time_wood_items_large", 200),
            Map.entry("cooking/time_wood_items_small", 300),
            Map.entry("cooking/time_wood_slabs", 150),
            Map.entry("cooking/time_wool", 100),
            Map.entry("cooking/time_wool_carpets", 67),
            Map.entry("cooking/time_wool_slabs", 50)
    );

    private static Map<Item, Integer> getFuelTimeMap() {
        // 1.21.2: fuel is a component on the item now (it is data driven), not a static table on the furnace
        if (_fuelTimeMap == null) {
            // item components only exist once registry data has loaded (main menu: nothing yet), same as the old no-world answer
            if (!Items.COAL.builtInRegistryHolder().areComponentsBound()) {
                return Map.of();
            }
            Map<Item, Integer> map = new HashMap<>();
            for (Item item : BuiltInRegistries.ITEM) {
                CookingFuel fuel = item.components().get(DataComponents.COOKING_FUEL);
                if (fuel == null) {
                    continue;
                }
                Integer ticks = null;
                if (fuel.burnTime() instanceof ResolvableInt.Constant constant) {
                    ticks = constant.value();
                } else if (fuel.burnTime() instanceof ResolvableInt.Reference reference) {
                    ticks = FURNACE_BURN_TICKS.get(reference.key().identifier().getPath());
                }
                if (ticks != null) {
                    map.put(item, ticks);
                }
            }
            _fuelTimeMap = map;
        }
        return _fuelTimeMap;
    }

    /// 1.21.2 turned the tool tiers into plain data components. These stand in for TieredItem / getTier().

    /**
     * Whatever used to extend TieredItem: swords and the pickaxe/axe/shovel/hoe family.
     */
    public static boolean isTool(Item item) {
        return isDigger(item) || isSword(item);
    }

    // the class checks (SwordItem, AxeItem, DiggerItem, ArmorItem) are gone, vanilla tags the same sets now.
    // note the tags only exist once a world's data is loaded
    public static boolean isSword(Item item) {
        return item.builtInRegistryHolder().is(ItemTags.SWORDS);
    }

    public static boolean isAxe(Item item) {
        return item.builtInRegistryHolder().is(ItemTags.AXES);
    }

    // pickaxe, axe, shovel, hoe. not a TOOL component check, swords and shears carry one too
    public static boolean isDigger(Item item) {
        Holder<Item> holder = item.builtInRegistryHolder();
        return holder.is(ItemTags.PICKAXES) || holder.is(ItemTags.AXES) || holder.is(ItemTags.SHOVELS) || holder.is(ItemTags.HOES);
    }

    // which tool family this is, null for anything that isn't one. tools are plain Item since 1.21.2, so getClass() puts a
    // pickaxe and a sword in the same family and the full-bag sweep threw the iron pick because a diamond sword was around
    public static String toolKind(Item item) {
        Holder<Item> holder = item.builtInRegistryHolder();
        if (holder.is(ItemTags.PICKAXES)) return "pickaxe";
        if (holder.is(ItemTags.AXES)) return "axe";
        if (holder.is(ItemTags.SHOVELS)) return "shovel";
        if (holder.is(ItemTags.HOES)) return "hoe";
        if (holder.is(ItemTags.SWORDS)) return "sword";
        return null;
    }

    // helmet/chestplate/leggings/boots. the armor tags leave out elytra, carved pumpkins and skulls, which is what ArmorItem did too
    public static boolean isArmor(Item item) {
        Holder<Item> holder = item.builtInRegistryHolder();
        return holder.is(ItemTags.HEAD_ARMOR) || holder.is(ItemTags.CHEST_ARMOR) || holder.is(ItemTags.LEG_ARMOR) || holder.is(ItemTags.FOOT_ARMOR);
    }

    /**
     * Stand in for tier.getUses(), the durability of the material (same number on every tool of a tier).
     */
    public static int getToolDurability(Item item) {
        return item.components().getOrDefault(DataComponents.MAX_DAMAGE, 0);
    }

    // keySet() on the enchantments is a set of enchantment holders, so asking it whether it contains a TAG key
    // (what the beat tasks did) is always false and curses sailed straight through. the holder knows its own tags
    public static boolean hasCurse(ItemStack stack) {
        for (Holder<Enchantment> enchantment : stack.getEnchantments().keySet()) {
            if (enchantment.is(EnchantmentTags.CURSE)) {
                return true;
            }
        }
        return false;
    }

    // a bare player hits for 1 (Player.createAttributes), the sword's own attribute is on top of that
    private static final double PLAYER_BASE_ATTACK_DAMAGE = 1.0;
    // and swings 4 times a second with nothing in hand. a sword's -2.4 is what makes it 1.6, an axe's -3.2 makes it 0.8
    private static final double PLAYER_BASE_ATTACK_SPEED = 4.0;

    // damage of one full charge hit with this item in the main hand, straight from the attribute modifiers
    // component. fists are 1, a wooden sword is 4, netherite is 8. no enchants (sharpness) in here
    public static float getAttackDamage(Item item) {
        return (float) mainHandAttribute(item, Attributes.ATTACK_DAMAGE, PLAYER_BASE_ATTACK_DAMAGE);
    }

    // anything that hits for more than a bare hand does: swords, axes, pickaxes and shovels all do, a stick does not
    public static boolean hitsHarderThanFists(Item item) {
        return getAttackDamage(item) > PLAYER_BASE_ATTACK_DAMAGE;
    }

    // full swings per second with this in the main hand. fists 4, sword 1.6, wooden axe 0.8. the tie break when two
    // weapons hit for the same (see WeaponPick)
    public static float getAttackSpeed(Item item) {
        return (float) mainHandAttribute(item, Attributes.ATTACK_SPEED, PLAYER_BASE_ATTACK_SPEED);
    }

    private static double mainHandAttribute(Item item, Holder<Attribute> attribute, double base) {
        ItemAttributeModifiers modifiers = item.components().get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (modifiers == null) {
            return base;
        }
        // same order the attribute instance uses: base, then all adds, then multiply-base off that, then multiply-total.
        // ItemAttributeModifiers#compute doesn't look at which attribute an entry is for (attack speed would leak in), so no using it
        double value = base;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (isMainHand(entry, attribute) && entry.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) {
                value += entry.modifier().amount();
            }
        }
        double afterAdds = value;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (isMainHand(entry, attribute) && entry.modifier().operation() == AttributeModifier.Operation.ADD_MULTIPLIED_BASE) {
                value += afterAdds * entry.modifier().amount();
            }
        }
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (isMainHand(entry, attribute) && entry.modifier().operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) {
                value *= 1 + entry.modifier().amount();
            }
        }
        return value;
    }

    // armor points (and toughness, the tie break) a piece gives in its own slot. read off the modifiers like the attack numbers,
    // so turtle shells and whatever a datapack makes up rank by what they actually do and not by a name list
    public static double getArmorPoints(Item item) {
        return wornAttribute(item, Attributes.ARMOR);
    }

    public static double getArmorToughness(Item item) {
        return wornAttribute(item, Attributes.ARMOR_TOUGHNESS);
    }

    private static double wornAttribute(Item item, Holder<Attribute> attribute) {
        net.minecraft.world.entity.EquipmentSlot slot = getEquipSlot(item);
        ItemAttributeModifiers modifiers = item.components().get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (slot == null || modifiers == null) {
            return 0;
        }
        double value = 0;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (entry.attribute().equals(attribute) && entry.slot().test(slot)
                    && entry.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) {
                value += entry.modifier().amount();
            }
        }
        return value;
    }

    private static boolean isMainHand(ItemAttributeModifiers.Entry entry, Holder<Attribute> attribute) {
        // ANY and HAND groups apply to the main hand too, not just MAINHAND
        return entry.attribute().equals(attribute) && entry.slot().test(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
    }

    // the sword that hits hardest, or null if there are no swords in the list. first one wins a tie
    public static Item getBestSword(Iterable<Item> candidates) {
        Item best = null;
        float bestDamage = Float.NEGATIVE_INFINITY;
        for (Item item : candidates) {
            if (!isSword(item)) continue;
            float damage = getAttackDamage(item);
            if (damage > bestDamage) {
                bestDamage = damage;
                best = item;
            }
        }
        return best;
    }

    /**
     * Stand in for tier.getSpeed(): the best mining speed any rule of the tool component gives.
     */
    public static float getMiningSpeed(Item item) {
        Tool tool = item.components().get(DataComponents.TOOL);
        if (tool == null) {
            return 1;
        }
        float best = tool.defaultMiningSpeed();
        for (Tool.Rule rule : tool.rules()) {
            if (rule.speed().isPresent()) {
                best = Math.max(best, rule.speed().get());
            }
        }
        return best;
    }

    /**
     * Stand in for `instanceof Equipable`, anything with the equippable component can be worn.
     */
    public static boolean isEquippable(Item item) {
        return item.components().has(DataComponents.EQUIPPABLE);
    }

    /**
     * Stand in for ArmorItem#getEquipmentSlot, the slot an equippable item goes in. Null if it doesn't go anywhere.
     */
    public static net.minecraft.world.entity.EquipmentSlot getEquipSlot(Item item) {
        var equippable = item.components().get(DataComponents.EQUIPPABLE);
        return equippable == null ? null : equippable.slot();
    }

    public static double getFuelAmount(Item... items) {
        double total = 0;
        for (Item item : items) {
            if (getFuelTimeMap().containsKey(item)) {
                int timeTicks = getFuelTimeMap().get(item);
                // 300 ticks of wood -> 1.5 operations
                // 200 ticks -> 1 operation
                total += (double) timeTicks / 200.0;
            }
        }
        return total;
    }

    public static double getFuelAmount(ItemStack stack) {
        return getFuelAmount(stack.getItem()) * stack.getCount();
    }

    public static boolean isFuel(Item item) {
        return getFuelTimeMap().containsKey(item);
    }

    public boolean isRawFood(Item item) {
        return _cookableFoodMap.containsKey(item);
    }

    public static class ColorfulItems {
        public DyeColor color;
        public String colorName;
        public Item dye;
        public Item wool;
        public Item bed;
        public Item carpet;
        public Item stainedGlass;
        public Item stainedGlassPane;
        public Item terracotta;
        public Item glazedTerracotta;
        public Item concrete;
        public Item concretePowder;
        public Item banner;
        public Item shulker;
        public Block wallBanner;

        public ColorfulItems(DyeColor color, String colorName, Item dye, Item wool, Item bed, Item carpet, Item stainedGlass, Item stainedGlassPane, Item terracotta, Item glazedTerracotta, Item concrete, Item concretePowder, Item banner, Item shulker, Block wallBanner) {
            this.color = color;
            this.colorName = colorName;
            this.dye = dye;
            this.wool = wool;
            this.bed = bed;
            this.carpet = carpet;
            this.stainedGlass = stainedGlass;
            this.stainedGlassPane = stainedGlassPane;
            this.terracotta = terracotta;
            this.glazedTerracotta = glazedTerracotta;
            this.concrete = concrete;
            this.concretePowder = concretePowder;
            this.banner = banner;
            this.shulker = shulker;
            this.wallBanner = wallBanner;
        }
    }

    public static class WoodItems {
        public String prefix;
        public Item planks;
        public Item log;
        public Item strippedLog;
        public Item strippedWood;
        public Item wood;
        public Item sign;
        public Item hangingSign;
        public Item door;
        public Item button;
        public Item stairs;
        public Item slab;
        public Item fence;
        public Item fenceGate;
        public Item boat;
        public Item sapling;
        public Item leaves;
        public Item pressurePlate;
        public Item trapdoor;

        public WoodItems(String prefix, Item planks, Item log, Item strippedLog, Item strippedWood, Item wood, Item sign, Item hangingSign, Item door, Item button, Item stairs, Item slab, Item fence, Item fenceGate, Item boat, Item sapling, Item leaves, Item pressurePlate, Item trapdoor) {
            this.prefix = prefix;
            this.planks = planks;
            this.log = log;
            this.strippedLog = strippedLog;
            this.strippedWood = strippedWood;
            this.wood = wood;
            this.sign = sign;
            this.hangingSign = hangingSign;
            this.door = door;
            this.button = button;
            this.stairs = stairs;
            this.slab = slab;
            this.fence = fence;
            this.fenceGate = fenceGate;
            this.boat = boat;
            this.sapling = sapling;
            this.leaves = leaves;
            this.pressurePlate = pressurePlate;
            this.trapdoor = trapdoor;
        }

        public boolean isNetherWood() {
            return planks == Items.CRIMSON_PLANKS || planks == Items.WARPED_PLANKS;
        }
    }

}
