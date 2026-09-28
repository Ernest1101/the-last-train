package com.lasttrain.registry;

import com.lasttrain.LastTrain;
import com.lasttrain.block.CabinetBlock;
import com.lasttrain.block.CabinetBlockEntity;
import com.lasttrain.block.ChairBlock;
import com.lasttrain.block.CreakyFloorBlock;
import com.lasttrain.block.FuseBoxBlock;
import com.lasttrain.block.LampBlock;
import com.lasttrain.block.WardrobeBlock;
import com.lasttrain.block.LockedDoorBlock;
import com.lasttrain.block.ShapedBlock;
import com.lasttrain.block.WeatheredStairsBlock;
import com.lasttrain.entity.BlindOneEntity;
import com.lasttrain.entity.DollEntity;
import com.lasttrain.entity.ThrownBottleEntity;
import com.lasttrain.entity.TrainEntity;
import com.lasttrain.entity.WatcherEntity;
import com.lasttrain.item.BatteryItem;
import com.lasttrain.item.CursedDiaryItem;
import com.lasttrain.item.FlashlightItem;
import com.lasttrain.item.NoteItem;
import com.lasttrain.item.ThrowableBottleItem;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.decoration.PaintingVariant;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockSetType;

import java.util.ArrayList;
import java.util.List;

public final class ModRegistry {
	private static final List<Item> TAB_ITEMS = new ArrayList<>();

	/** Where the player wakes up after the third night (data/lasttrain/dimension/nightmare.json). */
	public static final ResourceKey<Level> NIGHTMARE = ResourceKey.create(Registries.DIMENSION, LastTrain.id("nightmare"));

	public static final EntityType<BlindOneEntity> BLIND_ONE = Registry.register(BuiltInRegistries.ENTITY_TYPE,
			LastTrain.id("blind_one"),
			FabricEntityTypeBuilder.create(MobCategory.MONSTER, BlindOneEntity::new)
					// 0.6 wide like other mobs: an open door leaves a gap of 0.81, and at 0.7 it caught on the door leaf;
					// under 1.9 tall so that standing on a rug (1/16) it still fits under a two-block door frame
					.dimensions(EntityDimensions.fixed(0.6f, 1.9f))
					.trackRangeBlocks(128) // seen from the departing train in the escape cutscene
					.build());

	public static final EntityType<TrainEntity> TRAIN = Registry.register(BuiltInRegistries.ENTITY_TYPE,
			LastTrain.id("train"),
			FabricEntityTypeBuilder.<TrainEntity>create(MobCategory.MISC, TrainEntity::new)
					.dimensions(EntityDimensions.fixed(3.0f, 3.8f))
					.trackRangeBlocks(256)
					.trackedUpdateRate(1)
					.disableSaving()
					.fireImmune()
					.build());

	// ---- house
	public static final Block WEATHERED_PLANKS = block("weathered_planks", new Block(BlockBehaviour.Properties.copy(Blocks.DARK_OAK_PLANKS)));
	public static final Block WEATHERED_BEAM = block("weathered_beam", new Block(BlockBehaviour.Properties.copy(Blocks.DARK_OAK_PLANKS)));
	public static final Block ROTTEN_FLOORBOARDS = block("rotten_floorboards", new Block(BlockBehaviour.Properties.copy(Blocks.SPRUCE_PLANKS)));
	public static final Block OLD_WALLPAPER = block("old_wallpaper", new Block(BlockBehaviour.Properties.copy(Blocks.SPRUCE_PLANKS)));
	public static final Block BLOODY_WALLPAPER = block("bloody_wallpaper", new Block(BlockBehaviour.Properties.copy(Blocks.SPRUCE_PLANKS)));
	public static final Block BOARDED_WINDOW = block("boarded_window", new Block(BlockBehaviour.Properties.copy(Blocks.SPRUCE_PLANKS).noOcclusion()));
	public static final Block WEATHERED_STAIRS = block("weathered_stairs", new WeatheredStairsBlock(
			WEATHERED_PLANKS.defaultBlockState(), BlockBehaviour.Properties.copy(Blocks.DARK_OAK_PLANKS)));
	public static final CabinetBlock CABINET = block("cabinet", new CabinetBlock(
			BlockBehaviour.Properties.copy(Blocks.BARREL).noOcclusion()));
	public static final CabinetBlock DRESSER = block("dresser", new CabinetBlock(
			BlockBehaviour.Properties.copy(Blocks.BARREL).noOcclusion()));
	public static final CabinetBlock NIGHTSTAND = block("nightstand", new CabinetBlock(
			BlockBehaviour.Properties.copy(Blocks.BARREL).noOcclusion(), Block.box(2, 0, 2, 14, 12, 14)));
	public static final CabinetBlock CRATE = block("crate", new CabinetBlock(
			BlockBehaviour.Properties.copy(Blocks.BARREL).noOcclusion()));
	public static final CabinetBlock DISPLAY_CABINET = block("display_cabinet", new CabinetBlock(
			BlockBehaviour.Properties.copy(Blocks.BARREL).noOcclusion()));
	/** One block entity type for every lootable piece of furniture. */
	public static final BlockEntityType<CabinetBlockEntity> CABINET_ENTITY = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE, LastTrain.id("cabinet"),
			FabricBlockEntityTypeBuilder.create(CabinetBlockEntity::new, CABINET, DRESSER, NIGHTSTAND, CRATE, DISPLAY_CABINET).build());
	/** A tall clock with a swinging pendulum; its hands show the time of day. */
	public static final Block GRANDFATHER_CLOCK = block("grandfather_clock",
			new com.lasttrain.block.GrandfatherClockBlock(BlockBehaviour.Properties.copy(Blocks.DARK_OAK_PLANKS).noOcclusion()));
	/** The clock's invisible upper half. */
	public static final Block CLOCK_TOP = Registry.register(BuiltInRegistries.BLOCK, LastTrain.id("clock_top"),
			new com.lasttrain.block.ClockTopBlock(BlockBehaviour.Properties.copy(Blocks.DARK_OAK_PLANKS).noOcclusion().noLootTable()));
	public static final BlockEntityType<com.lasttrain.block.GrandfatherClockBlockEntity> CLOCK_ENTITY = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE, LastTrain.id("grandfather_clock"),
			FabricBlockEntityTypeBuilder.create(com.lasttrain.block.GrandfatherClockBlockEntity::new, GRANDFATHER_CLOCK).build());
	/** A brass chandelier; LIT ones still have burning candles. */
	public static final Block CHANDELIER = block("chandelier", new com.lasttrain.block.ChandelierBlock(
			BlockBehaviour.Properties.copy(Blocks.CHAIN).noCollission().noOcclusion()
					.lightLevel(state -> state.getValue(com.lasttrain.block.ChandelierBlock.LIT) ? 9 : 0)));
	public static final Block OLD_BOOKSHELF = block("old_bookshelf", new Block(BlockBehaviour.Properties.copy(Blocks.BOOKSHELF)));
	/** A room door nailed shut: that room is entered through its neighbour. */
	public static final Block BOARDED_DOOR = block("boarded_door",
			new com.lasttrain.block.BoardedDoorBlock(BlockBehaviour.Properties.copy(Blocks.DARK_OAK_PLANKS).strength(-1.0f, 3600000.0f).noOcclusion()));
	/** Looks like the others, but one red book sticks out: pull it and the shelf slides away. */
	public static final Block SECRET_BOOKSHELF = block("secret_bookshelf",
			new com.lasttrain.block.SecretBookshelfBlock(BlockBehaviour.Properties.copy(Blocks.BOOKSHELF).noOcclusion()));
	public static final Block RED_RUG = block("red_rug", new CarpetBlock(BlockBehaviour.Properties.copy(Blocks.RED_CARPET)));
	public static final Block DUSTY_RUG = block("dusty_rug", new CarpetBlock(BlockBehaviour.Properties.copy(Blocks.GRAY_CARPET)));
	public static final Block OLD_TABLE = block("old_table", new ShapedBlock(
			BlockBehaviour.Properties.copy(Blocks.DARK_OAK_PLANKS).noOcclusion(), Block.box(0, 0, 0, 16, 16, 16)));
	public static final Block OLD_CHAIR = block("old_chair", new ChairBlock(
			BlockBehaviour.Properties.copy(Blocks.DARK_OAK_PLANKS).noOcclusion()));
	public static final Block PLATE = block("plate", new ShapedBlock(
			BlockBehaviour.Properties.copy(Blocks.FLOWER_POT).noOcclusion(), Block.box(3, 0, 3, 13, 1, 13)));
	public static final Block CUP = block("cup", new ShapedBlock(
			BlockBehaviour.Properties.copy(Blocks.FLOWER_POT).noOcclusion(), Block.box(6, 0, 6, 11.5, 5, 10)));
	public static final Block BOOK_STACK = block("book_stack", new ShapedBlock(
			BlockBehaviour.Properties.copy(Blocks.FLOWER_POT).noOcclusion(), Block.box(3, 0, 3, 13, 6, 13)));

	public static final EntityType<ThrownBottleEntity> THROWN_BOTTLE = Registry.register(BuiltInRegistries.ENTITY_TYPE,
			LastTrain.id("thrown_bottle"),
			FabricEntityTypeBuilder.<ThrownBottleEntity>create(MobCategory.MISC, ThrownBottleEntity::new)
					.dimensions(EntityDimensions.fixed(0.25f, 0.25f))
					.trackRangeBlocks(64).trackedUpdateRate(10)
					.build());

	public static final EntityType<DollEntity> DOLL = Registry.register(BuiltInRegistries.ENTITY_TYPE,
			LastTrain.id("doll"),
			FabricEntityTypeBuilder.create(MobCategory.MISC, DollEntity::new)
					.dimensions(EntityDimensions.fixed(0.4f, 0.8f))
					.trackRangeBlocks(64)
					.disableSaving()
					.build());

	public static final EntityType<WatcherEntity> WATCHER = Registry.register(BuiltInRegistries.ENTITY_TYPE,
			LastTrain.id("watcher"),
			FabricEntityTypeBuilder.create(MobCategory.MISC, WatcherEntity::new)
					.dimensions(EntityDimensions.fixed(0.6f, 2.3f))
					.trackRangeBlocks(96)
					.disableSaving()
					.build());

	public static final EntityType<com.lasttrain.entity.DoppelgangerEntity> DOPPELGANGER = Registry.register(BuiltInRegistries.ENTITY_TYPE,
			LastTrain.id("doppelganger"),
			FabricEntityTypeBuilder.create(MobCategory.MISC, com.lasttrain.entity.DoppelgangerEntity::new)
					.dimensions(EntityDimensions.fixed(0.6f, 1.8f))
					.trackRangeBlocks(64)
					.disableSaving()
					.build());

	// ---- paintings (textures/painting/*.png)
	public static final PaintingVariant PORTRAIT_MAN = painting("portrait_man", 16, 16);
	public static final PaintingVariant PORTRAIT_WOMAN = painting("portrait_woman", 16, 16);
	public static final PaintingVariant EYES = painting("eyes", 16, 16);
	public static final PaintingVariant FAMILY = painting("family", 32, 32);
	public static final Block CREAKY_FLOORBOARDS = block("creaky_floorboards", new CreakyFloorBlock(BlockBehaviour.Properties.copy(Blocks.SPRUCE_PLANKS)));
	public static final Block DAMP_STONE = block("damp_stone", new Block(BlockBehaviour.Properties.copy(Blocks.STONE_BRICKS)));
	public static final WardrobeBlock WARDROBE = Registry.register(BuiltInRegistries.BLOCK, LastTrain.id("wardrobe"),
			new WardrobeBlock(BlockBehaviour.Properties.copy(Blocks.DARK_OAK_PLANKS).noOcclusion()
					.isSuffocating((state, level, pos) -> false).isViewBlocking((state, level, pos) -> false)));
	public static final FuseBoxBlock FUSE_BOX = block("fuse_box", new FuseBoxBlock(
			BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK).noOcclusion().strength(-1.0f, 3600000.0f)));
	public static final Block ROOF_SHINGLES = block("roof_shingles", new Block(BlockBehaviour.Properties.copy(Blocks.DEEPSLATE_TILES)));
	public static final DoorBlock OLD_DOOR = Registry.register(BuiltInRegistries.BLOCK, LastTrain.id("old_door"),
			new DoorBlock(BlockBehaviour.Properties.copy(Blocks.SPRUCE_DOOR), BlockSetType.SPRUCE));
	public static final LockedDoorBlock LOCKED_DOOR = Registry.register(BuiltInRegistries.BLOCK, LastTrain.id("locked_door"),
			new LockedDoorBlock(BlockBehaviour.Properties.copy(Blocks.IRON_DOOR).strength(-1.0f, 3600000.0f).noLootTable()));

	// ---- station
	public static final Block PLATFORM_TILES = block("platform_tiles", new Block(BlockBehaviour.Properties.copy(Blocks.STONE_BRICKS)));
	public static final Block PLATFORM_EDGE = block("platform_edge", new Block(BlockBehaviour.Properties.copy(Blocks.STONE_BRICKS)));
	public static final Block BALLAST = block("ballast", new Block(BlockBehaviour.Properties.copy(Blocks.GRAVEL)));
	public static final Block TRACK_SLEEPERS = block("track_sleepers", new ShapedBlock(
			BlockBehaviour.Properties.copy(Blocks.RAIL).noOcclusion(), Block.box(0, 0, 0, 16, 1, 16)));
	public static final Block TRACK_RAIL_NORTH = block("track_rail_north", new ShapedBlock(
			BlockBehaviour.Properties.copy(Blocks.RAIL).noOcclusion(), Block.box(0, 0, 0, 16, 3, 16)));
	public static final Block TRACK_RAIL_SOUTH = block("track_rail_south", new ShapedBlock(
			BlockBehaviour.Properties.copy(Blocks.RAIL).noOcclusion(), Block.box(0, 0, 0, 16, 3, 16)));
	public static final Block LAMP_POLE = block("lamp_pole", new ShapedBlock(
			BlockBehaviour.Properties.copy(Blocks.IRON_BARS).noOcclusion(), Block.box(6.5, 0, 6.5, 9.5, 16, 9.5)));
	public static final LampBlock STATION_LAMP = block("station_lamp", new LampBlock(
			BlockBehaviour.Properties.copy(Blocks.LANTERN).noOcclusion(), Block.box(3, 0, 3, 13, 13, 13)));
	public static final Block WOODEN_CROSS = block("wooden_cross", new ShapedBlock(
			BlockBehaviour.Properties.copy(Blocks.DARK_OAK_FENCE).noOcclusion(), Block.box(6, 0, 6, 10, 16, 10)));

	// ---- items
	public static final Item CURSED_DIARY = item("cursed_diary", new CursedDiaryItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));
	public static final Item THROWABLE_BOTTLE = item("throwable_bottle", new ThrowableBottleItem(new Item.Properties().stacksTo(8)));
	public static final Item FLASHLIGHT = item("flashlight", new FlashlightItem(new Item.Properties()));
	public static final Item BATTERY = item("battery", new BatteryItem(new Item.Properties().stacksTo(16)));
	public static final Item CROWBAR = item("crowbar", new Item(new Item.Properties().stacksTo(1)));
	public static final Item FUSE = item("fuse", new Item(new Item.Properties().stacksTo(1)));
	public static final Item NOTE = item("note", new NoteItem(new Item.Properties().stacksTo(1)));
	public static final Item FAMILY_PHOTO = item("family_photo", new Item(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));
	public static final Item WARDROBE_ITEM = item("wardrobe", new DoubleHighBlockItem(WARDROBE, new Item.Properties()));
	public static final Item HOUSE_KEY = item("house_key", new Item(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));
	public static final Item OLD_DOOR_ITEM = item("old_door", new DoubleHighBlockItem(OLD_DOOR, new Item.Properties()));
	public static final Item LOCKED_DOOR_ITEM = item("locked_door", new DoubleHighBlockItem(LOCKED_DOOR, new Item.Properties()));
	public static final Item BLIND_ONE_SPAWN_EGG = item("blind_one_spawn_egg",
			new SpawnEggItem(BLIND_ONE, 0xA8A696, 0x3A342C, new Item.Properties()));

	public static final CreativeModeTab TAB = Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
			LastTrain.id("main"),
			FabricItemGroup.builder()
					.title(Component.translatable("itemGroup.lasttrain"))
					.icon(() -> new ItemStack(HOUSE_KEY))
					.displayItems((params, output) -> TAB_ITEMS.forEach(output::accept))
					.build());

	private ModRegistry() {
	}

	private static <T extends Block> T block(String name, T block) {
		Registry.register(BuiltInRegistries.BLOCK, LastTrain.id(name), block);
		item(name, new BlockItem(block, new Item.Properties()));
		return block;
	}

	private static PaintingVariant painting(String name, int width, int height) {
		return Registry.register(BuiltInRegistries.PAINTING_VARIANT, LastTrain.id(name), new PaintingVariant(width, height));
	}

	private static <T extends Item> T item(String name, T item) {
		TAB_ITEMS.add(item);
		return Registry.register(BuiltInRegistries.ITEM, LastTrain.id(name), item);
	}

	public static void init() {
		FabricDefaultAttributeRegistry.register(BLIND_ONE, BlindOneEntity.createAttributes());
		FabricDefaultAttributeRegistry.register(WATCHER, WatcherEntity.createAttributes());
		FabricDefaultAttributeRegistry.register(DOLL, DollEntity.createAttributes());
		FabricDefaultAttributeRegistry.register(DOPPELGANGER, com.lasttrain.entity.DoppelgangerEntity.createAttributes());
	}
}
