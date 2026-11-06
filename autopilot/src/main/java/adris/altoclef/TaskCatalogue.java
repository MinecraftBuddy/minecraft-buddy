package adris.altoclef;

import net.minecraft.core.registries.BuiltInRegistries;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasks.container.UpgradeInSmithingTableTask;
import adris.altoclef.tasks.resources.*;
import adris.altoclef.tasks.resources.wood.*;
import adris.altoclef.tasks.squashed.CataloguedResourceTask;
import adris.altoclef.util.*;
import baritone.api.utils.Dimension;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.world.entity.animal.squid.GlowSquid;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.fish.Cod;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.fish.Salmon;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.animal.squid.Squid;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.MapColor;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Contains a hardcoded list of ALL obtainable resources.
 * <p>
 * Most resources correspond to a single item, but some resources (like "log" or "door") include a range of items.
 * <p>
 * Call `TaskCatalogue.getItemTask` to return a task given a resource key.
 * Call `TaskCatalogue.getSquashedItemTask` to return a task that gets multiple resources, combining their steps.
 */
@SuppressWarnings({"rawtypes"})
public class TaskCatalogue {

    private static final HashMap<String, Item[]> _nameToItemMatches = new HashMap<>();
    private static final HashMap<String, CataloguedResource> _nameToResourceTask = new HashMap<>();
    private static final HashMap<Item, CataloguedResource> _itemToResourceTask = new HashMap<>();
    private static final HashSet<Item> _resourcesObtainable = new HashSet<>();
    // second names for the same entry ("door", "netherite_pick"). they resolve when typed, but anything listing the
    // catalogue for people to look at should skip them or every pickaxe shows up twice
    private static final HashMap<String, String> _aliases = new HashMap<>();
    // every recipe the shaped helpers register, by name. building the task needs a running client, so the recipe parity test reads these instead
    private static final TreeMap<String, RecipeTarget> _craftedRecipes = new TreeMap<>();
    // what the smelts and smiths are made from, by name. they aren't recipes in the graph above, and a smelt of
    // netherrack is a trip to the nether all the same
    private static final HashMap<String, List<String>> _madeFrom = new HashMap<>();
    // the catalogue name of every single item entry, so needsNether can go from an item to its recipe
    private static final HashMap<Item, String> _itemToName = new HashMap<>();
    // needsNether's answers. the catalogue never changes after the static block, so neither do these
    private static final Map<String, Boolean> _needsNether = new java.util.concurrent.ConcurrentHashMap<>();
    // the ones whose task walks to the nether by itself without the catalogue flag saying so
    private static final Set<String> NETHER_BY_HAND = Set.of("nether_bricks", "magma_cream");

    static {
        /// DEFINE RESOURCE TASKS HERE
        {
            String p = "planks";
            String s = "stick";
            String o = null;

            /// RAW RESOURCES
            simple("log", ItemHelper.LOG, MineLogsTask::new).anyDimension();
            woodTasks("log", wood -> wood.log, (wood, count) -> new MineAndCollectTask(wood.log, count, new Block[]{Block.byItem(wood.log)}, MiningRequirement.HAND), true);
            mine("dirt", MiningRequirement.HAND, new Block[]{Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.DIRT_PATH}, Items.DIRT);
            simple("cobblestone", Items.COBBLESTONE, CollectCobblestoneTask::new).dontMineIfPresent();
            simple("cobbled_deepslate", Items.COBBLED_DEEPSLATE, CollectCobbledDeepslateTask::new).dontMineIfPresent();
            // the buddy's fork: stone tools and a furnace take any of these three, as the game does
            simple("stone_material", CollectStoneMaterialTask.MATERIALS, CollectStoneMaterialTask::new).dontMineIfPresent();
            mine("andesite", MiningRequirement.WOOD, Blocks.ANDESITE, Items.ANDESITE);
            mine("granite", MiningRequirement.WOOD, Blocks.GRANITE, Items.GRANITE);
            mine("diorite", MiningRequirement.WOOD, Blocks.DIORITE, Items.DIORITE);
            mine("calcite", MiningRequirement.WOOD, Blocks.CALCITE, Items.CALCITE);
            mine("tuff", MiningRequirement.WOOD, Blocks.TUFF, Items.TUFF);
            mine("netherrack", MiningRequirement.WOOD, Blocks.NETHERRACK, Items.NETHERRACK).forceDimension(Dimension.NETHER);
            mine("magma_block", MiningRequirement.WOOD, Blocks.MAGMA_BLOCK, Items.MAGMA_BLOCK).forceDimension(Dimension.NETHER);
            mine("blackstone", MiningRequirement.WOOD, Blocks.BLACKSTONE, Items.BLACKSTONE).forceDimension(Dimension.NETHER);
            mine("basalt", MiningRequirement.WOOD, Blocks.BASALT, Items.BASALT).forceDimension(Dimension.NETHER);
            mine("soul_sand", Items.SOUL_SAND).forceDimension(Dimension.NETHER);
            mine("soul_soil", Items.SOUL_SOIL).forceDimension(Dimension.NETHER);
            mine("glowstone_dust", Blocks.GLOWSTONE, Items.GLOWSTONE_DUST).forceDimension(Dimension.NETHER);
            ore("coal", MiningRequirement.WOOD, new Block[]{Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE}, Items.COAL);
            ore("raw_iron", MiningRequirement.STONE, new Block[]{Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE}, Items.RAW_IRON);
            ore("raw_gold", MiningRequirement.IRON, new Block[]{Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE}, Items.RAW_GOLD);
            ore("raw_copper", MiningRequirement.STONE, new Block[]{Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE}, Items.RAW_COPPER);
            ore("diamond", MiningRequirement.IRON, new Block[]{Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE}, Items.DIAMOND);
            ore("emerald", MiningRequirement.IRON, new Block[]{Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE}, Items.EMERALD);
            ore("redstone", MiningRequirement.IRON, new Block[]{Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE}, Items.REDSTONE);
            ore("lapis_lazuli", MiningRequirement.STONE, new Block[]{Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE}, Items.LAPIS_LAZULI);
            alias("lapis", "lapis_lazuli");
            mine("amethyst_shard", MiningRequirement.WOOD, Blocks.AMETHYST_CLUSTER, Items.AMETHYST_SHARD);
            mine("pointed_dripstone", MiningRequirement.WOOD, Blocks.POINTED_DRIPSTONE, Items.POINTED_DRIPSTONE);
            mine("sand", Blocks.SAND, Items.SAND);
            mine("red_sand", Blocks.RED_SAND, Items.RED_SAND);
            mine("gravel", Blocks.GRAVEL, Items.GRAVEL);
            mine("clay_ball", Blocks.CLAY, Items.CLAY_BALL);
            mine("ancient_debris", MiningRequirement.DIAMOND, Blocks.ANCIENT_DEBRIS, Items.ANCIENT_DEBRIS).forceDimension(Dimension.NETHER);
            mine("gilded_blackstone", MiningRequirement.STONE, Blocks.GILDED_BLACKSTONE, Items.GILDED_BLACKSTONE).forceDimension(Dimension.NETHER);
            mine("oak_sapling", Blocks.OAK_LEAVES, Items.OAK_SAPLING);
            mine("spruce_sapling", Blocks.SPRUCE_LEAVES, Items.SPRUCE_SAPLING);
            mine("birch_sapling", Blocks.BIRCH_LEAVES, Items.BIRCH_SAPLING);
            mine("jungle_sapling", Blocks.JUNGLE_LEAVES, Items.JUNGLE_SAPLING);
            mine("acacia_sapling", Blocks.ACACIA_LEAVES, Items.ACACIA_SAPLING);
            mine("dark_oak_sapling", Blocks.DARK_OAK_LEAVES, Items.DARK_OAK_SAPLING);
            mine("mangrove_propagule", Blocks.MANGROVE_PROPAGULE, Items.MANGROVE_PROPAGULE);
            mine("cherry_sapling", Blocks.CHERRY_LEAVES, Items.CHERRY_SAPLING);
mine("pale_oak_sapling", Blocks.PALE_OAK_LEAVES, Items.PALE_OAK_SAPLING);
            // poplar has no plain leaves, the three autumn colours all drop its sapling
            mine("poplar_sapling", MiningRequirement.HAND, new Block[]{Blocks.ORANGE_POPLAR_LEAVES, Blocks.RED_POPLAR_LEAVES, Blocks.YELLOW_POPLAR_LEAVES}, Items.POPLAR_SAPLING);
            simple("sapling", ItemHelper.SAPLINGS, CollectSaplingsTask::new);
            simple("sandstone", Items.SANDSTONE, CollectSandstoneTask::new).dontMineIfPresent();
            simple("red_sandstone", Items.RED_SANDSTONE, CollectRedSandstoneTask::new).dontMineIfPresent();
            simple("coarse_dirt", Items.COARSE_DIRT, CollectCoarseDirtTask::new).dontMineIfPresent();
            simple("amethyst_block", Items.AMETHYST_BLOCK, CollectAmethystBlockTask::new).dontMineIfPresent();
            simple("dripstone_block", Items.DRIPSTONE_BLOCK, CollectDripstoneBlockTask::new).dontMineIfPresent();
            simple("flint", Items.FLINT, CollectFlintTask::new);
            simple("obsidian", Items.OBSIDIAN, CollectObsidianTask::new).dontMineIfPresent();
            simple("wool", ItemHelper.WOOL, CollectWoolTask::new);
            simple("egg", Items.EGG, CollectEggsTask::new);
            mob("bone", Items.BONE, Skeleton.class);
            mob("gunpowder", Items.GUNPOWDER, Creeper.class);
            simple("ender_pearl", Items.ENDER_PEARL, KillEndermanTask::new);
            mob("spider_eye", Items.SPIDER_EYE, Spider.class);
            mob("leather", Items.LEATHER, Cow.class);
            mob("feather", Items.FEATHER, Chicken.class);
            mob("rotten_flesh", Items.ROTTEN_FLESH, Zombie.class);
            mob("rabbit_foot", Items.RABBIT_FOOT, Rabbit.class);
            mob("rabbit_hide", Items.RABBIT_HIDE, Rabbit.class);
            mob("slime_ball", Items.SLIME_BALL, Slime.class);
            mob("wither_skeleton_skull", Items.WITHER_SKELETON_SKULL, WitherSkeleton.class).forceDimension(Dimension.NETHER);
            mob("ink_sac", Items.INK_SAC, Squid.class); // Warning, this probably won't work.
            mob("glow_ink_sac", Items.GLOW_INK_SAC, GlowSquid.class); // Warning, this probably won't work.
            mob("string", Items.STRING, Spider.class); // Warning, this probably won't work.
            mine("sugar_cane", Items.SUGAR_CANE);
            mine("brown_mushroom", MiningRequirement.HAND, new Block[]{Blocks.BROWN_MUSHROOM, Blocks.BROWN_MUSHROOM_BLOCK}, Items.BROWN_MUSHROOM);
            mine("red_mushroom", MiningRequirement.HAND, new Block[]{Blocks.RED_MUSHROOM, Blocks.RED_MUSHROOM_BLOCK}, Items.RED_MUSHROOM);
            mine("mushroom", MiningRequirement.HAND, new Block[]{Blocks.BROWN_MUSHROOM, Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM, Blocks.RED_MUSHROOM_BLOCK}, Items.BROWN_MUSHROOM, Items.RED_MUSHROOM);
            mine("melon_slice", MiningRequirement.HAND, Blocks.MELON, Items.MELON_SLICE);
            mine("pumpkin", MiningRequirement.HAND, Blocks.PUMPKIN, Items.PUMPKIN);
            mine("bell", MiningRequirement.WOOD, Blocks.BELL, Items.BELL);
            mine("nether_wart", MiningRequirement.HAND, Blocks.NETHER_WART, Items.NETHER_WART).forceDimension(Dimension.NETHER);
            mine("crimson_fungus", MiningRequirement.HAND, Blocks.CRIMSON_FUNGUS, Items.CRIMSON_FUNGUS).forceDimension(Dimension.NETHER);
            mine("warped_fungus", MiningRequirement.HAND, Blocks.WARPED_FUNGUS, Items.WARPED_FUNGUS).forceDimension(Dimension.NETHER);
            mine("crimson_roots", MiningRequirement.HAND, Blocks.CRIMSON_ROOTS, Items.CRIMSON_ROOTS).forceDimension(Dimension.NETHER);
            mine("warped_roots", MiningRequirement.HAND, Blocks.WARPED_ROOTS, Items.WARPED_ROOTS).forceDimension(Dimension.NETHER);
            mine("weeping_vines", MiningRequirement.HAND, Blocks.WEEPING_VINES, Items.WEEPING_VINES).forceDimension(Dimension.NETHER);
            mine("twisting_vines", MiningRequirement.HAND, Blocks.TWISTING_VINES, Items.TWISTING_VINES).forceDimension(Dimension.NETHER);
            mine("nether_wart_block", MiningRequirement.HAND, Blocks.NETHER_WART_BLOCK, Items.NETHER_WART_BLOCK).forceDimension(Dimension.NETHER);
            mine("warped_wart_block", MiningRequirement.HAND, Blocks.WARPED_WART_BLOCK, Items.WARPED_WART_BLOCK).forceDimension(Dimension.NETHER);
            mine("shroomlight", MiningRequirement.HAND, Blocks.SHROOMLIGHT, Items.SHROOMLIGHT).forceDimension(Dimension.NETHER);
            simple("blaze_rod", Items.BLAZE_ROD, CollectBlazeRodsTask::new).forceDimension(Dimension.NETHER); // Not super simple tbh lmao
            //simple("quartz", Items.QUARTZ, CollectQuartzTask::new);
            mine("quartz", MiningRequirement.WOOD, Blocks.NETHER_QUARTZ_ORE, Items.QUARTZ).forceDimension(Dimension.NETHER);
            simple("cocoa_beans", Items.COCOA_BEANS, CollectCocoaBeansTask::new);
            shear("cobweb", Blocks.COBWEB, Items.COBWEB).dontMineIfPresent();
            colorfulTasks("wool", color -> color.wool, (color, count) -> new CollectWoolTask(color.color, count));
            // Misc greenery
            shear("leaves", ItemHelper.itemsToBlocks(ItemHelper.LEAVES), ItemHelper.LEAVES).dontMineIfPresent();
            for (CataloguedResource resource : woodTasks(
                    "leaves",
                    woodItems -> woodItems.leaves,
                    (woodItems, count) -> {
                        if (woodItems.isNetherWood()) {
                            // Nether "leaves" aren't sheared, they can simply be mined.
                            return new MineAndCollectTask(woodItems.leaves, count, new Block[]{Block.byItem(woodItems.leaves)}, MiningRequirement.HAND).forceDimension(Dimension.NETHER);
                        } else {
                            return new ShearAndCollectBlockTask(woodItems.leaves, count, Block.byItem(woodItems.leaves));
                        }
                    })
            ) {
                resource.dontMineIfPresent();
            }
            mine("bamboo", Blocks.BAMBOO, Items.BAMBOO);
            shear("vine", Blocks.VINE, Items.VINE).dontMineIfPresent();
            shear("grass", Blocks.SHORT_GRASS, Items.SHORT_GRASS).dontMineIfPresent();
            shear("lily_pad", Blocks.LILY_PAD, Items.LILY_PAD).dontMineIfPresent();
            shear("tall_grass", Blocks.TALL_GRASS, Items.TALL_GRASS).dontMineIfPresent();
            shear("fern", Blocks.FERN, Items.FERN).dontMineIfPresent();
            shear("large_fern", Blocks.LARGE_FERN, Items.LARGE_FERN).dontMineIfPresent();
            shear("dead_bush", Blocks.DEAD_BUSH, Items.DEAD_BUSH).dontMineIfPresent();
            shear("glow_lichen", Blocks.GLOW_LICHEN, Items.GLOW_LICHEN).dontMineIfPresent();
            // Flowers
            simple("flower", ItemHelper.FLOWER, CollectFlowerTask::new);
            // a recipe slot with several items has to be a catalogue name, or the recipe collector skips it and never fetches anything
            mine("suspicious_stew_flower", MiningRequirement.HAND, ItemHelper.SUSPICIOUS_STEW_FLOWERS, ItemHelper.SUSPICIOUS_STEW_FLOWERS);
            mine("allium", Items.ALLIUM);
            mine("azure_bluet", Items.AZURE_BLUET);
            mine("blue_orchid", Items.BLUE_ORCHID);
            mine("cactus", Items.CACTUS);
            mine("cornflower", Items.CORNFLOWER);
            mine("dandelion", Items.DANDELION);
            mine("lilac", Items.LILAC);
            mine("lily_of_the_valley", Items.LILY_OF_THE_VALLEY);
            mine("orange_tulip", Items.ORANGE_TULIP);
            mine("oxeye_daisy", Items.OXEYE_DAISY);
            mine("pink_tulip", Items.PINK_TULIP);
            mine("poppy", Items.POPPY);
            mine("peony", Items.PEONY);
            mine("red_tulip", Items.RED_TULIP);
            mine("rose_bush", Items.ROSE_BUSH);
            mine("sunflower", Items.SUNFLOWER);
            mine("white_tulip", Items.WHITE_TULIP);
            // Crops
            simple("wheat", Items.WHEAT, CollectWheatTask::new);
            crop("carrot", Items.CARROT, Blocks.CARROTS, Items.CARROT);
            crop("potato", Items.POTATO, Blocks.POTATOES, Items.POTATO);
            crop("poisonous_potato", Items.POISONOUS_POTATO, Blocks.POTATOES, Items.POTATO);
            crop("beetroot", Items.BEETROOT, Blocks.BEETROOTS, Items.BEETROOT_SEEDS);
            simple("wheat_seeds", Items.WHEAT_SEEDS, CollectWheatSeedsTask::new);
            crop("beetroot_seeds", Items.BEETROOT_SEEDS, Blocks.BEETROOTS, Items.BEETROOT_SEEDS);


            // MATERIALS
            //mine("netherite_upgrade_smithing_template", MiningRequirement.HAND, Blocks.CHEST, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE).forceDimension(Dimension.NETHER);
            simple("netherite_upgrade_smithing_template", Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, GetSmithingTemplateTask::new);
            alias("netherite_upgrade", "netherite_upgrade_smithing_template");
            simple("planks", ItemHelper.PLANKS, CollectPlanksTask::new).dontMineIfPresent();
            // Per-tree Planks. At the moment, nether planks need to be specified that their logs are in the nether.
            for (CataloguedResource woodCatalogue : woodTasks("planks", wood -> wood.planks, (wood, count) -> {
                CollectPlanksTask result = new CollectPlanksTask(wood.planks, count);
                if (wood.isNetherWood()) {
                    // Kinda jank
                    result.logsInNether();
                }
                return result;
            }, true)) {
                // Don't mine individual planks either!! Handled internally.
                woodCatalogue.dontMineIfPresent();
            }
            simple("stripped_logs", ItemHelper.STRIPPED_LOGS, CollectStrippedLogTask::new).dontMineIfPresent();
            for (CataloguedResource woodCatalogue : woodTasks("stripped_logs", wood -> wood.strippedLog,
                    (wood, count) -> new CollectStrippedLogTask(wood.strippedLog, count))) {
                woodCatalogue.dontMineIfPresent();
            }
            // shapedRecipe2x2("stick", Items.STICK, 4, p, o, p, o);
            simple("stick", Items.STICK, CollectSticksTask::new);
            smelt("stone", Items.STONE, "cobblestone").dontMineIfPresent();
            smelt("deepslate", Items.DEEPSLATE, "cobbled_deepslate").dontMineIfPresent();
            smelt("smooth_stone", Items.SMOOTH_STONE, "stone");
            smelt("smooth_quartz", Items.SMOOTH_QUARTZ, "quartz_block");
            smelt("smooth_basalt", Items.SMOOTH_BASALT, "basalt");
            smelt("glass", Items.GLASS, "sand").dontMineIfPresent();
            simple("iron_ingot", Items.IRON_INGOT, CollectIronIngotTask::new).forceDimension(Dimension.OVERWORLD);
            smelt("copper_ingot", Items.COPPER_INGOT, "raw_copper", Items.COPPER_ORE);
            smelt("charcoal", Items.CHARCOAL, "log");
            smelt("brick", Items.BRICK, "clay_ball");
            smelt("nether_brick", Items.NETHER_BRICK, "netherrack");
            smelt("green_dye", Items.DYE.green(), "cactus");
            simple("gold_ingot", Items.GOLD_INGOT, CollectGoldIngotTask::new).anyDimension(); // accounts for nether too
            shapedRecipe3x3Block("iron_block", Items.IRON_BLOCK, "iron_ingot");
            shapedRecipe3x3Block("gold_block", Items.GOLD_BLOCK, "gold_ingot");
            shapedRecipe3x3Block("copper_block", Items.COPPER_BLOCK.weathering().unaffected(), "copper_ingot");
            shapedRecipe3x3Block("raw_iron_block", Items.RAW_IRON_BLOCK, "raw_iron");
            shapedRecipe3x3Block("raw_gold_block", Items.RAW_GOLD_BLOCK, "raw_gold");
            shapedRecipe3x3Block("raw_copper_block", Items.RAW_COPPER_BLOCK, "raw_copper");
            shapedRecipe3x3Block("diamond_block", Items.DIAMOND_BLOCK, "diamond");
            shapedRecipe3x3Block("redstone_block", Items.REDSTONE_BLOCK, "redstone");
            shapedRecipe3x3Block("coal_block", Items.COAL_BLOCK, "coal");
            shapedRecipe3x3Block("emerald_block", Items.EMERALD_BLOCK, "emerald");
            shapedRecipe3x3Block("lapis_block", Items.LAPIS_BLOCK, "lapis_lazuli");
            shapedRecipe3x3Block("slime_block", Items.SLIME_BLOCK, "slime_ball");
            shapedRecipe3x3Block("melon", Items.MELON, "melon_slice").dontMineIfPresent();
            shapedRecipe2x2Block("glowstone", Items.GLOWSTONE, "glowstone_dust").dontMineIfPresent();
            shapedRecipe2x2Block("clay", Items.CLAY, "clay_ball").dontMineIfPresent();
            smelt("netherite_scrap", Items.NETHERITE_SCRAP, "ancient_debris");
            shapedRecipe3x3("netherite_ingot", Items.NETHERITE_INGOT, 1, "netherite_scrap", "netherite_scrap", "netherite_scrap", "netherite_scrap", "gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot", o);
            simple("gold_nugget", Items.GOLD_NUGGET, CollectGoldNuggetsTask::new);
            {
                String g = "gold_nugget";
                shapedRecipe3x3("glistering_melon_slice", Items.GLISTERING_MELON_SLICE, 1, g, g, g, g, "melon_slice", g, g, g, g);
            }
            shapedRecipe2x2("sugar", Items.SUGAR, 1, "sugar_cane", o, o, o);
            shapedRecipe2x2("bone_meal", Items.BONE_MEAL, 3, "bone", o, o, o);
            shapedRecipe2x2("melon_seeds", Items.MELON_SEEDS, 1, "melon_slice", o, o, o);
            // the block goes first: a slot name that isn't registered yet quietly matches nothing, and the planks recipe had no ingredient at all
            shapedRecipe3x3Block("bamboo_block", Items.BAMBOO_BLOCK, "bamboo");
            shapedRecipe2x2("bamboo_planks", Items.BAMBOO_PLANKS, 2, "bamboo_block", o, o, o);
            simple("hay_block", Items.HAY_BLOCK, CollectHayBlockTask::new).dontMineIfPresent();
            shapedRecipe2x2Block("polished_andesite", Items.POLISHED_ANDESITE, 4, "andesite");
            shapedRecipe2x2Block("polished_diorite", Items.POLISHED_DIORITE, 4, "diorite");
            shapedRecipe2x2Block("polished_granite", Items.POLISHED_GRANITE, 4, "granite");
            shapedRecipe2x2Block("quartz_block", Items.QUARTZ_BLOCK, "quartz");
            shapedRecipe2x2Block("polished_blackstone", Items.POLISHED_BLACKSTONE, 4, "blackstone");
            shapedRecipe2x2Block("polished_blackstone_bricks", Items.POLISHED_BLACKSTONE_BRICKS, 4, "polished_blackstone");
            shapedRecipe2x2Block("polished_basalt", Items.POLISHED_BASALT, 4, "basalt");
            shapedRecipe2x2Block("polished_deepslate", Items.POLISHED_DEEPSLATE, 4, "cobbled_deepslate");
            shapedRecipe2x2Block("deepslate_bricks", Items.DEEPSLATE_BRICKS, 4, "polished_deepslate");
            shapedRecipe2x2Block("deepslate_tiles", Items.DEEPSLATE_TILES, 4, "deepslate_bricks");
            shapedRecipe2x2Block("cut_copper", Items.CUT_COPPER.weathering().unaffected(), 4, "copper_block");
            shapedRecipe2x2Block("cut_sandstone", Items.CUT_SANDSTONE, 4, "sandstone");
            shapedRecipe2x2Block("cut_red_sandstone", Items.CUT_RED_SANDSTONE, 4, "red_sandstone");
            shapedRecipe2x2Block("quartz_bricks", Items.QUARTZ_BRICKS, 4, "quartz_block");
            shapedRecipe2x2("quartz_pillar", Items.QUARTZ_PILLAR, 2, "quartz_block", o, "quartz_block", o);
            shapedRecipe2x2Block("stone_bricks", Items.STONE_BRICKS, 4, "stone");
            shapedRecipe2x2("mossy_stone_bricks", Items.MOSSY_STONE_BRICKS, 1, "stone_bricks", "vine", o, o);
            shapedRecipe2x2("mossy_cobblestone", Items.MOSSY_COBBLESTONE, 1, "cobblestone", "vine", o, o);
            simple("nether_bricks", Items.NETHER_BRICKS, CollectNetherBricksTask::new).dontMineIfPresent();
            shapedRecipe2x2("red_nether_bricks", Items.RED_NETHER_BRICKS, 1, "nether_brick", "nether_wart", "nether_wart", "nether_brick");
            smelt("cracked_stone_bricks", Items.CRACKED_STONE_BRICKS, "stone_bricks");
            smelt("cracked_nether_bricks", Items.CRACKED_NETHER_BRICKS, "nether_bricks");
            smelt("cracked_polished_blackstone_bricks", Items.CRACKED_POLISHED_BLACKSTONE_BRICKS, "polished_blackstone_bricks");
            smelt("cracked_deepslate_bricks", Items.CRACKED_DEEPSLATE_BRICKS, "deepslate_bricks");
            smelt("cracked_deepslate_tiles", Items.CRACKED_DEEPSLATE_TILES, "deepslate_tiles");
            smelt("smooth_sandstone", Items.SMOOTH_SANDSTONE, "sandstone");
            smelt("smooth_red_sandstone", Items.SMOOTH_RED_SANDSTONE, "red_sandstone");
            {
                String B = "nether_bricks";
                String b = "nether_brick";
                shapedRecipe3x3("nether_brick_fence", Items.NETHER_BRICK_FENCE, 6, o, o, o, B, b, B, B, b, B);
            }
            shapedRecipe3x3("brush", Items.BRUSH, 1, o, "feather", o, o, "copper_ingot", o, o, s, o);
            shapedRecipe3x3("paper", Items.PAPER, 3, "sugar_cane", "sugar_cane", "sugar_cane", o, o, o, o, o, o);
            shapedRecipe2x2("book", Items.BOOK, 1, "paper", "paper", "paper", "leather");
            shapedRecipe2x2("writable_book", Items.WRITABLE_BOOK, 1, "book", "ink_sac", o, "feather");
            alias("book_and_quill", "writable_book");
            shapedRecipe3x3("bowl", Items.BOWL, 4, p, o, p, o, p, o, o, o, o);
            shapedRecipe2x2("blaze_powder", Items.BLAZE_POWDER, 2, "blaze_rod", o, o, o);
            shapedRecipe2x2("ender_eye", Items.ENDER_EYE, 1, "blaze_powder", "ender_pearl", o, o);
            alias("eye_of_ender", "ender_eye");
            shapedRecipe2x2("fermented_spider_eye", Items.FERMENTED_SPIDER_EYE, 1, "brown_mushroom", "sugar", o, "spider_eye");
            shapedRecipe3x3("fire_charge", Items.FIRE_CHARGE, 3, o, "blaze_powder", o, o, "coal", o, o, "gunpowder", o);
            shapedRecipe2x2("flower_banner_pattern", Items.FLOWER_BANNER_PATTERN, 1, "paper", "oxeye_daisy", o, o);
            simple("magma_cream", Items.MAGMA_CREAM, CollectMagmaCreamTask::new);
            // Slabs + Stairs + Walls
            shapedRecipeSlab("cobblestone_slab", Items.COBBLESTONE_SLAB, "cobblestone");
            shapedRecipeStairs("cobblestone_stairs", Items.COBBLESTONE_STAIRS, "cobblestone");
            shapedRecipeWall("cobblestone_wall", Items.COBBLESTONE_WALL, "cobblestone");
            shapedRecipeSlab("stone_slab", Items.STONE_SLAB, "stone");
            shapedRecipeStairs("stone_stairs", Items.STONE_STAIRS, "stone");
            shapedRecipeSlab("smooth_stone_slab", Items.SMOOTH_STONE_SLAB, "smooth_stone");
            shapedRecipeSlab("stone_brick_slab", Items.STONE_BRICK_SLAB, "stone_bricks");
            shapedRecipeStairs("stone_brick_stairs", Items.STONE_BRICK_STAIRS, "stone_bricks");
            shapedRecipeWall("stone_brick_wall", Items.STONE_BRICK_WALL, "stone_bricks");
            shapedRecipeSlab("mossy_stone_brick_slab", Items.MOSSY_STONE_BRICK_SLAB, "mossy_stone_bricks");
            shapedRecipeStairs("mossy_stone_brick_stairs", Items.MOSSY_STONE_BRICK_STAIRS, "mossy_stone_bricks");
            shapedRecipeWall("mossy_stone_brick_wall", Items.MOSSY_STONE_BRICK_WALL, "mossy_stone_bricks");
            shapedRecipeSlab("mossy_cobblestone_slab", Items.MOSSY_COBBLESTONE_SLAB, "mossy_cobblestone");
            shapedRecipeStairs("mossy_cobblestone_stairs", Items.MOSSY_COBBLESTONE_STAIRS, "mossy_cobblestone");
            shapedRecipeWall("mossy_cobblestone_wall", Items.MOSSY_COBBLESTONE_WALL, "mossy_cobblestone");
            shapedRecipeSlab("andesite_slab", Items.ANDESITE_SLAB, "andesite");
            shapedRecipeStairs("andesite_stairs", Items.ANDESITE_STAIRS, "andesite");
            shapedRecipeWall("andesite_wall", Items.ANDESITE_WALL, "andesite");
            shapedRecipeSlab("granite_slab", Items.GRANITE_SLAB, "granite");
            shapedRecipeStairs("granite_stairs", Items.GRANITE_STAIRS, "granite");
            shapedRecipeWall("granite_wall", Items.GRANITE_WALL, "granite");
            shapedRecipeSlab("diorite_slab", Items.DIORITE_SLAB, "diorite");
            shapedRecipeStairs("diorite_stairs", Items.DIORITE_STAIRS, "diorite");
            shapedRecipeWall("diorite_wall", Items.DIORITE_WALL, "diorite");
            shapedRecipeSlab("polished_andesite_slab", Items.POLISHED_ANDESITE_SLAB, "polished_andesite");
            shapedRecipeStairs("polished_andesite_stairs", Items.POLISHED_ANDESITE_STAIRS, "polished_andesite");
            shapedRecipeSlab("polished_granite_slab", Items.POLISHED_GRANITE_SLAB, "polished_granite");
            shapedRecipeStairs("polished_granite_stairs", Items.POLISHED_GRANITE_STAIRS, "polished_granite");
            shapedRecipeSlab("polished_diorite_slab", Items.POLISHED_DIORITE_SLAB, "polished_diorite");
            shapedRecipeStairs("polished_diorite_stairs", Items.POLISHED_DIORITE_STAIRS, "polished_diorite");
            shapedRecipeSlab("sandstone_slab", Items.SANDSTONE_SLAB, "sandstone");
            shapedRecipeStairs("sandstone_stairs", Items.SANDSTONE_STAIRS, "sandstone");
            shapedRecipeWall("sandstone_wall", Items.SANDSTONE_WALL, "sandstone");
            shapedRecipeSlab("cut_sandstone_slab", Items.CUT_STANDSTONE_SLAB, "cut_sandstone");
            shapedRecipeSlab("smooth_sandstone_slab", Items.SMOOTH_SANDSTONE_SLAB, "smooth_sandstone");
            shapedRecipeStairs("smooth_sandstone_stairs", Items.SMOOTH_SANDSTONE_STAIRS, "smooth_sandstone");
            shapedRecipeSlab("red_sandstone_slab", Items.RED_SANDSTONE_SLAB, "red_sandstone");
            shapedRecipeStairs("red_sandstone_stairs", Items.RED_SANDSTONE_STAIRS, "red_sandstone");
            shapedRecipeWall("red_sandstone_wall", Items.RED_SANDSTONE_WALL, "red_sandstone");
            shapedRecipeSlab("cut_red_sandstone_slab", Items.CUT_RED_SANDSTONE_SLAB, "cut_red_sandstone");
            shapedRecipeSlab("smooth_red_sandstone_slab", Items.SMOOTH_RED_SANDSTONE_SLAB, "smooth_red_sandstone");
            shapedRecipeStairs("smooth_red_sandstone_stairs", Items.SMOOTH_RED_SANDSTONE_STAIRS, "smooth_red_sandstone");
            shapedRecipeSlab("nether_brick_slab", Items.NETHER_BRICK_SLAB, "nether_bricks");
            shapedRecipeStairs("nether_brick_stairs", Items.NETHER_BRICK_STAIRS, "nether_bricks");
            shapedRecipeWall("nether_brick_wall", Items.NETHER_BRICK_WALL, "nether_bricks");
            shapedRecipeSlab("red_nether_brick_slab", Items.RED_NETHER_BRICK_SLAB, "red_nether_bricks");
            shapedRecipeStairs("red_nether_brick_stairs", Items.RED_NETHER_BRICK_STAIRS, "red_nether_bricks");
            shapedRecipeWall("red_nether_brick_wall", Items.RED_NETHER_BRICK_WALL, "red_nether_bricks");
            shapedRecipeSlab("quartz_slab", Items.QUARTZ_SLAB, "quartz_block");
            shapedRecipeStairs("quartz_stairs", Items.QUARTZ_STAIRS, "quartz_block");
            shapedRecipeSlab("smooth_quartz_slab", Items.SMOOTH_QUARTZ_SLAB, "smooth_quartz");
            shapedRecipeStairs("smooth_quartz_stairs", Items.SMOOTH_QUARTZ_STAIRS, "smooth_quartz");
            shapedRecipeSlab("blackstone_slab", Items.BLACKSTONE_SLAB, "blackstone");
            shapedRecipeStairs("blackstone_stairs", Items.BLACKSTONE_STAIRS, "blackstone");
            shapedRecipeWall("blackstone_wall", Items.BLACKSTONE_WALL, "blackstone");
            shapedRecipeSlab("polished_blackstone_slab", Items.POLISHED_BLACKSTONE_SLAB, "polished_blackstone");
            shapedRecipeStairs("polished_blackstone_stairs", Items.POLISHED_BLACKSTONE_STAIRS, "polished_blackstone");
            shapedRecipeWall("polished_blackstone_wall", Items.POLISHED_BLACKSTONE_WALL, "polished_blackstone");
            shapedRecipeSlab("polished_blackstone_brick_slab", Items.POLISHED_BLACKSTONE_BRICK_SLAB, "polished_blackstone_bricks");
            shapedRecipeStairs("polished_blackstone_brick_stairs", Items.POLISHED_BLACKSTONE_BRICK_STAIRS, "polished_blackstone_bricks");
            shapedRecipeWall("polished_blackstone_brick_wall", Items.POLISHED_BLACKSTONE_BRICK_WALL, "polished_blackstone_bricks");
            shapedRecipeSlab("cut_copper_slab", Items.CUT_COPPER_SLAB.weathering().unaffected(), "cut_copper");
            shapedRecipeStairs("cut_copper_stairs", Items.CUT_COPPER_STAIRS.weathering().unaffected(), "cut_copper");
            shapedRecipeSlab("cobbled_deepslate_slab", Items.COBBLED_DEEPSLATE_SLAB, "cobbled_deepslate");
            shapedRecipeStairs("cobbled_deepslate_stairs", Items.COBBLED_DEEPSLATE_STAIRS, "cobbled_deepslate");
            shapedRecipeWall("cobbled_deepslate_wall", Items.COBBLED_DEEPSLATE_WALL, "cobbled_deepslate");
            shapedRecipeSlab("polished_deepslate_slab", Items.POLISHED_DEEPSLATE_SLAB, "polished_deepslate");
            shapedRecipeStairs("polished_deepslate_stairs", Items.POLISHED_DEEPSLATE_STAIRS, "polished_deepslate");
            shapedRecipeWall("polished_deepslate_wall", Items.POLISHED_DEEPSLATE_WALL, "polished_deepslate");
            shapedRecipeSlab("deepslate_brick_slab", Items.DEEPSLATE_BRICK_SLAB, "deepslate_bricks");
            shapedRecipeStairs("deepslate_brick_stairs", Items.DEEPSLATE_BRICK_STAIRS, "deepslate_bricks");
            shapedRecipeWall("deepslate_brick_wall", Items.DEEPSLATE_BRICK_WALL, "deepslate_bricks");
            shapedRecipeSlab("deepslate_tile_slab", Items.DEEPSLATE_TILE_SLAB, "deepslate_tiles");
            shapedRecipeStairs("deepslate_tile_stairs", Items.DEEPSLATE_TILE_STAIRS, "deepslate_tiles");
            shapedRecipeWall("deepslate_tile_wall", Items.DEEPSLATE_TILE_WALL, "deepslate_tiles");
            shapedRecipe2x2("chiseled_sandstone", Items.CHISELED_SANDSTONE, 1, "sandstone_slab", o, "sandstone_slab", o);
            shapedRecipe2x2("chiseled_red_sandstone", Items.CHISELED_RED_SANDSTONE, 1, "red_sandstone_slab", o, "red_sandstone_slab", o);
            shapedRecipe2x2("chiseled_stone_bricks", Items.CHISELED_STONE_BRICKS, 1, "stone_brick_slab", o, "stone_brick_slab", o);
            shapedRecipe2x2("chiseled_nether_bricks", Items.CHISELED_NETHER_BRICKS, 1, "nether_brick_slab", o, "nether_brick_slab", o);
            shapedRecipe2x2("chiseled_quartz_block", Items.CHISELED_QUARTZ_BLOCK, 1, "quartz_slab", o, "quartz_slab", o);
            shapedRecipe2x2("chiseled_deepslate", Items.CHISELED_DEEPSLATE, 1, "cobbled_deepslate_slab", o, "cobbled_deepslate_slab", o);
            /// TOOLS
            tools("wooden", "planks", Items.WOODEN_PICKAXE, Items.WOODEN_SHOVEL, Items.WOODEN_SWORD, Items.WOODEN_AXE, Items.WOODEN_HOE);
            tools("stone", "stone_material", Items.STONE_PICKAXE, Items.STONE_SHOVEL, Items.STONE_SWORD, Items.STONE_AXE, Items.STONE_HOE);
            // the buddy's fork: copper tools mine what stone tools do (26.x gives them the same tier), wear longer and cut faster
            tools("copper", "copper_ingot", Items.COPPER_PICKAXE, Items.COPPER_SHOVEL, Items.COPPER_SWORD, Items.COPPER_AXE, Items.COPPER_HOE);
            tools("iron", "iron_ingot", Items.IRON_PICKAXE, Items.IRON_SHOVEL, Items.IRON_SWORD, Items.IRON_AXE, Items.IRON_HOE);
            tools("golden", "gold_ingot", Items.GOLDEN_PICKAXE, Items.GOLDEN_SHOVEL, Items.GOLDEN_SWORD, Items.GOLDEN_AXE, Items.GOLDEN_HOE);
            tools("diamond", "diamond", Items.DIAMOND_PICKAXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_SWORD, Items.DIAMOND_AXE, Items.DIAMOND_HOE);
            armor("leather", "leather", Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS);
            armor("copper", "copper_ingot", Items.COPPER_HELMET, Items.COPPER_CHESTPLATE, Items.COPPER_LEGGINGS, Items.COPPER_BOOTS);
            armor("iron", "iron_ingot", Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS);
            armor("golden", "gold_ingot", Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS);
            armor("diamond", "diamond", Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS);
            smith("netherite_helmet", Items.NETHERITE_HELMET, "netherite_ingot", "diamond_helmet");
            smith("netherite_chestplate", Items.NETHERITE_CHESTPLATE, "netherite_ingot", "diamond_chestplate");
            smith("netherite_leggings", Items.NETHERITE_LEGGINGS, "netherite_ingot", "diamond_leggings");
            smith("netherite_boots", Items.NETHERITE_BOOTS, "netherite_ingot", "diamond_boots");
            smith("netherite_pickaxe", Items.NETHERITE_PICKAXE, "netherite_ingot", "diamond_pickaxe");
            smith("netherite_axe", Items.NETHERITE_AXE, "netherite_ingot", "diamond_axe");
            smith("netherite_shovel", Items.NETHERITE_SHOVEL, "netherite_ingot", "diamond_shovel");
            smith("netherite_sword", Items.NETHERITE_SWORD, "netherite_ingot", "diamond_sword");
            smith("netherite_hoe", Items.NETHERITE_HOE, "netherite_ingot", "diamond_hoe");
            shapedRecipe3x3("bow", Items.BOW, 1, "string", s, o, "string", o, s, "string", s, o);
            shapedRecipe3x3("arrow", Items.ARROW, 4, "flint", o, o, s, o, o, "feather", o, o);
            {
                String i = "iron_ingot";
                shapedRecipe3x3("bucket", Items.BUCKET, 1, i, o, i, o, i, o, o, o, o);
                shapedRecipe2x2("flint_and_steel", Items.FLINT_AND_STEEL, 1, i, o, o, "flint");
                shapedRecipe2x2("shears", Items.SHEARS, 1, i, o, o, i);
                shapedRecipe2x2("iron_nugget", Items.IRON_NUGGET, 9, i, o, o, o);
                shapedRecipe3x3("compass", Items.COMPASS, 1, o, i, o, i, "redstone", i, o, i, o);
                shapedRecipe3x3("shield", Items.SHIELD, 1, p, i, p, p, p, p, o, p, o);
                String g = "gold_ingot";
                shapedRecipe3x3("clock", Items.CLOCK, 1, o, g, o, g, "redstone", g, o, g, o);
            }
            simple("water_bucket", Items.WATER_BUCKET, CollectBucketLiquidTask.CollectWaterBucketTask::new);
            simple("lava_bucket", Items.LAVA_BUCKET, CollectBucketLiquidTask.CollectLavaBucketTask::new);
            // a glass bottle filled at water, for the brewing stand. the item is any potion, the task counts water ones only
            simple("water_bottle", Items.POTION, CollectBucketLiquidTask.CollectWaterBottleTask::new);
            {
                String a = "paper";
                shapedRecipe3x3("map", Items.MAP, 1, a, a, a, a, "compass", a, a, a, a);
            }
            shapedRecipe3x3("fishing_rod", Items.FISHING_ROD, 1, o, o, s, o, s, "string", s, o, "string");
            shapedRecipe2x2("carrot_on_a_stick", Items.CARROT_ON_A_STICK, 1, "fishing_rod", o, o, "carrot");
            shapedRecipe2x2("warped_fungus_on_a_stick", Items.WARPED_FUNGUS_ON_A_STICK, 1, "fishing_rod", o, o, "warped_fungus");
            shapedRecipe3x3("spyglass", Items.SPYGLASS, 1, o, "amethyst_shard", o, o, "copper_ingot", o, o, "copper_ingot", o);
            shapedRecipe3x3("glass_bottle", Items.GLASS_BOTTLE, 3, "glass", o, "glass", o, "glass", o, o, o, o);
            {
                String l = "leather";
                shapedRecipe3x3("leather_horse_armor", Items.LEATHER_HORSE_ARMOR, 1, l, o, l, l, l, l, l, o, l);
            }
            alias("wooden_pick", "wooden_pickaxe");
            alias("stone_pick", "stone_pickaxe");
            alias("iron_pick", "iron_pickaxe");
            alias("gold_pick", "golden_pickaxe");
            alias("diamond_pick", "diamond_pickaxe");
            alias("netherite_pick", "netherite_pickaxe");
            simple("boat", ItemHelper.WOOD_BOAT, CollectBoatTask::new);
            woodTasks("boat", woodItems -> woodItems.boat, (woodItems, count) -> new CollectBoatTask(woodItems.boat, woodItems.prefix + "_planks", count));
            shapedRecipe3x3("lead", Items.LEAD, 2, "string", "string", o, "string", "string", o, o, o, "string");

            simple("honeycomb", Items.HONEYCOMB, CollectHoneycombTask::new);
            {
                String h = "honeycomb";
                shapedRecipe2x2Block("honeycomb_block", Items.HONEYCOMB_BLOCK, h);
                shapedRecipe2x2("candle", Items.CANDLE, 1, "string", o, h, o);
                shapedRecipe3x3("beehive", Items.BEEHIVE, 1, p, p, p, h, h, h, p, p, p);
            }

            // FURNITURE
            shapedRecipe2x2("crafting_table", Items.CRAFTING_TABLE, 1, p, p, p, p).dontMineIfPresent();
            shapedRecipe3x3("smithing_table", Items.SMITHING_TABLE, 1, "iron_ingot", "iron_ingot", o, p, p, o, p, p, o);
            shapedRecipe3x3("grindstone", Items.GRINDSTONE, 1, s, "stone_slab", s, p, o, p, o, o, o);
            simple("wooden_pressure_plate", ItemHelper.WOOD_PRESSURE_PLATE, CollectWoodenPressurePlateTask::new);
            woodTasks("pressure_plate", woodItems -> woodItems.pressurePlate, (woodItems, count) -> new CollectWoodenPressurePlateTask(woodItems.pressurePlate, woodItems.prefix + "_planks", count));
            simple("wooden_button", ItemHelper.WOOD_BUTTON, CollectWoodenButtonTask::new);
            woodButtons();
            shapedRecipe2x2("stone_pressure_plate", Items.STONE_PRESSURE_PLATE, 1, o, o, "stone", "stone");
            shapedRecipe2x2("stone_button", Items.STONE_BUTTON, 1, "stone", o, o, o);
            shapedRecipe2x2("polished_blackstone_pressure_plate", Items.POLISHED_BLACKSTONE_PRESSURE_PLATE, 1, o, o, "polished_blackstone", "polished_blackstone");
            shapedRecipe2x2("polished_blackstone_button", Items.POLISHED_BLACKSTONE_BUTTON, 1, "polished_blackstone", o, o, o);
            simple("sign", ItemHelper.WOOD_SIGN, CollectSignTask::new).dontMineIfPresent(); // By default, we save signs round these parts.
            woodTasks("sign", woodItems -> woodItems.sign, (woodItems, count) -> new CollectSignTask(woodItems.sign, woodItems.prefix + "_planks", count));
            {
                String c = "cobblestone";
                String sm = "stone_material";
                shapedRecipe3x3("furnace", Items.FURNACE, 1, sm, sm, sm, sm, o, sm, sm, sm, sm).dontMineIfPresent();
                shapedRecipe3x3("dropper", Items.DROPPER, 1, c, c, c, c, o, c, c, "redstone", c);
                shapedRecipe3x3("dispenser", Items.DISPENSER, 1, c, c, c, c, "bow", c, c, "redstone", c);
                // any stone the game takes (#stone_crafting_materials): the kit's blocks may be all cobbled deepslate, and the nether has no stone
                shapedRecipe3x3("brewing_stand", Items.BREWING_STAND, 1, o, o, o, o, "blaze_rod", o, sm, sm, sm);
                shapedRecipe3x3("piston", Items.PISTON, 1, p, p, p, c, "iron_ingot", c, c, "redstone", c);
                shapedRecipe3x3("observer", Items.OBSERVER, 1, c, c, c, "redstone", "redstone", "quartz", c, c, c);
                shapedRecipe2x2("lever", Items.LEVER, 1, s, o, c, o);
            }
            simple("hanging_sign", ItemHelper.WOOD_HANGING_SIGN, CollectHangingSignTask::new).dontMineIfPresent();
            woodTasks("hanging_sign", woodItems -> woodItems.hangingSign, (woodItems, count) -> new CollectHangingSignTask(woodItems.hangingSign, woodItems.prefix + "_stripped_logs", count));
            shapedRecipe3x3("chest", Items.CHEST, 1, p, p, p, p, o, p, p, p, p).dontMineIfPresent();
            shapedRecipe2x2("torch", Items.TORCH, 4, "coal", o, s, o);
            simple("bed", ItemHelper.BED, CollectBedTask::new);
            colorfulTasks("bed", colors -> colors.bed, (colors, count) -> new CollectBedTask(colors.bed, colors.colorName + "_wool", count));
            {
                String i = "iron_ingot";
                String b = "iron_block";
                String m = "smooth_stone";
                shapedRecipe3x3("anvil", Items.ANVIL, 1, b, b, b, o, i, o, i, i, i);
                shapedRecipe3x3("cauldron", Items.CAULDRON, 1, i, o, i, i, o, i, i, i, i);
                shapedRecipe3x3("minecart", Items.MINECART, 1, o, o, o, i, o, i, i, i, i);
                shapedRecipe3x3("iron_door", Items.IRON_DOOR, 3, i, i, o, i, i, o, i, i, o);
                shapedRecipe3x3("iron_bars", Items.IRON_BARS, 16, i, i, i, i, i, i, o, o, o);
                shapedRecipe3x3("blast_furnace", Items.BLAST_FURNACE, 1, i, i, i, i, "furnace", i, m, m, m);
                shapedRecipe2x2Block("iron_trapdoor", Items.IRON_TRAPDOOR, i);
            }
            shapedRecipe3x3("armor_stand", Items.ARMOR_STAND, 1, s, s, s, o, s, o, s, "smooth_stone_slab", s);
            {
                String b = "obsidian";
                shapedRecipe3x3("enchanting_table", Items.ENCHANTING_TABLE, 1, o, "book", o, "diamond", b, "diamond", b, b, b);
                shapedRecipe3x3("ender_chest", Items.ENDER_CHEST, 1, b, b, b, b, "ender_eye", b, b, b, b).dontMineIfPresent();
            }
            {
                String b = "brick";
                shapedRecipe3x3("decorated_pot", Items.DECORATED_POT, 1, o, b, o, b, o, b, o, b, o);
                shapedRecipe3x3("flower_pot", Items.FLOWER_POT, 1, b, o, b, o, b, o, o, o, o);
                shapedRecipe2x2Block("bricks", Items.BRICKS, b);
                shapedRecipeSlab("brick_slab", Items.BRICK_SLAB, "bricks");
                shapedRecipeStairs("brick_stairs", Items.BRICK_STAIRS, "bricks");
                shapedRecipeWall("brick_wall", Items.BRICK_WALL, "bricks");
            }
            shapedRecipe3x3("ladder", Items.LADDER, 3, s, o, s, s, s, s, s, o, s).dontMineIfPresent();
            shapedRecipe3x3("jukebox", Items.JUKEBOX, 1, p, p, p, p, "diamond", p, p, p, p);
            shapedRecipe3x3("note_block", Items.NOTE_BLOCK, 1, p, p, p, p, "redstone", p, p, p, p);
            shapedRecipe3x3("redstone_lamp", Items.REDSTONE_LAMP, 1, o, "redstone", o, "redstone", "glowstone", "redstone", o, "redstone", o);
            shapedRecipe3x3("bookshelf", Items.BOOKSHELF, 1, p, p, p, "book", "book", "book", p, p, p);
            shapedRecipe2x2("loom", Items.LOOM, 1, "string", "string", p, p);
            {
                String g = "glass";
                shapedRecipe3x3("glass_pane", Items.GLASS_PANE, 16, g, g, g, g, g, g, o, o, o).dontMineIfPresent();
            }
            simple("carved_pumpkin", Items.CARVED_PUMPKIN, count -> new CarveThenCollectTask(Items.CARVED_PUMPKIN, count, Blocks.CARVED_PUMPKIN, Items.PUMPKIN, Blocks.PUMPKIN, Items.SHEARS));
            shapedRecipe2x2("jack_o_lantern", Items.JACK_O_LANTERN, 1, "carved_pumpkin", o, "torch", o);
            shapedRecipe3x3("target", Items.TARGET, 1, o, "redstone", o, "redstone", "hay_block", "redstone", o, "redstone", o);
            shapedRecipe3x3("campfire", Items.CAMPFIRE, 1, o, s, o, s, "coal", s, "log", "log", "log");
            shapedRecipe3x3("soul_campfire", Items.SOUL_CAMPFIRE, 1, o, s, o, s, "soul_soil", s, "log", "log", "log");
            shapedRecipe3x3("soul_torch", Items.SOUL_TORCH, 4, o, "coal", o, o, s, o, o, "soul_soil", o);
            {
                String l = "log";
                shapedRecipe3x3("smoker", Items.SMOKER, 1, o, l, o, l, "furnace", l, o, l, o);
            }
            {
                String i = "iron_nugget";
                shapedRecipe3x3("lantern", Items.LANTERN, 1, i, i, i, i, "torch", i, i, i, i);
                shapedRecipe3x3("soul_lantern", Items.SOUL_LANTERN, 1, i, i, i, i, "soul_torch", i, i, i, i);
                shapedRecipe3x3("iron_chain", Items.IRON_CHAIN, 1, o, i, o, o, "iron_ingot", o, o, i, o);
                // vanilla renamed chain to iron_chain on 26.3 (copper has its own now), old name still typed by people
                alias("chain", "iron_chain");
            }
            {
                String c = "chiseled_stone_bricks";
                shapedRecipe3x3("lodestone", Items.LODESTONE, 1, c, c, c, c, "iron_ingot", c, c, c, c);
            }
            shapedRecipe3x3("lightning_rod", Items.LIGHTNING_ROD.weathering().unaffected(), 1, o, "copper_ingot", o, o, "copper_ingot", o, o, "copper_ingot", o);
            shapedRecipe3x3("tinted_glass", Items.TINTED_GLASS, 2, o, "amethyst_shard", o, "amethyst_shard", "glass", "amethyst_shard", o, "amethyst_shard", o);

            // A BUNCH OF WOODEN STUFF
            simple("wooden_stairs", ItemHelper.WOOD_STAIRS, CollectWoodenStairsTask::new);
            woodTasks("stairs", woodItems -> woodItems.stairs, (woodItems, count) -> new CollectWoodenStairsTask(woodItems.stairs, woodItems.prefix + "_planks", count));
            simple("wooden_slab", ItemHelper.WOOD_SLAB, CollectWoodenSlabTask::new);
            woodTasks("slab", woodItems -> woodItems.slab, (woodItems, count) -> new CollectWoodenSlabTask(woodItems.slab, woodItems.prefix + "_planks", count));
            simple("wooden_door", ItemHelper.WOOD_DOOR, CollectWoodenDoorTask::new);
            woodTasks("door", woodItems -> woodItems.door, (woodItems, count) -> new CollectWoodenDoorTask(woodItems.door, woodItems.prefix + "_planks", count));
            simple("wooden_trapdoor", ItemHelper.WOOD_TRAPDOOR, CollectWoodenTrapDoorTask::new);
            woodTasks("trapdoor", woodItems -> woodItems.trapdoor, (woodItems, count) -> new CollectWoodenTrapDoorTask(woodItems.trapdoor, woodItems.prefix + "_planks", count));
            simple("wooden_fence", ItemHelper.WOOD_FENCE, CollectFenceTask::new);
            woodTasks("fence", woodItems -> woodItems.fence, (woodItems, count) -> new CollectFenceTask(woodItems.fence, woodItems.prefix + "_planks", count));
            simple("wooden_fence_gate", ItemHelper.WOOD_FENCE_GATE, CollectFenceGateTask::new);
            woodTasks("fence_gate", woodItems -> woodItems.fenceGate, (woodItems, count) -> new CollectFenceGateTask(woodItems.fenceGate, woodItems.prefix + "_planks", count));
            woodRecipes();
            schematicBlocks();
            {
                String r = "wooden_slab";
                shapedRecipe3x3("chiseled_bookshelf", Items.CHISELED_BOOKSHELF, 1, p, p, p, r, r, r, p, p, p).dontMineIfPresent();
                shapedRecipe3x3("barrel", Items.BARREL, 1, p, r, p, p, o, p, p, r, p);
                shapedRecipe3x3("cartography_table", Items.CARTOGRAPHY_TABLE, 1, "paper", "paper", o, p, p, o, p, p, o);
                shapedRecipe3x3("composter", Items.COMPOSTER, 1, r, o, r, r, o, r, r, r, r);
                shapedRecipe3x3("fletching_table", Items.FLETCHING_TABLE, 1, "flint", "flint", o, p, p, o, p, p, o);
                shapedRecipe3x3("lectern", Items.LECTERN, 1, r, r, r, o, "bookshelf", o, o, r, o);
            }            // Most people will always think "wooden door" when they say "door".
            alias("door", "wooden_door");
            alias("trapdoor", "wooden_trapdoor");
            alias("fence", "wooden_fence");
            alias("fence_gate", "wooden_fence_gate");

            shapedRecipe2x2("heavy_weighted_pressure_plate", Items.HEAVY_WEIGHTED_PRESSURE_PLATE, 1, "iron_ingot", "iron_ingot", o, o);
            shapedRecipe2x2("light_weighted_pressure_plate", Items.LIGHT_WEIGHTED_PRESSURE_PLATE, 1, "gold_ingot", "gold_ingot", o, o);

            shapedRecipe3x3("daylight_detector", Items.DAYLIGHT_DETECTOR, 1, "glass", "glass", "glass", "quartz", "quartz", "quartz", "wooden_slab", "wooden_slab", "wooden_slab");
            shapedRecipe3x3("tripwire_hook", Items.TRIPWIRE_HOOK, 2, "iron_ingot", o, o, "stick", o, o, "planks", o, o);
            shapedRecipe2x2("trapped_chest", Items.TRAPPED_CHEST, 1, "chest", "tripwire_hook", o, o);
            shapedRecipe3x3("crossbow", Items.CROSSBOW, 1, s, "iron_ingot", s, "string", "tripwire_hook", "string", o, s, o);
            {
                String t = "gunpowder";
                String n = "sand";
                shapedRecipe3x3("tnt", Items.TNT, 1, t, n, t, n, t, n, t, n, t);
            }
            shapedRecipe2x2("sticky_piston", Items.STICKY_PISTON, 1, "slime_ball", o, "piston", o);
            shapedRecipe2x2("redstone_torch", Items.REDSTONE_TORCH, 1, "redstone", o, s, o);
            shapedRecipe3x3("repeater", Items.REPEATER, 1, "redstone_torch", "redstone", "redstone_torch", "stone", "stone", "stone", o, o, o);
            shapedRecipe3x3("comparator", Items.COMPARATOR, 1, o, "redstone_torch", o, "redstone_torch", "quartz", "redstone_torch", "stone", "stone", "stone");
            {
                // Some rails
                String i = "iron_ingot";
                String g = "gold_ingot";
                shapedRecipe3x3("rail", Items.RAIL, 16, i, o, i, i, s, i, i, o, i);
                shapedRecipe3x3("powered_rail", Items.POWERED_RAIL, 6, g, o, g, g, s, g, g, "redstone", g);
                shapedRecipe3x3("detector_rail", Items.DETECTOR_RAIL, 6, i, o, i, i, "stone_pressure_plate", i, i, "redstone", i);
                shapedRecipe3x3("activator_rail", Items.ACTIVATOR_RAIL, 6, i, s, i, i, "redstone_torch", i, i, s, i);
                shapedRecipe3x3("hopper", Items.HOPPER, 1, i, o, i, i, "chest", i, o, i, o);
            }
            shapedRecipe3x3("painting", Items.PAINTING, 1, s, s, s, s, "wool", s, s, s, s);
            shapedRecipe3x3("item_frame", Items.ITEM_FRAME, 1, s, s, s, s, "leather", s, s, s, s);
            shapedRecipe2x2("glow_item_frame", Items.GLOW_ITEM_FRAME, 1, "item_frame", "glow_ink_sac", o, o);
            shapedRecipe2x2("chest_minecart", Items.CHEST_MINECART, 1, "chest", o, "minecart", o);
            shapedRecipe2x2("furnace_minecart", Items.FURNACE_MINECART, 1, "furnace", o, "minecart", o);
            shapedRecipe2x2("hopper_minecart", Items.HOPPER_MINECART, 1, "hopper", o, "minecart", o);
            shapedRecipe2x2("tnt_minecart", Items.TNT_MINECART, 1, "tnt", o, "minecart", o);
            alias("minecart_with_chest", "chest_minecart");
            alias("minecart_with_furnace", "furnace_minecart");
            alias("minecart_with_hopper", "hopper_minecart");
            alias("minecart_with_tnt", "tnt_minecart");


            /// FOOD
            mobCook("porkchop", Items.PORKCHOP, Items.COOKED_PORKCHOP, Pig.class);
            mobCook("beef", Items.BEEF, Items.COOKED_BEEF, Cow.class);
            mobCook("chicken", Items.CHICKEN, Items.COOKED_CHICKEN, Chicken.class);
            mobCook("mutton", Items.MUTTON, Items.COOKED_MUTTON, Sheep.class);
            mobCook("rabbit", Items.RABBIT, Items.COOKED_RABBIT, Rabbit.class);
            mobCook("salmon", Items.SALMON, Items.COOKED_SALMON, Salmon.class);
            mobCook("cod", Items.COD, Items.COOKED_COD, Cod.class);
            simple("milk", Items.MILK_BUCKET, CollectMilkTask::new);
            mine("apple", Blocks.OAK_LEAVES, Items.APPLE);
            smelt("baked_potato", Items.BAKED_POTATO, "potato");
            // the buddy's fork: kelp, dried in a smoker, an ocean's food
            mine("kelp", MiningRequirement.HAND, new Block[]{Blocks.KELP, Blocks.KELP_PLANT}, Items.KELP);
            smelt("dried_kelp", Items.DRIED_KELP, "kelp");
            shapedRecipe2x2("mushroom_stew", Items.MUSHROOM_STEW, 1, "red_mushroom", "brown_mushroom", "bowl", o);
            shapedRecipe2x2("suspicious_stew", Items.SUSPICIOUS_STEW, 1, "red_mushroom", "brown_mushroom", "bowl", "suspicious_stew_flower");
            shapedRecipe3x3("bread", Items.BREAD, 1, "wheat", "wheat", "wheat", o, o, o, o, o, o);
            shapedRecipe3x3("cookie", Items.COOKIE, 8, "wheat", "cocoa_beans", "wheat", o, o, o, o, o, o);
            shapedRecipe2x2("pumpkin_pie", Items.PUMPKIN_PIE, 1, "pumpkin", "sugar", o, "egg");
            shapedRecipe3x3("cake", Items.CAKE, 1, "milk", "milk", "milk", "sugar", "egg", "sugar", "wheat", "wheat", "wheat").dontMineIfPresent();
            {
                String g = "gold_nugget";
                shapedRecipe3x3("golden_carrot", Items.GOLDEN_CARROT, 1, g, g, g, g, "carrot", g, g, g, g);
                String i = "gold_ingot";
                shapedRecipe3x3("golden_apple", Items.GOLDEN_APPLE, 1, i, i, i, i, "apple", i, i, i, i);
            }
            shapedRecipe3x3("rabbit_stew", Items.RABBIT_STEW, 1, o, "cooked_rabbit", o, "carrot", "baked_potato", "mushroom", o, "bowl", o);
            {
                String b = "beetroot";
                shapedRecipe3x3("beetroot_soup", Items.BEETROOT_SOUP, 1, b, b, b, b, b, b, o, "bowl", o);
            }
        }
    }

    private static CataloguedResource put(String name, Item[] matches, Function<Integer, ResourceTask> getTask) {
        CataloguedResource result = new CataloguedResource(matches, getTask);
        Block[] blocks = ItemHelper.itemsToBlocks(matches);
        // DEFAULT BEHAVIOUR: Mine if present & assume overworld is required!
        if (blocks.length != 0) {
            result.mineIfPresent();
        }
        result.forceDimension(Dimension.OVERWORLD);
        if (_nameToResourceTask.containsKey(name)) {
            throw new IllegalStateException("Tried cataloguing " + name + " twice!");
        }
        _nameToResourceTask.put(name, result);
        _nameToItemMatches.put(name, matches);
        _resourcesObtainable.addAll(Arrays.asList(matches));

        // If this resource is just one item, consider it collectable.
        if (matches.length == 1) {
            if (_itemToResourceTask.containsKey(matches[0])) {
                throw new IllegalStateException("Tried cataloguing " + matches[0].getDescriptionId() + " twice!");
            }
            _itemToResourceTask.put(matches[0], result);
            _itemToName.put(matches[0], name);
        }

        return result;
    }

    // This is here so that we can use strings for item targets (optionally) and stuff like that.
    public static Item[] getItemMatches(String name) {
        if (!_nameToItemMatches.containsKey(name)) {
            return new Item[0];
        }
        return _nameToItemMatches.get(name);
    }

    public static boolean isObtainable(Item item) {
        return _resourcesObtainable.contains(item);
    }

    public static ItemTarget getItemTarget(String name, int count) {
        return new ItemTarget(name, count);
    }

    public static CataloguedResourceTask getSquashedItemTask(ItemTarget... targets) {
        return new CataloguedResourceTask(true, targets);
    }

    public static ResourceTask getItemTask(String name, int count) {

        if (!taskExists(name)) {
            Debug.logWarning("Task " + name + " does not exist. Error possibly.");
            Debug.logStack();
            return null;
        }

        return _nameToResourceTask.get(name).getResource(count);
    }

    public static ResourceTask getItemTask(Item item, int count) {
        if (!taskExists(item)) {
            Debug.logWarning("Task " + item + " does not exist. Error possibly.");
            Debug.logStack();
            return null;
        }

        return _itemToResourceTask.get(item).getResource(count);
    }

    public static ResourceTask getItemTask(ItemTarget target) {
        if (target.isCatalogueItem()) {
            return getItemTask(target.getCatalogueName(), target.getTargetCount());
        } else if (target.getMatches().length == 1) {
            return getItemTask(target.getMatches()[0], target.getTargetCount());
        } else {
            return getSquashedItemTask(target);
        }
    }

    public static boolean taskExists(String name) {
        return _nameToResourceTask.containsKey(name);
    }

    public static boolean taskExists(Item item) {
        return _itemToResourceTask.containsKey(item);
    }

    // name -> single craft target for every recipe the shaped helpers (and the wood buttons) registered.
    // the recipe parity test needs these, and it can't just ask for the tasks: building any Task wants the live player, which a headless test doesn't have
    static Map<String, RecipeTarget> craftedRecipes() {
        return Collections.unmodifiableMap(_craftedRecipes);
    }

    // the recipe a shaped catalogue item is crafted with (null for anything else), for a caller that has to craft from the bag
    // and must not go through the catalogue's tasks, which collect whatever the bag is short of
    public static RecipeTarget recipeOf(String name) {
        return _craftedRecipes.get(name);
    }

    // whether getting this item sends the bot to the nether: its entry is pinned there (blaze rods, quartz), or something
    // it's crafted, smelted or smithed from is (a brewing stand is a blaze rod with extra steps). gold isn't, its entry
    // goes anywhere and mines the overworld ore. false for anything the catalogue doesn't know
    public static boolean needsNether(Item item) {
        String name = _itemToName.get(item);
        return name != null && needsNether(name, new HashSet<>());
    }

    private static boolean needsNether(String name, Set<String> visiting) {
        Boolean known = _needsNether.get(name);
        if (known != null) {
            return known;
        }
        // a loop in the graph is no road to the nether. the answer isn't kept while the loop is open, the outer call
        // that closes it knows better
        if (!visiting.add(name)) {
            return false;
        }
        boolean out = netherHere(name, visiting);
        visiting.remove(name);
        if (out || visiting.isEmpty()) {
            _needsNether.put(name, out);
        }
        return out;
    }

    private static boolean netherHere(String name, Set<String> visiting) {
        CataloguedResource entry = _nameToResourceTask.get(name);
        if (NETHER_BY_HAND.contains(name) || (entry != null && entry.forcesNether())) {
            return true;
        }
        RecipeTarget recipe = _craftedRecipes.get(name);
        if (recipe != null) {
            for (ItemTarget slot : recipe.getRecipe().getSlots()) {
                if (slot != null && slotNeedsNether(slot, visiting)) {
                    return true;
                }
            }
        }
        for (String from : _madeFrom.getOrDefault(name, List.of())) {
            if (needsNether(from, visiting)) {
                return true;
            }
        }
        return false;
    }

    // a slot that takes "planks" is happy with oak, so a group only counts when every item in it is nether only
    private static boolean slotNeedsNether(ItemTarget slot, Set<String> visiting) {
        if (slot.isCatalogueItem()) {
            String name = slot.getCatalogueName();
            Item[] matches = getItemMatches(name);
            CataloguedResource entry = _nameToResourceTask.get(name);
            if (matches.length <= 1 || entry == null || _craftedRecipes.containsKey(name)) {
                return needsNether(name, visiting);
            }
            return entry.forcesNether() || allNeedNether(matches, visiting);
        }
        return allNeedNether(slot.getMatches(), visiting);
    }

    private static boolean allNeedNether(Item[] matches, Set<String> visiting) {
        if (matches.length == 0) {
            return false;
        }
        for (Item item : matches) {
            String name = _itemToName.get(item);
            if (name == null || !needsNether(name, visiting)) {
                return false;
            }
        }
        return true;
    }

    public static Collection<String> resourceNames() {
        return _nameToResourceTask.keySet();
    }

    public static boolean isAlias(String name) {
        return _aliases.containsKey(name);
    }

    // alias -> the name it stands for
    public static Map<String, String> aliases() {
        return Collections.unmodifiableMap(_aliases);
    }

    private static CataloguedResource simple(String name, Item[] matches, Function<Integer, ResourceTask> getTask) {
        return put(name, matches, getTask);
    }

    private static CataloguedResource simple(String name, Item matches, Function<Integer, ResourceTask> getTask) {
        return simple(name, new Item[]{matches}, getTask);
    }

    // TODO: Do I really need this?
    //private static CataloguedResource task(Item matches, Function<Integer, ResourceTask> getTask){
    //
    //}

    private static CataloguedResource mine(String name, MiningRequirement requirement, Item[] toMine, Item... targets) {
        Block[] toMineBlocks = new Block[toMine.length];
        for (int i = 0; i < toMine.length; ++i) toMineBlocks[i] = Block.byItem(toMine[i]);
        return mine(name, requirement, toMineBlocks, targets);
    }

    private static CataloguedResource mine(String name, MiningRequirement requirement, Block[] toMine, Item... targets) {
        BillBook.GATHER.put(name, requirement);
        return put(name, targets, count -> new MineAndCollectTask(new ItemTarget(targets, count), toMine, requirement)).dontMineIfPresent(); // Mining already taken care of!!
    }

    // mine() for an ore: several of these in one CataloguedResourceTask get mined as one (MineAndCollectTask.together).
    // only ores. logs and stone have their own tree and dig logic that a mixed block list turns off
    private static CataloguedResource ore(String name, MiningRequirement requirement, Block[] toMine, Item... targets) {
        BillBook.GATHER.put(name, requirement);
        return put(name, targets, count -> new MineAndCollectTask(new ItemTarget(targets, count), toMine, requirement).ore()).dontMineIfPresent();
    }

    private static CataloguedResource mine(String name, MiningRequirement requirement, Block toMine, Item target) {
        return mine(name, requirement, new Block[]{toMine}, target);
    }

    private static CataloguedResource mine(String name, Block toMine, Item target) {
        return mine(name, MiningRequirement.HAND, toMine, target);
    }

    private static CataloguedResource mine(String name, Item target) {
        return mine(name, Block.byItem(target), target);
    }

    private static CataloguedResource shear(String name, Block[] toShear, Item... targets) {
        return put(name, targets, count -> new ShearAndCollectBlockTask(new ItemTarget[]{new ItemTarget(targets, count)}, toShear)).dontMineIfPresent();
    }

    private static CataloguedResource shear(String name, Block toShear, Item... targets) {
        return shear(name, new Block[]{toShear}, targets);
    }

    private static CataloguedResource shapedRecipe2x2(String name, Item match, int outputCount, String s0, String s1, String s2, String s3) {
        CraftingRecipe recipe = CraftingRecipe.newShapedRecipe(name, new ItemTarget[]{t(s0), t(s1), t(s2), t(s3)}, outputCount);
        _craftedRecipes.put(name, new RecipeTarget(match, 1, recipe));
        return put(name, new Item[]{match}, count -> new CraftInInventoryTask(new RecipeTarget(match, count, recipe)));
    }

    private static CataloguedResource shapedRecipe3x3(String name, Item match, int outputCount, String s0, String s1, String s2, String s3, String s4, String s5, String s6, String s7, String s8) {
        CraftingRecipe recipe = CraftingRecipe.newShapedRecipe(name, new ItemTarget[]{t(s0), t(s1), t(s2), t(s3), t(s4), t(s5), t(s6), t(s7), t(s8)}, outputCount);
        _craftedRecipes.put(name, new RecipeTarget(match, 1, recipe));
        return put(name, new Item[]{match}, count -> new CraftInTableTask(new RecipeTarget(match, count, recipe)));
    }

    private static CataloguedResource shapedRecipe2x2Block(String name, Item match, String material) {
        return shapedRecipe2x2(name, match, 1, material, material, material, material);
    }

    private static CataloguedResource shapedRecipe2x2Block(String name, Item match, int outputCount, String material) {
        return shapedRecipe2x2(name, match, outputCount, material, material, material, material);
    }

    private static CataloguedResource shapedRecipe3x3Block(String name, Item match, String material) {
        return shapedRecipe3x3(name, match, 1, material, material, material, material, material, material, material, material, material);
    }

    private static CataloguedResource shapedRecipeSlab(String name, Item match, String material) {
        return shapedRecipe3x3(name, match, 6, null, null, null, null, null, null, material, material, material);
    }

    private static CataloguedResource shapedRecipeStairs(String name, Item match, String material) {
        return shapedRecipe3x3(name, match, 4, material, null, null, material, material, null, material, material, material);
    }

    private static CataloguedResource shapedRecipeWall(String name, Item match, String material) {
        return shapedRecipe3x3(name, match, 6, material, material, material, material, material, material, null, null, null);
    }

    private static CataloguedResource smelt(String name, Item[] matches, String materials, Item... optionalMaterials) {
        _madeFrom.put(name, List.of(materials));
        return put(name, matches, count -> new SmeltInFurnaceTask(new SmeltTarget(new ItemTarget(matches, count), new ItemTarget(materials, count), optionalMaterials)));
    }

    private static CataloguedResource smelt(String name, Item match, String materials, Item... optionalMaterials) {
        BillBook.EDGES.put(name, new BillEdge(materials, 1, 1, BillEdge.Step.SMELT));
        return smelt(name, new Item[]{match}, materials, optionalMaterials);
    }

    private static CataloguedResource smith(String name, Item[] matches, String materials, String tool) {
        _madeFrom.put(name, List.of(materials, tool));
        return put(name, matches, count -> new UpgradeInSmithingTableTask(new ItemTarget(tool, count), new ItemTarget(materials, count), new ItemTarget(matches, count)));//new SmeltInFurnaceTask(new SmeltTarget(new ItemTarget(matches, count), new ItemTarget(materials, count))));
    }

    private static CataloguedResource smith(String name, Item match, String materials, String tool) {
        return smith(name, new Item[]{match}, materials, tool);
    }

    private static CataloguedResource mob(String name, Item[] matches, Class mobClass) {
        return put(name, matches, count -> new KillAndLootTask(mobClass, new ItemTarget(matches, count)));
    }

    private static CataloguedResource mob(String name, Item match, Class mobClass) {
        return mob(name, new Item[]{match}, mobClass);
    }

    private static void mobCook(String uncookedName, String cookedName, Item uncooked, Item cooked, Class mobClass) {
        mob(uncookedName, uncooked, mobClass);
        smelt(cookedName, cooked, uncookedName);
    }

    private static void mobCook(String uncookedName, Item uncooked, Item cooked, Class mobClass) {
        mobCook(uncookedName, "cooked_" + uncookedName, uncooked, cooked, mobClass);
    }

    private static CataloguedResource crop(String name, Item[] matches, Block[] cropBlocks, Item[] cropSeeds) {
        return put(name, matches, count -> new CollectCropTask(new ItemTarget(matches, count), cropBlocks, cropSeeds));
    }

    public static CataloguedResource crop(String name, Item match, Block cropBlock, Item cropSeed) {
        return crop(name, new Item[]{match}, new Block[]{cropBlock}, new Item[]{cropSeed});
    }

    private static void colorfulTasks(String baseName, Function<ItemHelper.ColorfulItems, Item> getMatch, BiFunction<ItemHelper.ColorfulItems, Integer, ResourceTask> getTask) {
        for (DyeColor dCol : DyeColor.values()) {
            MapColor mCol = dCol.getMapColor();
            ItemHelper.ColorfulItems color = ItemHelper.getColorfulItems(mCol);
            String prefix = color.colorName;
            put(prefix + "_" + baseName, new Item[]{getMatch.apply(color)}, count -> getTask.apply(color, count));
        }
    }

    private static CataloguedResource[] woodTasks(Function<ItemHelper.WoodItems, String> getCatalogueName, Function<ItemHelper.WoodItems, Item> getMatch, BiFunction<ItemHelper.WoodItems, Integer, ResourceTask> getTask, boolean requireNetherForNetherStuff) {
        List<CataloguedResource> result = new ArrayList<>();
        for (WoodType woodType : WoodType.values()) {
            ItemHelper.WoodItems woodItems = ItemHelper.getWoodItems(woodType);
            Item match = getMatch.apply(woodItems);
            String cataloguedName = getCatalogueName.apply(woodItems);
            if (match == null) continue;
            boolean isNether = woodItems.isNetherWood();
            CataloguedResource t = put(cataloguedName, new Item[]{match}, count -> getTask.apply(woodItems, count));
            if (requireNetherForNetherStuff && isNether) {
                t.forceDimension(Dimension.NETHER);
            } else if (isNether) {
                // the crimson stairs and doors go through lambdas, not the recipe helpers, so needsNether would never
                // see them. they're all crimson planks in the end
                _madeFrom.putIfAbsent(cataloguedName, List.of(woodItems.prefix + "_planks"));
            }
            result.add(t);
        }
        return result.toArray(CataloguedResource[]::new);
    }

    private static CataloguedResource[] woodTasks(String baseName, Function<ItemHelper.WoodItems, Item> getMatch, BiFunction<ItemHelper.WoodItems, Integer, ResourceTask> getTask, boolean requireNetherForNetherStuff) {
        return woodTasks(woodItem -> woodItem.prefix + "_" + baseName, getMatch, getTask, requireNetherForNetherStuff);
    }

    private static CataloguedResource[] woodTasks(String baseName, Function<ItemHelper.WoodItems, Item> getMatch, BiFunction<ItemHelper.WoodItems, Integer, ResourceTask> getTask) {
        return woodTasks(baseName, getMatch, getTask, false);
    }

    // blocks and dyes a schematic build can ask for. gettable == "has an entry", so a coloured craft only gets one when its dye
    // really has a source, or the build stalls waiting on a block nothing can make. dyes go in first for that reason
    private static void schematicBlocks() {
        String o = null;
        // one flower, one dye. sourced from the mine() flowers above, ink sacs (black) and cocoa (brown) stay out
        dye(DyeColor.WHITE, 1, "bone_meal", o);
        dye(DyeColor.YELLOW, 1, "dandelion", o);
        dye(DyeColor.RED, 1, "poppy", o);
        dye(DyeColor.BLUE, 1, "lapis_lazuli", o);
        dye(DyeColor.MAGENTA, 1, "allium", o);
        dye(DyeColor.LIGHT_GRAY, 1, "azure_bluet", o);
        dye(DyeColor.ORANGE, 1, "orange_tulip", o);
        dye(DyeColor.PINK, 1, "pink_tulip", o);
        dye(DyeColor.LIGHT_BLUE, 1, "blue_orchid", o);
        // the mixes, once both parents above exist (green_dye is the cactus smelt)
        dye(DyeColor.PURPLE, 2, "blue_dye", "red_dye");
        dye(DyeColor.CYAN, 2, "blue_dye", "green_dye");
        dye(DyeColor.LIME, 2, "green_dye", "white_dye");

        smelt("terracotta", Items.TERRACOTTA, "clay").dontMineIfPresent();
        for (DyeColor dCol : DyeColor.values()) {
            ItemHelper.ColorfulItems c = ItemHelper.getColorfulItems(dCol);
            String color = c.colorName;
            // carpet only needs wool, which every colour has an entry for
            if (_nameToResourceTask.containsKey(color + "_wool")) {
                shapedRecipe2x2(color + "_carpet", c.carpet, 3, color + "_wool", color + "_wool", null, null).dontMineIfPresent();
            }
            String dye = color + "_dye";
            if (!_nameToResourceTask.containsKey(dye)) {
                continue;
            }
            String t = "terracotta";
            shapedRecipe3x3(color + "_terracotta", c.terracotta, 8, t, t, t, t, dye, t, t, t, t).dontMineIfPresent();
            smelt(color + "_glazed_terracotta", c.glazedTerracotta, color + "_terracotta").dontMineIfPresent();
            String g = "glass";
            shapedRecipe3x3(color + "_stained_glass", c.stainedGlass, 8, g, g, g, g, dye, g, g, g, g).dontMineIfPresent();
            String sg = color + "_stained_glass";
            shapedRecipe3x3(color + "_stained_glass_pane", c.stainedGlassPane, 16, null, null, null, sg, sg, sg, sg, sg, sg).dontMineIfPresent();
        }
        // wood blocks are 4 logs -> 3, stripped the same. bamboo has none (null). keyed by the vanilla id (crimson_hyphae etc.),
        // the log inputs keep the catalogue's _log names
        for (WoodType woodType : WoodType.values()) {
            ItemHelper.WoodItems w = ItemHelper.getWoodItems(woodType);
            if (w.wood != null && w.log != null) {
                String l = w.prefix + "_log";
                shapedRecipe2x2(BuiltInRegistries.ITEM.getKey(w.wood).getPath(), w.wood, 3, l, l, l, l).dontMineIfPresent();
            }
            // the stripped stems only get collected by walking the overworld, so the nether ones would wait forever
            if (w.strippedWood != null && w.strippedLog != null && !w.isNetherWood()) {
                String l = w.prefix + "_stripped_logs";
                shapedRecipe2x2(BuiltInRegistries.ITEM.getKey(w.strippedWood).getPath(), w.strippedWood, 3, l, l, l, l).dontMineIfPresent();
            }
        }
    }

    private static void dye(DyeColor color, int out, String a, String b) {
        ItemHelper.ColorfulItems c = ItemHelper.getColorfulItems(color);
        shapedRecipe2x2(c.colorName + "_dye", c.dye, out, a, b, null, null);
    }

    // woodTasks, but eager, so the recipe is in _craftedRecipes without anyone asking for a task.
    // the planks come from the catalogue name because bamboo has no planks field in WoodItems (it can't, bamboo_planks is registered by hand)
    private static void woodButtons() {
        for (WoodType woodType : WoodType.values()) {
            ItemHelper.WoodItems woodItems = ItemHelper.getWoodItems(woodType);
            if (woodItems.button == null) continue;
            String name = woodItems.prefix + "_button";
            CraftingRecipe recipe = CraftingRecipe.newShapedRecipe(name, new ItemTarget[]{t(woodItems.prefix + "_planks"), null, null, null}, 1);
            _craftedRecipes.put(name, new RecipeTarget(woodItems.button, 1, recipe));
            put(name, new Item[]{woodItems.button}, count -> new CraftInInventoryTask(new RecipeTarget(woodItems.button, count, recipe)));
        }
    }

    // the wood blocks keep their Collect*Task (woodTasks above), this only writes down the recipe so the gather bill can see
    // them. without it stairs and doors were leaves nobody planned for, so the planks they eat never made it onto the log trip.
    // same shapes as the Collect*Task classes. bamboo has no planks field, so it stays out (its stuff is bamboo_planks)
    private static void woodRecipes() {
        ItemTarget s = t("stick");
        ItemTarget o = null;
        for (WoodType woodType : WoodType.values()) {
            ItemHelper.WoodItems w = ItemHelper.getWoodItems(woodType);
            if (w.planks == null) continue;
            ItemTarget p = t(w.prefix + "_planks");
            woodRecipe(w.prefix + "_stairs", w.stairs, 4, p, o, o, p, p, o, p, p, p);
            woodRecipe(w.prefix + "_slab", w.slab, 6, p, p, p, o, o, o, o, o, o);
            woodRecipe(w.prefix + "_fence", w.fence, 3, p, s, p, p, s, p, o, o, o);
            woodRecipe(w.prefix + "_fence_gate", w.fenceGate, 1, s, p, s, s, p, s, o, o, o);
            woodRecipe(w.prefix + "_door", w.door, 3, p, p, o, p, p, o, p, p, o);
            woodRecipe(w.prefix + "_trapdoor", w.trapdoor, 2, p, p, p, p, p, p, o, o, o);
            woodRecipe(w.prefix + "_sign", w.sign, 3, p, p, p, p, p, p, o, s, o);
            woodRecipe(w.prefix + "_pressure_plate", w.pressurePlate, 1, p, p, o, o);
        }
    }

    // recipe only, the task routing stays whatever woodTasks registered
    private static void woodRecipe(String name, Item match, int out, ItemTarget... slots) {
        if (match == null) return;
        _craftedRecipes.put(name, new RecipeTarget(match, 1, CraftingRecipe.newShapedRecipe(name, slots, out)));
    }

    // one step the gather bill (RecipeBill) can walk that isn't a shaped recipe: a run eats inPerRun of input and makes
    // outPerRun. smelts are 1:1, a log is 4 planks, 2 planks are 4 sticks, a bucket is a water bucket once you dunk it
    public record BillEdge(String input, int inPerRun, int outPerRun, Step step) {
        public enum Step {CRAFT, SMELT, FILL}
    }

    // the step for name, null when it has none (shaped recipes are recipeOf, everything else is opaque)
    public static BillEdge billEdge(String name) {
        return BillBook.EDGES.get(name);
    }

    // the pickaxe a plain mine of name needs, or null when the bill shouldn't fetch it up front: mob drops, crops, shears,
    // the custom tasks, and anything whose entry walks to the nether first. those keep doing what they always did
    public static MiningRequirement gatherRequirement(String name) {
        CataloguedResource entry = _nameToResourceTask.get(name);
        if (entry == null || entry.forcesNether()) {
            return null;
        }
        return BillBook.GATHER.get(name);
    }

    // a holder class so the maps exist while the static block up top is still running. a plain static field down here
    // is initialized after that block, so smelt() and mine() would be writing into null
    private static final class BillBook {
        static final HashMap<String, BillEdge> EDGES = new HashMap<>();
        // what may be gathered up front and with which pickaxe. mine() adds every plain block it registers
        static final HashMap<String, MiningRequirement> GATHER = new HashMap<>();

        static {
            // the ingots are custom tasks, not smelt(), but a smelt is what they are
            EDGES.put("iron_ingot", new BillEdge("raw_iron", 1, 1, BillEdge.Step.SMELT));
            EDGES.put("gold_ingot", new BillEdge("raw_gold", 1, 1, BillEdge.Step.SMELT));
            EDGES.put("planks", new BillEdge("log", 1, 4, BillEdge.Step.CRAFT));
            EDGES.put("stick", new BillEdge("planks", 2, 4, BillEdge.Step.CRAFT));
            EDGES.put("water_bucket", new BillEdge("bucket", 1, 1, BillEdge.Step.FILL));
            EDGES.put("lava_bucket", new BillEdge("bucket", 1, 1, BillEdge.Step.FILL));
            EDGES.put("water_bottle", new BillEdge("glass_bottle", 1, 1, BillEdge.Step.FILL));
            GATHER.put("log", MiningRequirement.HAND);
            GATHER.put("cobblestone", MiningRequirement.WOOD);
            GATHER.put("cobbled_deepslate", MiningRequirement.WOOD);
            GATHER.put("stone_material", MiningRequirement.WOOD);
            for (WoodType woodType : WoodType.values()) {
                ItemHelper.WoodItems wood = ItemHelper.getWoodItems(woodType);
                if (wood.planks == null || wood.log == null) continue;
                EDGES.put(wood.prefix + "_planks", new BillEdge(wood.prefix + "_log", 1, 4, BillEdge.Step.CRAFT));
                // crimson and warped stems are a nether trip, gatherRequirement would say no anyway but why ask
                if (!wood.isNetherWood()) {
                    GATHER.put(wood.prefix + "_log", MiningRequirement.HAND);
                }
            }
        }
    }

    private static void tools(String toolMaterialName, String material, Item pickaxeItem, Item shovelItem, Item swordItem, Item axeItem, Item hoeItem) {
        String s = "stick";
        String o = null;
        //noinspection UnnecessaryLocalVariable
        String m = material;
        shapedRecipe3x3(toolMaterialName + "_pickaxe", pickaxeItem, 1, m, m, m, o, s, o, o, s, o);
        shapedRecipe3x3(toolMaterialName + "_shovel", shovelItem, 1, o, m, o, o, s, o, o, s, o);
        shapedRecipe3x3(toolMaterialName + "_sword", swordItem, 1, o, m, o, o, m, o, o, s, o);
        shapedRecipe3x3(toolMaterialName + "_axe", axeItem, 1, m, m, o, m, s, o, o, s, o);
        shapedRecipe3x3(toolMaterialName + "_hoe", hoeItem, 1, m, m, o, o, s, o, o, s, o);
    }

    private static void armor(String armorMaterialName, String material, Item helmetItem, Item chestplateItem, Item leggingsItem, Item bootsItem) {
        String o = null;
        //noinspection UnnecessaryLocalVariable
        String m = material;
        shapedRecipe3x3(armorMaterialName + "_helmet", helmetItem, 1, m, m, m, m, o, m, o, o, o);
        shapedRecipe3x3(armorMaterialName + "_chestplate", chestplateItem, 1, m, o, m, m, m, m, m, m, m);
        shapedRecipe3x3(armorMaterialName + "_leggings", leggingsItem, 1, m, m, m, m, o, m, m, o, m);
        shapedRecipe3x3(armorMaterialName + "_boots", bootsItem, 1, o, o, o, m, o, m, m, o, m);
    }

    private static void alias(String newName, String original) {
        if (!_nameToResourceTask.containsKey(original) || !_nameToItemMatches.containsKey(original)) {
            Debug.logWarning("Invalid resource: " + original + ". Will not create alias.");
        } else {
            _nameToResourceTask.put(newName, _nameToResourceTask.get(original));
            _nameToItemMatches.put(newName, _nameToItemMatches.get(original));
            _aliases.put(newName, original);
        }
    }

    private static ItemTarget t(String cataloguedName) {
        return new ItemTarget(cataloguedName);
    }

    private static class CataloguedResource {
        private final Item[] _targets;
        private final Function<Integer, ResourceTask> _getResource;

        private boolean _mineIfPresent;
        private boolean _forceDimension = false;
        private Dimension _targetDimension;

        public CataloguedResource(Item[] targets, Function<Integer, ResourceTask> getResource) {
            _targets = targets;
            _getResource = getResource;
        }

        public CataloguedResource mineIfPresent() {
            _mineIfPresent = true;
            return this;
        }

        public CataloguedResource dontMineIfPresent() {
            _mineIfPresent = false;
            return this;
        }

        public CataloguedResource forceDimension(Dimension dimension) {
            _forceDimension = true;
            _targetDimension = dimension;
            return this;
        }

        public CataloguedResource anyDimension() {
            _forceDimension = false;
            return this;
        }

        // the task goes to the nether before it does anything else (ResourceTask.isInWrongDimension)
        boolean forcesNether() {
            return _forceDimension && _targetDimension == Dimension.NETHER;
        }

        public ResourceTask getResource(int count) {
            ResourceTask result = _getResource.apply(count);
            if (_mineIfPresent) {
                result = result.mineIfPresent(ItemHelper.itemsToBlocks(_targets));
            }
            if (_forceDimension) {
                result = result.forceDimension(_targetDimension);
            }
            return result;
        }
    }
}
