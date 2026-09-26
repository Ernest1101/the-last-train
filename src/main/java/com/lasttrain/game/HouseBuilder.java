package com.lasttrain.game;

import com.lasttrain.block.CabinetBlock;
import com.lasttrain.block.ChairBlock;
import com.lasttrain.block.FuseBoxBlock;
import com.lasttrain.block.LampBlock;
import com.lasttrain.block.WardrobeBlock;
import com.lasttrain.item.NoteItem;
import com.lasttrain.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.decoration.PaintingVariant;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds the mansion, the path and the railway station around an origin.
 *
 * <pre>
 *  Each of the two floors (y=0 and y=6):
 *  z=25 +--------+--------+--------+-----+--------+--------+--------+
 *       |  room  |  room  |  room  |stair|  room  |  room  |  room  |   south rooms, doors at z=14
 *  z=14 +---D----+---D----+---D----+     +---D----+---D----+---D----+
 *       |              long gallery (z=11..13, x=1..59)             |
 *  z=10 +---D----+---D----+---D----+     +---D----+---D----+---D----+
 *       |  room  |  room  |  room  |foyer|  room  |  room  |  room  |   north rooms, doors at z=10
 *   z=0 +--------+--------+--------+EXIT-+--------+--------+--------+
 *       x=0      9        18      28  32     41       50       60
 *  z=-11..-14 platform (x=14..46)   z=-15..-17 track (x from -160 to 320)
 * </pre>
 * 24 rooms of 12 kinds, furnished at random. Every player gets their own bedroom upstairs,
 * which the Blind One can't enter. The key is hidden in one random container outside those bedrooms.
 * The map changes every game: which room is where, doors between neighbouring rooms, gallery doors
 * boarded up (that room is only reachable through its neighbour) and caved-in stretches of the gallery
 * (you go round through two rooms). Everything stays reachable.
 */
public final class HouseBuilder {
	public static final int WIDTH = 60;
	public static final int DEPTH = 25;
	public static final int STOREY = 6;
	public static final int DOOR_X = 30;
	/** Middle row of the 3-block wide track. */
	public static final int RAIL_Z = -16;
	public static final int PLATFORM_MIN_X = 14;
	public static final int PLATFORM_MAX_X = 46;
	/** The train model sits on top of the rails, which are 3px tall. */
	private static final double RAIL_HEIGHT = 3.0 / 16.0;
	private static final int[][] ROOM_X = {{1, 8}, {10, 17}, {19, 27}, {33, 40}, {42, 49}, {51, 59}};

	public enum Kind { PLAYER_BEDROOM, BEDROOM, LIBRARY, STUDY, DINING, KITCHEN, PARLOR, STORAGE, NURSERY, BATHROOM, CHAPEL, GALLERY }

	/** A player's own room: the monster may not enter {@code box}; it stops at {@code entrance}. */
	public record SafeRoom(AABB box, Vec3 entrance, BlockPos spawn) {
	}

	public record Layout(BlockPos origin, BlockPos houseMin, BlockPos houseMax, BlockPos exitDoor,
						 List<SafeRoom> safeRooms, BlockPos monsterSpawn, Vec3 trainStart, double trainStopX,
						 List<BlockPos> lamps, List<BlockPos> basementLamps, BlockPos dollSpawn, String keyRoom,
						 @org.jetbrains.annotations.Nullable BlockPos secretChest, List<BlockPos> patrolPoints, List<BlockPos> stairLandings) {
		public boolean isInsideHouse(Vec3 pos) {
			return pos.x >= houseMin.getX() && pos.x < houseMax.getX() + 1
					&& pos.z >= houseMin.getZ() && pos.z < houseMax.getZ() + 1
					&& pos.y >= houseMin.getY() - 8 && pos.y < houseMax.getY() + 6;
		}
	}

	/** Shape of the train model in blocks, relative to the entity position (the wagon's middle). */
	public static final class TrainGeometry {
		public static final double BACK = -7.4;
		public static final double FRONT = 18.9;
		public static final double CENTRE_OFFSET = (BACK + FRONT) / 2.0;

		private TrainGeometry() {
		}
	}

	/** Interior of one room in local coordinates. {@code north}: the room is north of the gallery (door on its south side). */
	private record Room(int x1, int x2, int z1, int z2, int y, boolean north) {
		int doorX() {
			return (x1 + x2) / 2;
		}

		int doorWallZ() {
			return north ? z2 + 1 : z1 - 1;
		}

		/** The row along the wall opposite the door. */
		int backZ() {
			return north ? z1 : z2;
		}

		/** One step from the back wall towards the door. */
		int towardsDoor() {
			return north ? 1 : -1;
		}

		/** What furniture on the back wall faces. */
		Direction faceDoor() {
			return north ? Direction.SOUTH : Direction.NORTH;
		}

		int midX() {
			return (x1 + x2) / 2;
		}

		int midZ() {
			return (z1 + z2) / 2;
		}
	}

	private final ServerLevel level;
	private final BlockPos origin;
	private final RandomSource random;
	/** Containers the key may end up in. */
	private final List<BlockPos> lootSpots = new ArrayList<>();
	private final List<SafeRoom> safeRooms = new ArrayList<>();
	private final List<BlockPos> basementSpots = new ArrayList<>();
	private final List<BlockPos> atticSpots = new ArrayList<>();
	/** Which room each loot spot is in (translation key), for the day-three hint. */
	private final java.util.Map<BlockPos, String> spotRoom = new java.util.HashMap<>();
	private final List<BlockPos> lamps = new ArrayList<>();
	private final List<BlockPos> basementLamps = new ArrayList<>();
	private String currentRoom = "room.lasttrain.gallery_corridor";
	private BlockPos monsterSpawn;
	private BlockPos dollSpawn;
	private String keyRoom = "room.lasttrain.gallery_corridor";
	private final java.util.Map<Room, Kind> kinds = new java.util.HashMap<>();
	/** Where the Blind One wanders when it hears nothing: into every room but the players' own, and down the galleries. */
	private final List<BlockPos> patrolPoints = new ArrayList<>();
	private final List<BlockPos> stairLandings = new ArrayList<>();
	/** Neighbouring rooms (left, right) joined by a door in the wall between them. */
	private final List<Room[]> sideDoors = new ArrayList<>();
	/** Rooms whose gallery door must stay open: another room or a detour depends on it. */
	private final java.util.Set<Room> keepOpen = new java.util.HashSet<>();
	/** Caved-in gallery stretches: {floor y, x of the wall between the two rooms of the detour}. */
	private final List<int[]> rubble = new ArrayList<>();
	private int chandeliers;
	/** The chest in the hidden room behind the secret bookshelf. */
	private BlockPos secretChest;

	private HouseBuilder(ServerLevel level, BlockPos origin) {
		this.level = level;
		this.origin = origin;
		this.random = level.getRandom();
	}

	/** {@code feet} is where the player stands; the house floor goes one block below it. */
	public static Layout build(ServerLevel level, BlockPos feet, int players) {
		HouseBuilder b = new HouseBuilder(level, feet.below());
		b.terrain();
		b.railway();
		b.shell();
		b.stairs();
		b.basement();
		b.attic();
		b.rooms(Math.max(1, players));
		b.secretRoom();
		b.sideDoors();
		b.planRubble();
		b.boardDoors();
		b.corridors();
		b.clocks();
		b.placeRubble();
		b.grounds();
		b.creakyFloors();
		b.distributeLoot();

		BlockPos o = b.origin;
		double platformCentre = o.getX() + (PLATFORM_MIN_X + PLATFORM_MAX_X + 1) / 2.0;
		double stopX = platformCentre - TrainGeometry.CENTRE_OFFSET;
		return new Layout(o,
				o, o.offset(WIDTH, 2 * STOREY, DEPTH),
				o.offset(DOOR_X, 1, 0),
				List.copyOf(b.safeRooms), b.monsterSpawn,
				new Vec3(stopX - 90.0, o.getY() + 1.0 + RAIL_HEIGHT, o.getZ() + RAIL_Z + 0.5), // inside the simulation distance, or it would never move
				stopX, List.copyOf(b.lamps), List.copyOf(b.basementLamps),
				b.dollSpawn != null ? b.dollSpawn : b.monsterSpawn, b.keyRoom, b.secretChest, List.copyOf(b.patrolPoints), List.copyOf(b.stairLandings));
	}

	// ------------------------------------------------------------------ helpers

	private BlockPos at(int x, int y, int z) {
		return this.origin.offset(x, y, z);
	}

	private void set(int x, int y, int z, BlockState state) {
		this.level.setBlock(at(x, y, z), state, Block.UPDATE_CLIENTS);
	}

	private void set(int x, int y, int z, Block block) {
		this.set(x, y, z, block.defaultBlockState());
	}

	private boolean isAir(int x, int y, int z) {
		return this.level.getBlockState(at(x, y, z)).isAir();
	}

	private void fill(int x1, int y1, int z1, int x2, int y2, int z2, Block block) {
		for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
			for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
				for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
					this.set(x, y, z, block);
				}
			}
		}
	}

	private Block wallpaper() {
		return this.random.nextInt(14) == 0 ? ModRegistry.BLOODY_WALLPAPER : ModRegistry.OLD_WALLPAPER;
	}

	private void door(Block block, int x, int y, int z, Direction facing) {
		BlockState door = block.defaultBlockState()
				.setValue(DoorBlock.FACING, facing)
				.setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
				.setValue(DoorBlock.OPEN, false);
		set(x, y, z, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
		set(x, y + 1, z, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
	}

	private static final Block[] CONTAINERS = {ModRegistry.CABINET, ModRegistry.DRESSER, ModRegistry.NIGHTSTAND,
			ModRegistry.CRATE, ModRegistry.DISPLAY_CABINET};

	/** A lootable container that may get the key. */
	private void loot(Block block, int x, int y, int z, Direction facing) {
		if (!isAir(x, y, z)) {
			return;
		}
		decor(block, x, y, z, facing);
		this.lootSpots.add(at(x, y, z));
		this.spotRoom.put(at(x, y, z), this.currentRoom);
	}

	/** A container with junk only (never the key). */
	private void decor(Block block, int x, int y, int z, Direction facing) {
		if (block == Blocks.CHEST) {
			set(x, y, z, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing));
		} else {
			set(x, y, z, block.defaultBlockState().setValue(CabinetBlock.FACING, facing));
		}
		fillJunk(x, y, z);
	}

	private void fillJunk(int x, int y, int z) {
		ItemStack[] junk = {new ItemStack(Items.BONE), new ItemStack(Items.PAPER), new ItemStack(Items.ROTTEN_FLESH),
				new ItemStack(Items.STRING, 3), new ItemStack(Items.GLASS_BOTTLE), new ItemStack(Items.BREAD),
				new ItemStack(Items.CANDLE), new ItemStack(Items.BOOK), new ItemStack(Items.SPIDER_EYE),
				new ItemStack(Items.BOWL), new ItemStack(Items.FEATHER), new ItemStack(Items.INK_SAC)};
		if (this.level.getBlockEntity(at(x, y, z)) instanceof Container container) {
			for (int i = 0; i < 1 + this.random.nextInt(3); i++) {
				container.setItem(this.random.nextInt(container.getContainerSize()), junk[this.random.nextInt(junk.length)].copy());
			}
		}
	}

	private Block anyContainer() {
		return CONTAINERS[this.random.nextInt(CONTAINERS.length)];
	}

	private void candle(int x, int y, int z) {
		if (isAir(x, y, z)) {
			set(x, y, z, Blocks.CANDLE.defaultBlockState()
					.setValue(CandleBlock.CANDLES, 1 + this.random.nextInt(3))
					.setValue(CandleBlock.LIT, this.random.nextInt(3) > 0));
		}
	}

	private void onTable(int x, int y, int z) {
		switch (this.random.nextInt(5)) {
			case 0, 1 -> set(x, y, z, ModRegistry.PLATE);
			case 2 -> set(x, y, z, ModRegistry.CUP);
			case 3 -> set(x, y, z, ModRegistry.BOOK_STACK);
			default -> candle(x, y, z);
		}
	}

	private void chair(int x, int y, int z, Direction facing) {
		if (isAir(x, y, z)) {
			set(x, y, z, ModRegistry.OLD_CHAIR.defaultBlockState().setValue(ChairBlock.FACING, facing));
		}
	}

	private void lamp(int x, int y, int z) {
		if (isAir(x, y, z) && isAir(x, y + 1, z)) {
			set(x, y, z, ModRegistry.WEATHERED_BEAM);
			set(x, y + 1, z, ModRegistry.STATION_LAMP);
			this.lamps.add(at(x, y + 1, z));
		}
	}

	/** A hiding wardrobe (two blocks tall), doors facing {@code facing}. */
	private void wardrobe(int x, int y, int z, Direction facing) {
		if (!isAir(x, y, z) || !isAir(x, y + 1, z)) {
			return;
		}
		BlockState state = ModRegistry.WARDROBE.defaultBlockState().setValue(WardrobeBlock.FACING, facing);
		set(x, y, z, state.setValue(WardrobeBlock.HALF, DoubleBlockHalf.LOWER));
		set(x, y + 1, z, state.setValue(WardrobeBlock.HALF, DoubleBlockHalf.UPPER));
	}

	private void rug(Block rug, int x1, int z1, int x2, int z2, int y) {
		for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
			for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
				if (isAir(x, y, z)) {
					set(x, y, z, rug);
				}
			}
		}
	}

	private void bed(Block bed, int x, int y, int headZ, boolean headNorth) {
		Direction facing = headNorth ? Direction.NORTH : Direction.SOUTH;
		BlockState state = bed.defaultBlockState().setValue(BedBlock.FACING, facing);
		set(x, y, headZ, state.setValue(BedBlock.PART, BedPart.HEAD));
		set(x, y, headZ + (headNorth ? 1 : -1), state.setValue(BedBlock.PART, BedPart.FOOT));
	}

	/** Hangs a painting on the wall behind the air block (x, y, z); {@code facing} points away from the wall. */
	private void painting(int x, int y, int z, Direction facing) {
		PaintingVariant[] variants = {ModRegistry.PORTRAIT_MAN, ModRegistry.PORTRAIT_WOMAN, ModRegistry.EYES, ModRegistry.FAMILY};
		PaintingVariant variant = variants[this.random.nextInt(variants.length)];
		if (!placePainting(x, y, z, facing, variant) && variant == ModRegistry.FAMILY) {
			placePainting(x, y, z, facing, ModRegistry.EYES);
		}
	}

	private boolean placePainting(int x, int y, int z, Direction facing, PaintingVariant variant) {
		Holder<PaintingVariant> holder = BuiltInRegistries.PAINTING_VARIANT.wrapAsHolder(variant);
		Painting painting = new Painting(this.level, at(x, y, z), facing, holder);
		if (painting.survives()) {
			this.level.addFreshEntity(painting);
			return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ outside

	private void terrain() {
		fill(YARD_MIN_X - 2, 1, -12, YARD_MAX_X + 2, 2 * STOREY + 7, YARD_BACK_Z + 2, Blocks.AIR);
		fill(YARD_MIN_X - 2, -3, -12, YARD_MAX_X + 2, -1, YARD_BACK_Z + 2, Blocks.DIRT);
		fill(YARD_MIN_X - 2, 0, -12, YARD_MAX_X + 2, 0, YARD_BACK_Z + 2, Blocks.GRASS_BLOCK);
		fill(DOOR_X, 0, -10, DOOR_X, 0, -1, Blocks.DIRT_PATH);
		for (int i = 0; i < 60; i++) {
			int x = this.random.nextInt(WIDTH + 12) - 6;
			int z = -this.random.nextInt(9) - 1;
			if (x != DOOR_X) {
				set(x, 1, z, this.random.nextBoolean() ? Blocks.DEAD_BUSH : Blocks.GRASS);
			}
		}
	}

	private void railway() {
		for (int x = -160; x <= 320; x++) {
			fill(x, 1, RAIL_Z - 3, x, 6, RAIL_Z + 3, Blocks.AIR);
			fill(x, -2, RAIL_Z - 3, x, -1, RAIL_Z + 3, Blocks.STONE);
			fill(x, 0, RAIL_Z - 3, x, 0, RAIL_Z + 3, ModRegistry.BALLAST);
			set(x, 1, RAIL_Z - 1, ModRegistry.TRACK_RAIL_NORTH);
			set(x, 1, RAIL_Z, ModRegistry.TRACK_SLEEPERS);
			set(x, 1, RAIL_Z + 1, ModRegistry.TRACK_RAIL_SOUTH);
		}
		fill(PLATFORM_MIN_X, -1, RAIL_Z + 2, PLATFORM_MAX_X, 1, RAIL_Z + 5, ModRegistry.PLATFORM_TILES);
		fill(PLATFORM_MIN_X, 1, RAIL_Z + 2, PLATFORM_MAX_X, 1, RAIL_Z + 2, ModRegistry.PLATFORM_EDGE);
		for (int x = PLATFORM_MIN_X + 3; x <= PLATFORM_MAX_X; x += 8) {
			set(x, 2, RAIL_Z + 5, ModRegistry.LAMP_POLE);
			set(x, 3, RAIL_Z + 5, ModRegistry.LAMP_POLE);
			set(x, 4, RAIL_Z + 5, ModRegistry.STATION_LAMP);
		}
	}

	// ------------------------------------------------------------------ structure

	private void shell() {
		for (int storey = 0; storey < 2; storey++) {
			int floor = storey * STOREY;
			fill(0, floor, 0, WIDTH, floor, DEPTH, storey == 0 ? ModRegistry.ROTTEN_FLOORBOARDS : ModRegistry.WEATHERED_PLANKS);
			for (int y = floor + 1; y < floor + STOREY; y++) {
				for (int x = 0; x <= WIDTH; x++) {
					set(x, y, 0, ModRegistry.WEATHERED_PLANKS);
					set(x, y, DEPTH, ModRegistry.WEATHERED_PLANKS);
					if (x < 29 || x > 31) {
						set(x, y, 10, wallpaper()); // gallery walls, open where the cross corridor passes
						set(x, y, 14, wallpaper());
					}
				}
				for (int z = 0; z <= DEPTH; z++) {
					set(0, y, z, ModRegistry.WEATHERED_PLANKS);
					set(WIDTH, y, z, ModRegistry.WEATHERED_PLANKS);
					// the partitions stop at the outer walls: their wallpaper must not show outside
					if ((z < 10 || z > 14) && z > 0 && z < DEPTH) {
						for (int wx : new int[]{9, 18, 28, 32, 41, 50}) {
							set(wx, y, z, wallpaper());
						}
					}
				}
				for (int x = 0; x <= WIDTH; x += 10) {
					set(x, y, 0, ModRegistry.WEATHERED_BEAM);
					set(x, y, DEPTH, ModRegistry.WEATHERED_BEAM);
				}
			}
			// boarded windows, two per room on the outer walls
			int wy = floor + 2;
			for (int[] rx : ROOM_X) {
				for (int x : new int[]{rx[0] + 2, rx[1] - 2}) {
					for (int z : new int[]{0, DEPTH}) {
						set(x, wy, z, ModRegistry.BOARDED_WINDOW);
						set(x, wy + 1, z, ModRegistry.BOARDED_WINDOW);
					}
				}
			}
			for (int z : new int[]{4, 20}) {
				for (int x : new int[]{0, WIDTH}) {
					set(x, wy, z, ModRegistry.BOARDED_WINDOW);
					set(x, wy + 1, z, ModRegistry.BOARDED_WINDOW);
				}
			}
		}
		fill(0, 2 * STOREY, 0, WIDTH, 2 * STOREY, DEPTH, ModRegistry.WEATHERED_PLANKS);
		door(ModRegistry.LOCKED_DOOR, DOOR_X, 1, 0, Direction.NORTH);
	}

	/**
	 * Grand staircase in the south part of the cross corridor: two blocks wide (x=29..30), climbing north
	 * from z=22 to z=17. Downstairs you walk round it along x=31; upstairs it comes out next to the gallery.
	 */
	private void stairs() {
		BlockState step = ModRegistry.WEATHERED_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
		for (int k = 0; k <= 5; k++) {
			int z = 22 - k;
			for (int x = 29; x <= 30; x++) {
				set(x, 1 + k, z, step);
				if (k > 0) {
					fill(x, 1, z, x, k, z, ModRegistry.WEATHERED_PLANKS);
				}
			}
		}
		fill(29, STOREY, 18, 30, STOREY, 22, Blocks.AIR); // stairwell
		// upstairs, close off what's left beside and behind the stairwell
		fill(31, STOREY + 1, 18, 31, 2 * STOREY - 1, 24, ModRegistry.OLD_WALLPAPER);
		fill(29, STOREY + 1, 23, 30, 2 * STOREY - 1, 24, ModRegistry.OLD_WALLPAPER);
		// downstairs: the landing in front of the first step
		rug(ModRegistry.RED_RUG, 29, 23, 31, 24, 1);
		lamp(31, 1, 24);
		painting(30, 3, 24, Direction.NORTH);
	}

	// ------------------------------------------------------------------ rooms

	private void rooms(int players) {
		List<Room> ground = new ArrayList<>();
		List<Room> upper = new ArrayList<>();
		for (int storey = 0; storey < 2; storey++) {
			int y = storey * STOREY + 1;
			for (int[] rx : ROOM_X) {
				(storey == 0 ? ground : upper).add(new Room(rx[0], rx[1], 1, 9, y, true));
				(storey == 0 ? ground : upper).add(new Room(rx[0], rx[1], 15, 24, y, false));
			}
		}
		for (Room room : ground) {
			roomDoor(room);
		}
		for (Room room : upper) {
			roomDoor(room);
		}

		// upstairs: one bedroom per player, the rest random
		Collections.shuffle(upper, new java.util.Random(this.random.nextLong()));
		int bedrooms = Math.min(players, upper.size());
		Kind[] upperKinds = {Kind.BEDROOM, Kind.LIBRARY, Kind.STUDY, Kind.NURSERY, Kind.BATHROOM, Kind.STORAGE, Kind.CHAPEL, Kind.GALLERY};
		for (int i = 0; i < upper.size(); i++) {
			furnish(upper.get(i), i < bedrooms ? Kind.PLAYER_BEDROOM : upperKinds[(i - bedrooms) % upperKinds.length]);
		}

		// downstairs: the essentials first, then random; the monster lives in the first (storage) room
		Collections.shuffle(ground, new java.util.Random(this.random.nextLong()));
		Kind[] first = {Kind.STORAGE, Kind.KITCHEN, Kind.DINING, Kind.PARLOR, Kind.LIBRARY, Kind.STUDY};
		Kind[] more = {Kind.DINING, Kind.BATHROOM, Kind.GALLERY, Kind.STORAGE, Kind.CHAPEL, Kind.BEDROOM};
		for (int i = 0; i < ground.size(); i++) {
			Room room = ground.get(i);
			furnish(room, i < first.length ? first[i] : more[(i - first.length) % more.length]);
			if (i == 0) {
				this.monsterSpawn = at(room.doorX(), room.y(), room.midZ());
			}
		}
	}

	private void roomDoor(Room room) {
		Direction facing = room.north() ? Direction.SOUTH : Direction.NORTH;
		door(ModRegistry.OLD_DOOR, room.doorX(), room.y(), room.doorWallZ(), facing);
	}

	private void furnish(Room r, Kind kind) {
		this.kinds.put(r, kind);
		if (kind != Kind.PLAYER_BEDROOM) {
			// two steps in from the door: somewhere it can always stand
			this.patrolPoints.add(at(r.doorX(), r.y(), r.north() ? r.z2() - 2 : r.z1() + 2));
		}
		if (kind != Kind.STORAGE && kind != Kind.BATHROOM && kind != Kind.KITCHEN && kind != Kind.PLAYER_BEDROOM) {
			chandelier(r.midX(), r.y() + 4, r.midZ());
		}
		int y = r.y();
		int back = r.backZ();
		int step = r.towardsDoor();
		Direction face = r.faceDoor();
		Direction faceBack = face.getOpposite();
		this.currentRoom = "room.lasttrain." + kind.name().toLowerCase(java.util.Locale.ROOT).replace("player_", "");
		// common touches: a cobweb in a corner, a painting on a side wall
		set(this.random.nextBoolean() ? r.x1() : r.x2(), y + 3, back, Blocks.COBWEB);
		painting(r.x1(), y + 2, r.midZ() + (this.random.nextBoolean() ? 1 : -1), Direction.EAST);

		switch (kind) {
			case PLAYER_BEDROOM, BEDROOM -> {
				boolean own = kind == Kind.PLAYER_BEDROOM;
				bed(own ? Blocks.RED_BED : Blocks.GRAY_BED, r.x1() + 1, y, back, r.north());
				if (own) {
					decor(ModRegistry.NIGHTSTAND, r.x1(), y, back, face);
					decor(ModRegistry.NIGHTSTAND, r.x1() + 2, y, back, face);
					decor(ModRegistry.CABINET, r.x2(), y, back, face);
					decor(ModRegistry.DRESSER, r.x2(), y, r.midZ(), Direction.WEST);
				} else {
					loot(ModRegistry.NIGHTSTAND, r.x1(), y, back, face);
					loot(ModRegistry.NIGHTSTAND, r.x1() + 2, y, back, face);
					loot(ModRegistry.CABINET, r.x2(), y, back, face);
					loot(ModRegistry.DRESSER, r.x2(), y, r.midZ(), Direction.WEST);
				}
				candle(r.x1(), y + 1, back);
				wardrobe(r.x2(), y, back + step * 4, Direction.WEST);
				fill(r.x1() + 4, y, back, r.x1() + 5, y, back, ModRegistry.OLD_BOOKSHELF);
				set(r.x1() + 4, y + 1, back, ModRegistry.BOOK_STACK);
				lamp(r.x2(), y, back + step * 2);
				rug(ModRegistry.RED_RUG, r.x1() + 2, r.midZ() - 1, r.x2() - 2, r.midZ() + 1, y);
				if (own) {
					BlockPos spawn = at(r.midX(), y, r.midZ());
					AABB box = new AABB(at(r.x1(), y, r.z1()), at(r.x2() + 1, y + 4, r.z2() + 1));
					Vec3 entrance = Vec3.atBottomCenterOf(at(r.doorX(), y, r.doorWallZ() + (r.north() ? 1 : -1)));
					this.safeRooms.add(new SafeRoom(box, entrance, spawn));
				}
			}
			case LIBRARY -> {
				fill(r.x1(), y, back, r.x2(), y + 2, back, ModRegistry.OLD_BOOKSHELF);
				fill(r.x1(), y, back, r.x1(), y + 1, back + step * 5, ModRegistry.OLD_BOOKSHELF);
				fill(r.x2(), y, back, r.x2(), y, back + step * 5, ModRegistry.OLD_BOOKSHELF);
				int tz = back + step * 3;
				for (int x = r.midX() - 1; x <= r.midX() + 1; x++) {
					set(x, y, tz, ModRegistry.OLD_TABLE);
					onTable(x, y + 1, tz);
				}
				chair(r.midX() - 1, y, tz + step, faceBack);
				chair(r.midX() + 1, y, tz + step, faceBack);
				loot(ModRegistry.DISPLAY_CABINET, r.x2(), y, back + step * 6, Direction.WEST);
				loot(ModRegistry.DRESSER, r.x1(), y, back + step * 6, Direction.EAST);
				lamp(r.x2(), y, back + step * 7);
				rug(ModRegistry.DUSTY_RUG, r.x1() + 2, tz - 1, r.x2() - 2, tz + 1, y);
			}
			case STUDY -> {
				set(r.midX(), y, back + step, ModRegistry.OLD_TABLE);
				set(r.midX() + 1, y, back + step, ModRegistry.OLD_TABLE);
				set(r.midX(), y + 1, back + step, ModRegistry.BOOK_STACK);
				candle(r.midX() + 1, y + 1, back + step);
				chair(r.midX(), y, back + step * 2, faceBack);
				fill(r.x1(), y, back, r.x1(), y + 2, back + step * 4, ModRegistry.OLD_BOOKSHELF);
				loot(ModRegistry.DRESSER, r.x2(), y, back, face);
				loot(ModRegistry.CABINET, r.x2(), y, back + step * 3, Direction.WEST);
				loot(ModRegistry.DISPLAY_CABINET, r.x2(), y, back + step * 4, Direction.WEST);
				lamp(r.x1() + 1, y, back + step * 6);
				rug(ModRegistry.DUSTY_RUG, r.midX() - 2, back + step, r.midX() + 2, back + step * 4, y);
			}
			case DINING -> {
				int tz = r.midZ();
				for (int x = r.x1() + 2; x <= r.x2() - 2; x++) {
					set(x, y, tz, ModRegistry.OLD_TABLE);
					onTable(x, y + 1, tz);
					if ((x - r.x1()) % 2 == 0) {
						chair(x, y, tz - 1, Direction.SOUTH);
						chair(x, y, tz + 1, Direction.NORTH);
					}
				}
				chair(r.x1() + 1, y, tz, Direction.EAST);
				loot(ModRegistry.DISPLAY_CABINET, r.x1() + 1, y, back, face);
				loot(ModRegistry.DISPLAY_CABINET, r.x1() + 2, y, back, face);
				loot(ModRegistry.CABINET, r.x2() - 1, y, back, face);
				lamp(r.x2(), y, back);
				rug(ModRegistry.RED_RUG, r.x1() + 1, tz - 2, r.x2() - 1, tz + 2, y);
			}
			case KITCHEN -> {
				for (int x = r.x1(); x <= r.x2(); x++) {
					if (x == r.midX()) {
						set(x, y, back, Blocks.SMOKER.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, face));
					} else {
						loot(x % 3 == 0 ? ModRegistry.DISPLAY_CABINET : ModRegistry.CABINET, x, y, back, face);
					}
				}
				set(r.x1(), y, back + step * 3, Blocks.CAULDRON);
				set(r.x1(), y, back + step * 4, Blocks.BARREL);
				loot(ModRegistry.CRATE, r.x2(), y, back + step * 4, Direction.WEST);
				set(r.x2(), y + 1, back + step * 4, ModRegistry.CRATE.defaultBlockState());
				int tz = back + step * 3;
				set(r.midX(), y, tz, ModRegistry.OLD_TABLE);
				set(r.midX() + 1, y, tz, ModRegistry.OLD_TABLE);
				onTable(r.midX(), y + 1, tz);
				onTable(r.midX() + 1, y + 1, tz);
				chair(r.midX(), y, tz + step, faceBack);
				lamp(r.x2(), y, back + step * 6);
			}
			case PARLOR -> {
				// fireplace in the middle of the back wall
				int fx = r.midX();
				for (int dy = 0; dy < 3; dy++) {
					set(fx - 1, y + dy, back, Blocks.BRICKS);
					set(fx + 1, y + dy, back, Blocks.BRICKS);
				}
				set(fx, y + 2, back, Blocks.BRICKS);
				set(fx, y + 1, back, Blocks.AIR);
				set(fx, y, back, Blocks.SOUL_CAMPFIRE);
				chair(fx - 1, y, back + step * 3, faceBack);
				chair(fx + 1, y, back + step * 3, faceBack);
				set(fx, y, back + step * 3, ModRegistry.OLD_TABLE);
				candle(fx, y + 1, back + step * 3);
				fill(r.x1(), y, back, r.x1(), y + 1, back + step * 3, ModRegistry.OLD_BOOKSHELF);
				loot(ModRegistry.DISPLAY_CABINET, r.x2(), y, back, face);
				loot(ModRegistry.DRESSER, r.x2(), y, back + step * 3, Direction.WEST);
				loot(ModRegistry.NIGHTSTAND, r.x2(), y, back + step * 5, Direction.WEST);
				rug(ModRegistry.RED_RUG, r.x1() + 1, back + step, r.x2() - 1, back + step * 5, y);
			}
			case STORAGE -> {
				for (int i = 0; i < 12; i++) {
					int x = r.x1() + this.random.nextInt(r.x2() - r.x1() + 1);
					int z = back + step * this.random.nextInt(4);
					// keep the door and every third column free: an aisle next to every crate, or a crate
					// walled in by other crates could hold the key and never be opened
					if (Math.abs(x - r.doorX()) <= 1 || (x - r.x1()) % 3 == 1) {
						continue;
					}
					if (this.random.nextInt(3) == 0) {
						if (isAir(x, y, z)) {
							set(x, y, z, Blocks.BARREL);
						}
					} else {
						loot(ModRegistry.CRATE, x, y, z, face);
						if (this.random.nextBoolean() && isAir(x, y + 1, z)) {
							set(x, y + 1, z, ModRegistry.CRATE.defaultBlockState());
						}
					}
				}
				if (isAir(r.x2(), y, back + step * 5)) {
					decor(Blocks.CHEST, r.x2(), y, back + step * 5, Direction.WEST);
					this.lootSpots.add(at(r.x2(), y, back + step * 5));
				}
				set(r.x1(), y + 3, back + step * 4, Blocks.COBWEB);
				set(r.x2(), y + 3, back + step * 6, Blocks.COBWEB);
			}
			case NURSERY -> {
				bed(Blocks.WHITE_BED, r.x1() + 1, y, back, r.north());
				bed(Blocks.WHITE_BED, r.x1() + 3, y, back, r.north());
				loot(ModRegistry.NIGHTSTAND, r.x1() + 2, y, back, face);
				loot(ModRegistry.CRATE, r.x2(), y, back, face);
				set(r.x2(), y + 1, back, Blocks.SKELETON_SKULL);
				loot(ModRegistry.CABINET, r.x2(), y, back + step * 3, Direction.WEST);
				candle(r.x1() + 2, y + 1, back);
				rug(ModRegistry.DUSTY_RUG, r.x1() + 1, back + step * 3, r.x2() - 1, back + step * 5, y);
				lamp(r.x1(), y, back + step * 6);
				wardrobe(r.x2(), y, back + step * 6, Direction.WEST);
				if (this.dollSpawn == null) {
					this.dollSpawn = at(r.midX(), y, back + step * 2);
				}
			}
			case BATHROOM -> {
				set(r.midX(), y, back, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
				set(r.midX() + 1, y, back, Blocks.CAULDRON);
				loot(ModRegistry.CABINET, r.x1(), y, back, face);
				loot(ModRegistry.DRESSER, r.x2(), y, back, face);
				set(r.x1(), y, back + step * 3, Blocks.BARREL);
				wardrobe(r.x2(), y, back + step * 4, Direction.WEST);
				rug(ModRegistry.DUSTY_RUG, r.midX() - 1, back + step, r.midX() + 2, back + step * 3, y);
				lamp(r.x2(), y, back + step * 4);
			}
			case CHAPEL -> {
				fill(r.x1(), y, back, r.x2(), y + 3, back, ModRegistry.BLOODY_WALLPAPER);
				set(r.midX(), y, back + step, ModRegistry.OLD_TABLE);
				set(r.midX(), y + 1, back + step, Blocks.SKELETON_SKULL);
				for (int x = r.x1() + 1; x <= r.x2() - 1; x += 2) {
					candle(x, y, back + step);
				}
				for (int row = 3; row <= 6; row += 2) {
					for (int x = r.x1() + 1; x <= r.x2() - 1; x++) {
						if (x != r.midX()) {
							chair(x, y, back + step * row, faceBack);
						}
					}
				}
				rug(ModRegistry.RED_RUG, r.midX(), back + step * 2, r.midX(), back + step * 7, y);
				loot(ModRegistry.NIGHTSTAND, r.x1(), y, back + step, Direction.EAST);
				loot(ModRegistry.NIGHTSTAND, r.x2(), y, back + step, Direction.WEST);
			}
			case GALLERY -> {
				for (int k = 0; k <= 7; k += 2) {
					painting(r.x1(), y + 2, back + step * k, Direction.EAST);
					painting(r.x2(), y + 2, back + step * k, Direction.WEST);
				}
				loot(ModRegistry.DISPLAY_CABINET, r.midX() - 1, y, back, face);
				loot(ModRegistry.DISPLAY_CABINET, r.midX() + 1, y, back, face);
				set(r.midX(), y, back, ModRegistry.OLD_TABLE);
				set(r.midX(), y + 1, back, Blocks.SKELETON_SKULL);
				rug(ModRegistry.RED_RUG, r.midX() - 1, back + step, r.midX() + 1, back + step * 7, y);
				lamp(r.x1() + 1, y, back);
				lamp(r.x2() - 1, y, back);
			}
		}
	}

	// ------------------------------------------------------------------ the grounds outside

	/** The yard: fenced in from the station side (the path, z=-10) to well behind the house. */
	public static final int YARD_MIN_X = -24;
	public static final int YARD_MAX_X = WIDTH + 24;
	public static final int YARD_FRONT_Z = -10;
	public static final int YARD_BACK_Z = DEPTH + 22;
	/** Fences, walls and bars whose connections are worked out once everything stands. */
	private final List<BlockPos> connectables = new ArrayList<>();
	/** Where something big already stands (trees keep their distance). */
	private final List<int[]> occupied = new ArrayList<>();

	private void grounds() {
		this.occupied.add(new int[]{-3, -3, WIDTH + 3, DEPTH + 3});      // the house
		this.occupied.add(new int[]{DOOR_X - 3, YARD_FRONT_Z, DOOR_X + 3, -1}); // the path
		this.occupied.add(new int[]{-21, 4, -5, 23});                     // the graveyard
		this.occupied.add(new int[]{WIDTH + 8, 8, WIDTH + 16, 16});        // the well
		this.occupied.add(new int[]{WIDTH + 6, DEPTH + 6, WIDTH + 10, DEPTH + 10}); // the scarecrow
		this.yardFence();
		this.graveyard(-20, 5);
		this.well(WIDTH + 12, 12);
		this.scarecrow(WIDTH + 8, DEPTH + 8);
		this.pathLamps();
		int trees = 0;
		for (int attempt = 0; attempt < 300 && trees < 14; attempt++) {
			int x = YARD_MIN_X + 3 + this.random.nextInt(YARD_MAX_X - YARD_MIN_X - 6);
			int z = -6 + this.random.nextInt(YARD_BACK_Z + 3);
			if (this.free(x, z, 5)) {
				this.deadTree(x, z);
				this.occupied.add(new int[]{x - 4, z - 4, x + 4, z + 4});
				trees++;
			}
		}
		this.overgrowth();
		for (BlockPos pos : this.connectables) {
			BlockState state = this.level.getBlockState(pos);
			this.level.setBlock(pos, Block.updateFromNeighbourShapes(state, this.level, pos), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
		}
	}

	/** Strictly inside the yard fence (nothing of the yard reaches over it, least of all towards the railway). */
	private static boolean insideFence(int x, int z) {
		return x > YARD_MIN_X && x < YARD_MAX_X && z > YARD_FRONT_Z && z < YARD_BACK_Z;
	}

	private boolean free(int x, int z, int margin) {
		for (int[] box : this.occupied) {
			if (x >= box[0] - margin && x <= box[2] + margin && z >= box[1] - margin && z <= box[3] + margin) {
				return false;
			}
		}
		// trees lean and put out roots: keep them well back from the fence on the railway side
		return z > YARD_FRONT_Z + 4 && z < YARD_BACK_Z - 1 && x > YARD_MIN_X + 1 && x < YARD_MAX_X - 1;
	}

	private void connectable(int x, int y, int z, BlockState state) {
		set(x, y, z, state);
		this.connectables.add(at(x, y, z));
	}

	/** Dark wooden fence between mossy stone pillars; some sections have fallen, some pillars carry a skull. */
	private void yardFence() {
		BlockState fence = Blocks.DARK_OAK_FENCE.defaultBlockState();
		java.util.function.BiConsumer<Integer, Integer> post = (x, z) -> {
			if (Math.abs(x - DOOR_X) <= 1 && z == YARD_FRONT_Z) {
				return; // the gate
			}
			int along = z == YARD_FRONT_Z || z == YARD_BACK_Z ? x - YARD_MIN_X : z - YARD_FRONT_Z;
			if (along % 8 == 0) {
				set(x, 1, z, Blocks.MOSSY_STONE_BRICKS);
				set(x, 2, z, Blocks.MOSSY_STONE_BRICKS);
				if (this.random.nextInt(4) == 0) {
					set(x, 3, z, Blocks.SKELETON_SKULL.defaultBlockState().setValue(net.minecraft.world.level.block.SkullBlock.ROTATION, this.random.nextInt(16)));
				}
			} else if (this.random.nextInt(9) != 0) { // a broken section now and then
				connectable(x, 1, z, fence);
				if (this.random.nextInt(14) == 0) {
					set(x, 2, z, Blocks.COBWEB);
				}
			}
		};
		for (int x = YARD_MIN_X; x <= YARD_MAX_X; x++) {
			post.accept(x, YARD_FRONT_Z);
			post.accept(x, YARD_BACK_Z);
		}
		for (int z = YARD_FRONT_Z + 1; z < YARD_BACK_Z; z++) {
			post.accept(YARD_MIN_X, z);
			post.accept(YARD_MAX_X, z);
		}
		// the gate stands open towards the station
		for (int x = DOOR_X - 1; x <= DOOR_X + 1; x += 2) {
			set(x, 1, YARD_FRONT_Z, Blocks.DARK_OAK_FENCE_GATE.defaultBlockState()
					.setValue(net.minecraft.world.level.block.FenceGateBlock.FACING, Direction.NORTH)
					.setValue(net.minecraft.world.level.block.FenceGateBlock.OPEN, true));
		}
		set(DOOR_X, 0, YARD_FRONT_Z, Blocks.DIRT_PATH);
	}

	/**
	 * A crooked, leafless tree: a tall trunk that may lean, short thick limbs that split into thin twigs
	 * reaching out and up, a bare spire at the top and roots breaking the ground.
	 */
	private void deadTree(int x, int z) {
		BlockState log = Blocks.DARK_OAK_LOG.defaultBlockState();
		BlockState twig = Blocks.DARK_OAK_FENCE.defaultBlockState();
		net.minecraft.world.level.block.state.properties.EnumProperty<Direction.Axis> axis =
				net.minecraft.world.level.block.RotatedPillarBlock.AXIS;
		int height = 8 + this.random.nextInt(5);
		int tx = x, tz = z;
		Direction lean = Direction.Plane.HORIZONTAL.getRandomDirection(this.random);
		int leanAt = this.random.nextInt(3) == 0 ? height / 2 : -1;
		for (int y = 1; y <= height; y++) {
			if (y == leanAt) {
				set(tx, y, tz, log.setValue(axis, lean.getAxis()));
				tx += lean.getStepX();
				tz += lean.getStepZ();
			}
			set(tx, y, tz, log.setValue(axis, Direction.Axis.Y));
		}
		for (int y = height + 1; y <= height + 2 + this.random.nextInt(2); y++) {
			connectable(tx, y, tz, twig); // the bare spire
		}
		// roots and bare earth around the foot
		for (Direction d : Direction.Plane.HORIZONTAL) {
			if (this.random.nextInt(3) > 0) {
				set(x + d.getStepX(), 1, z + d.getStepZ(), log.setValue(axis, d.getAxis()));
			}
			set(x + d.getStepX() * 2, 0, z + d.getStepZ() * 2, this.random.nextBoolean() ? Blocks.COARSE_DIRT : Blocks.PODZOL);
		}
		set(x, 0, z, Blocks.COARSE_DIRT);
		// limbs: one or two thick blocks, then thin twigs going on out and up
		int limbs = 4 + this.random.nextInt(3);
		for (int b = 0; b < limbs; b++) {
			Direction d = Direction.Plane.HORIZONTAL.getRandomDirection(this.random);
			int bx = tx, bz = tz;
			int by = (int) (height * 0.4) + this.random.nextInt(height - (int) (height * 0.4));
			int thick = 1 + this.random.nextInt(2);
			boolean inside = true;
			for (int s = 0; s < thick && inside; s++) {
				bx += d.getStepX();
				bz += d.getStepZ();
				inside = insideFence(bx, bz);
				if (inside) {
					set(bx, by, bz, log.setValue(axis, d.getAxis()));
				}
			}
			if (!inside) {
				continue; // this limb would reach over the fence
			}
			if (b % 2 == 0 && this.random.nextInt(3) == 0) { // something hangs from the stronger limbs
				int chain = 1 + this.random.nextInt(3);
				for (int c = 1; c <= chain && isAir(bx, by - c, bz); c++) {
					set(bx, by - c, bz, Blocks.CHAIN.defaultBlockState());
				}
				if (this.random.nextInt(4) == 0 && isAir(bx, by - chain - 1, bz)) {
					set(bx, by - chain - 1, bz, Blocks.SOUL_LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true));
				}
			}
			// twigs: up, then on out, then up again - thin and crooked
			Direction side = this.random.nextBoolean() ? d.getClockWise() : d.getCounterClockWise();
			int twigs = 2 + this.random.nextInt(3);
			for (int s = 0; s < twigs; s++) {
				if (s % 2 == 0) {
					by++;
				} else {
					Direction step = this.random.nextInt(3) == 0 ? side : d;
					bx += step.getStepX();
					bz += step.getStepZ();
				}
				if (!insideFence(bx, bz)) {
					break;
				}
				if (isAir(bx, by, bz)) {
					connectable(bx, by, bz, twig);
				}
			}
			if (this.random.nextInt(5) == 0 && isAir(bx, by + 1, bz)) {
				set(bx, by + 1, bz, Blocks.COBWEB);
			}
		}
	}

	/** Twelve graves in three rows behind a low wall: mounds of earth, crosses and worn headstones. */
	private void graveyard(int x0, int z0) {
		for (int x = x0; x <= x0 + 14; x++) {
			for (int z = z0; z <= z0 + 17; z++) {
				boolean edge = x == x0 || x == x0 + 14 || z == z0 || z == z0 + 17;
				if (edge && !(x == x0 + 14 && z >= z0 + 8 && z <= z0 + 9)) { // an opening towards the house
					connectable(x, 1, z, Blocks.MOSSY_COBBLESTONE_WALL.defaultBlockState());
				} else if (!edge && this.random.nextInt(5) == 0) {
					set(x, 0, z, Blocks.PODZOL);
				}
			}
		}
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 4; col++) {
				int gx = x0 + 2 + col * 3;
				int gz = z0 + 3 + row * 5;
				set(gx, 0, gz + 1, Blocks.COARSE_DIRT); // the mound
				set(gx, 0, gz + 2, Blocks.COARSE_DIRT);
				if (this.random.nextInt(3) == 0) {
					set(gx, 1, gz, ModRegistry.WOODEN_CROSS);
				} else {
					connectable(gx, 1, gz, (this.random.nextBoolean() ? Blocks.STONE_BRICK_WALL : Blocks.MOSSY_STONE_BRICK_WALL).defaultBlockState());
				}
				if (this.random.nextInt(3) == 0) {
					set(gx, 1, gz + 1, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 1 + this.random.nextInt(3)));
				} else if (this.random.nextInt(3) == 0) {
					set(gx, 1, gz + 2, Blocks.DEAD_BUSH);
				}
			}
		}
	}

	/** An old stone well under a little roof, a chain going down into black water. */
	private void well(int cx, int cz) {
		for (int x = cx - 1; x <= cx + 1; x++) {
			for (int z = cz - 1; z <= cz + 1; z++) {
				if (x == cx && z == cz) {
					fill(x, -6, z, x, 0, z, Blocks.AIR);
					fill(x, -6, z, x, -4, z, Blocks.WATER);
					set(x, -7, z, Blocks.MOSSY_COBBLESTONE);
				} else {
					set(x, 0, z, Blocks.MOSSY_COBBLESTONE);
					connectable(x, 1, z, Blocks.MOSSY_COBBLESTONE_WALL.defaultBlockState());
				}
			}
		}
		for (int x = cx - 1; x <= cx + 1; x++) { // stone shaft below
			for (int z = cz - 1; z <= cz + 1; z++) {
				if (x != cx || z != cz) {
					fill(x, -7, z, x, -1, z, Blocks.MOSSY_COBBLESTONE);
				}
			}
		}
		for (int z : new int[]{cz - 1, cz + 1}) {
			connectable(cx, 2, z, Blocks.DARK_OAK_FENCE.defaultBlockState());
			connectable(cx, 3, z, Blocks.DARK_OAK_FENCE.defaultBlockState());
		}
		for (int z = cz - 1; z <= cz + 1; z++) {
			for (int x = cx - 1; x <= cx + 1; x++) {
				set(x, 4, z, Blocks.DARK_OAK_SLAB);
			}
		}
		set(cx, 3, cz, Blocks.CHAIN);
		set(cx, 2, cz, Blocks.CHAIN);
	}

	/** Hay, a fence post and a carved pumpkin that always seems to face the house. */
	private void scarecrow(int x, int z) {
		connectable(x, 1, z, Blocks.DARK_OAK_FENCE.defaultBlockState());
		set(x, 2, z, Blocks.HAY_BLOCK);
		connectable(x - 1, 2, z, Blocks.DARK_OAK_FENCE.defaultBlockState()); // arms
		connectable(x + 1, 2, z, Blocks.DARK_OAK_FENCE.defaultBlockState());
		set(x, 3, z, Blocks.CARVED_PUMPKIN.defaultBlockState()
				.setValue(net.minecraft.world.level.block.CarvedPumpkinBlock.FACING, Direction.NORTH));
		set(x - 1, 1, z + 1, Blocks.PUMPKIN);
	}

	/** Two old lamps by the path; like the ones inside they flicker when the monster is near. */
	private void pathLamps() {
		for (int[] l : new int[][]{{DOOR_X - 2, -4}, {DOOR_X + 2, -8}}) {
			set(l[0], 1, l[1], ModRegistry.LAMP_POLE);
			set(l[0], 2, l[1], ModRegistry.LAMP_POLE);
			set(l[0], 3, l[1], ModRegistry.STATION_LAMP);
			this.lamps.add(at(l[0], 3, l[1]));
		}
	}

	/** Tall grass, ferns, dead bushes, mushrooms, rotting pumpkins, boulders and fallen logs. */
	private void overgrowth() {
		for (int i = 0; i < 700; i++) {
			int x = YARD_MIN_X + 1 + this.random.nextInt(YARD_MAX_X - YARD_MIN_X - 1);
			int z = YARD_FRONT_Z + 1 + this.random.nextInt(YARD_BACK_Z - YARD_FRONT_Z - 1);
			if (x >= -1 && x <= WIDTH + 1 && z >= -1 && z <= DEPTH + 1) {
				continue; // not inside the house
			}
			if (Math.abs(x - DOOR_X) <= 1 && z < 0) {
				continue; // keep the path clear
			}
			if (!isAir(x, 1, z) || !this.level.getBlockState(at(x, 0, z)).is(Blocks.GRASS_BLOCK)) {
				continue;
			}
			int roll = this.random.nextInt(100);
			if (roll < 38) {
				set(x, 1, z, Blocks.GRASS);
			} else if (roll < 58 && isAir(x, 2, z)) {
				BlockState tall = (this.random.nextBoolean() ? Blocks.TALL_GRASS : Blocks.LARGE_FERN).defaultBlockState();
				set(x, 1, z, tall.setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, DoubleBlockHalf.LOWER));
				set(x, 2, z, tall.setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
			} else if (roll < 72) {
				set(x, 1, z, Blocks.DEAD_BUSH);
			} else if (roll < 80) {
				set(x, 1, z, Blocks.FERN);
			} else if (roll < 86) {
				set(x, 1, z, Blocks.BROWN_MUSHROOM);
			} else if (roll < 89) {
				set(x, 1, z, Blocks.PUMPKIN);
			} else if (roll < 94) {
				set(x, 1, z, this.random.nextBoolean() ? Blocks.MOSSY_COBBLESTONE : Blocks.COBBLESTONE); // a boulder
			} else if (roll < 97 && this.free(x, z, 1)) {
				Direction d = Direction.Plane.HORIZONTAL.getRandomDirection(this.random);
				for (int k = 0; k < 3 + this.random.nextInt(2); k++) { // a fallen log
					int lx = x + d.getStepX() * k, lz = z + d.getStepZ() * k;
					if (insideFence(lx, lz) && isAir(lx, 1, lz) && !(Math.abs(lx - DOOR_X) <= 1 && lz < 0)) {
						set(lx, 1, lz, Blocks.DARK_OAK_LOG.defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, d.getAxis()));
					}
				}
			} else {
				set(x, 0, z, Blocks.COARSE_DIRT);
			}
		}
	}

	// ------------------------------------------------------------------ the secret room

	/**
	 * In a library (or a study, if there is no library) the back three rows become a hidden room behind a
	 * wall of shelves. One of them has a red book sticking out: pull it and the shelf slides away.
	 * Inside: candles, a skull, and a chest with one of the six notes, batteries and bottles.
	 */
	private void secretRoom() {
		List<Room> candidates = new ArrayList<>();
		for (Kind wanted : new Kind[]{Kind.LIBRARY, Kind.STUDY}) {
			for (java.util.Map.Entry<Room, Kind> entry : this.kinds.entrySet()) {
				if (entry.getValue() == wanted) {
					candidates.add(entry.getKey());
				}
			}
			if (!candidates.isEmpty()) {
				break;
			}
		}
		if (candidates.isEmpty()) {
			return;
		}
		candidates.sort(java.util.Comparator.comparingInt((Room room) -> room.y() * 1000 + room.x1() * 10 + (room.north() ? 0 : 1)));
		Room r = candidates.get(this.random.nextInt(candidates.size()));
		int y = r.y();
		int back = r.backZ();
		int step = r.towardsDoor();
		int wallZ = back + step * 3;
		AABB hidden = new AABB(at(r.x1(), y, Math.min(back, wallZ)), at(r.x2() + 1, y + 5, Math.max(back, wallZ) + 1));
		this.level.getEntitiesOfClass(Painting.class, hidden).forEach(net.minecraft.world.entity.Entity::discard);
		this.lootSpots.removeIf(pos -> hidden.contains(Vec3.atCenterOf(pos)));
		this.lamps.removeIf(pos -> hidden.contains(Vec3.atCenterOf(pos)));
		for (int x = r.x1(); x <= r.x2(); x++) {
			for (int k = 0; k < 3; k++) {
				for (int h = 0; h <= 4; h++) {
					set(x, y + h, back + step * k, Blocks.AIR);
				}
			}
		}
		// the false wall, floor to ceiling
		fill(r.x1(), y, wallZ, r.x2(), y + 4, wallZ, ModRegistry.OLD_BOOKSHELF);
		int sx = r.midX();
		// the red book sticks out towards the room
		set(sx, y, wallZ, ModRegistry.SECRET_BOOKSHELF.defaultBlockState().setValue(com.lasttrain.block.SecretBookshelfBlock.FACING, r.faceDoor()));
		// keep the way to the red book clear
		for (int h = 0; h <= 1; h++) {
			BlockState front = this.level.getBlockState(at(sx, y + h, wallZ + step));
			if (!front.isAir() && !(front.getBlock() instanceof CarpetBlock)) {
				set(sx, y + h, wallZ + step, Blocks.AIR);
			}
		}
		this.lootSpots.removeIf(pos -> pos.equals(at(sx, y, wallZ + step)));

		String previous = this.currentRoom;
		this.currentRoom = "room.lasttrain.secret";
		decor(Blocks.CHEST, sx, y, back, r.faceDoor());
		this.secretChest = at(sx, y, back);
		set(r.x1(), y, back, Blocks.SKELETON_SKULL);
		set(r.x2(), y, back, ModRegistry.OLD_TABLE);
		candle(r.x2(), y + 1, back);
		candle(sx - 1, y, back);
		candle(sx + 1, y, back);
		rug(ModRegistry.RED_RUG, sx - 1, back + step, sx + 1, back + step * 2, y);
		set(r.x1(), y + 3, back, Blocks.COBWEB);
		set(r.x2(), y + 3, back + step * 2, Blocks.COBWEB);
		painting(r.x1() + 1, y + 2, back, r.faceDoor());
		this.currentRoom = previous;
	}

	// ------------------------------------------------------------------ a different map every game

	/** Pairs of neighbouring rooms (column indices into ROOM_X) that share a wall. */
	private static final int[][] NEIGHBOURS = {{0, 1}, {1, 2}, {3, 4}, {4, 5}};

	private static Room room(int storey, int column, boolean north) {
		int[] rx = ROOM_X[column];
		int y = storey * STOREY + 1;
		return north ? new Room(rx[0], rx[1], 1, 9, y, true) : new Room(rx[0], rx[1], 15, 24, y, false);
	}

	/** About half the neighbouring rooms get a door between them (never into a player's bedroom). */
	private void sideDoors() {
		for (int storey = 0; storey < 2; storey++) {
			for (boolean north : new boolean[]{true, false}) {
				for (int[] pair : NEIGHBOURS) {
					Room left = room(storey, pair[0], north);
					Room right = room(storey, pair[1], north);
					if (this.kinds.get(left) == Kind.PLAYER_BEDROOM || this.kinds.get(right) == Kind.PLAYER_BEDROOM
							|| this.random.nextFloat() >= 0.55f) {
						continue;
					}
					int wall = left.x2() + 1;
					List<Integer> spots = new ArrayList<>();
					for (int z = left.z1() + 1; z <= left.z2() - 1; z++) {
						spots.add(z);
					}
					Collections.shuffle(spots, new java.util.Random(this.random.nextLong()));
					for (int z : spots) {
						// both sides free, so the furniture doesn't block it
						if (walkable(wall - 1, left.y(), z) && walkable(wall + 1, left.y(), z)
								&& reachesDoor(left, wall - 1, z) && reachesDoor(right, wall + 1, z)) {
							door(ModRegistry.OLD_DOOR, wall, left.y(), z, Direction.EAST);
							this.sideDoors.add(new Room[]{left, right});
							break;
						}
					}
				}
			}
		}
	}

	/** Can you walk inside {@code r} from (x, z) to its gallery door? (Not into a pocket between cupboards.) */
	private boolean reachesDoor(Room r, int x, int z) {
		int entryZ = r.north() ? r.z2() : r.z1();
		java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
		java.util.Set<Long> seen = new java.util.HashSet<>();
		queue.add(new int[]{x, z});
		seen.add(((long) x << 32) | (z & 0xFFFFFFFFL));
		while (!queue.isEmpty()) {
			int[] cell = queue.poll();
			if (cell[0] == r.doorX() && cell[1] == entryZ) {
				return true;
			}
			for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				int nx = cell[0] + d[0];
				int nz = cell[1] + d[1];
				if (nx < r.x1() || nx > r.x2() || nz < r.z1() || nz > r.z2() || !walkable(nx, r.y(), nz)) {
					continue;
				}
				if (seen.add(((long) nx << 32) | (nz & 0xFFFFFFFFL))) {
					queue.add(new int[]{nx, nz});
				}
			}
		}
		return false;
	}

	private boolean walkable(int x, int y, int z) {
		BlockState feet = this.level.getBlockState(at(x, y, z));
		return (feet.isAir() || feet.getBlock() instanceof CarpetBlock) && isAir(x, y + 1, z);
	}

	/** On each floor, maybe: the gallery caved in between two rooms that have a door between them. */
	private void planRubble() {
		for (int storey = 0; storey < 2; storey++) {
			int y = storey * STOREY + 1;
			List<Room[]> options = new ArrayList<>();
			for (Room[] pair : this.sideDoors) {
				if (pair[0].y() == y) {
					options.add(pair);
				}
			}
			if (options.isEmpty() || this.random.nextFloat() >= 0.6f) {
				continue;
			}
			Room[] detour = options.get(this.random.nextInt(options.size()));
			this.keepOpen.add(detour[0]);
			this.keepOpen.add(detour[1]);
			this.rubble.add(new int[]{y, detour[0].x2() + 1});
		}
	}

	/** Some rooms with a side door get their gallery door boarded up: you get in through the neighbour. */
	private void boardDoors() {
		List<Room[]> pairs = new ArrayList<>(this.sideDoors);
		Collections.shuffle(pairs, new java.util.Random(this.random.nextLong()));
		java.util.Set<Room> boarded = new java.util.HashSet<>();
		for (Room[] pair : pairs) {
			if (this.random.nextFloat() >= 0.45f) {
				continue;
			}
			int pick = this.random.nextInt(2);
			Room shut = pair[pick];
			Room through = pair[1 - pick];
			if (this.keepOpen.contains(shut) || boarded.contains(shut) || boarded.contains(through)) {
				continue;
			}
			BlockState boards = ModRegistry.BOARDED_DOOR.defaultBlockState();
			set(shut.doorX(), shut.y(), shut.doorWallZ(), boards.setValue(com.lasttrain.block.BoardedDoorBlock.HALF, DoubleBlockHalf.LOWER));
			set(shut.doorX(), shut.y() + 1, shut.doorWallZ(), boards.setValue(com.lasttrain.block.BoardedDoorBlock.HALF, DoubleBlockHalf.UPPER));
			boarded.add(shut);
			this.keepOpen.add(through);
		}
	}

	/** Beams, boards and stone from the ceiling, wall to wall across the gallery. */
	private void placeRubble() {
		for (int[] r : this.rubble) {
			int y = r[0];
			int wall = r[1];
			AABB box = new AABB(at(wall - 1, y, 11), at(wall + 2, y + 5, 14));
			this.level.getEntitiesOfClass(Painting.class, box.inflate(0.5)).forEach(net.minecraft.world.entity.Entity::discard);
			this.lootSpots.removeIf(pos -> box.contains(Vec3.atCenterOf(pos)));
			this.lamps.removeIf(pos -> box.contains(Vec3.atCenterOf(pos)));
			for (int x = wall - 1; x <= wall + 1; x++) {
				for (int z = 11; z <= 13; z++) {
					for (int h = 0; h <= 4; h++) {
						float roll = this.random.nextFloat();
						Block block;
						if (h <= 1) {
							block = roll < 0.5f ? ModRegistry.WEATHERED_PLANKS : roll < 0.75f ? ModRegistry.WEATHERED_BEAM : ModRegistry.DAMP_STONE;
						} else if (h == 2) {
							block = roll < 0.45f ? ModRegistry.WEATHERED_PLANKS : roll < 0.75f ? ModRegistry.WEATHERED_BEAM : Blocks.COBWEB;
						} else {
							block = roll < 0.35f ? ModRegistry.WEATHERED_PLANKS : roll < 0.55f ? ModRegistry.WEATHERED_BEAM
									: roll < 0.75f ? Blocks.COBWEB : Blocks.AIR;
						}
						set(x, y + h, z, block);
					}
				}
			}
			// cobwebs and dust spilling out on both sides
			for (int side : new int[]{-2, 2}) {
				for (int z = 11; z <= 13; z++) {
					if (this.random.nextInt(3) == 0 && isAir(wall + side, y + 2, z)) {
						set(wall + side, y + 2, z, Blocks.COBWEB);
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------ clocks and chandeliers

	/** Hangs a chandelier from the ceiling (the block under it); about a third still have burning candles. */
	private void chandelier(int x, int y, int z) {
		if (isAir(x, y, z) && !isAir(x, y + 1, z)) {
			set(x, y, z, ModRegistry.CHANDELIER.defaultBlockState()
					.setValue(com.lasttrain.block.ChandelierBlock.LIT, this.random.nextInt(3) == 0));
			this.chandeliers++;
		}
	}

	/** A grandfather clock ticking in the foyer, and another one upstairs above it. */
	private void clocks() {
		for (int y : new int[]{1, STOREY + 1}) {
			if (isAir(31, y + 1, 3)) {
				set(31, y, 3, ModRegistry.GRANDFATHER_CLOCK.defaultBlockState()
						.setValue(com.lasttrain.block.GrandfatherClockBlock.FACING, Direction.WEST));
				set(31, y + 1, 3, ModRegistry.CLOCK_TOP);
			}
		}
	}

	// ------------------------------------------------------------------ corridors

	private void corridors() {
		this.currentRoom = "room.lasttrain.gallery_corridor";
		for (int storey = 0; storey < 2; storey++) {
			int y = storey * STOREY + 1;
			for (int x = 6; x < WIDTH; x += 12) {
				this.patrolPoints.add(at(x, y, 12));
			}
			// the foot and the head of the stairs: where it changes floors
			this.patrolPoints.add(storey == 0 ? at(30, y, 24) : at(30, y, 15));
			this.stairLandings.add(storey == 0 ? at(30, y, 24) : at(30, y, 15));
			rug(ModRegistry.RED_RUG, 1, 12, WIDTH - 1, 12, y); // runner along the whole gallery
			for (int x = 3; x < WIDTH - 1; x += 6) {
				if (x >= 27 && x <= 33) {
					continue;
				}
				painting(x, y + 2, 11, Direction.SOUTH);
				painting(x + 2, y + 2, 13, Direction.NORTH);
			}
			// cabinets and lamps along the gallery, never in front of a door
			for (int x = 2; x < WIDTH - 1; x += 7) {
				if ((x >= 27 && x <= 33) || isDoorColumn(x)) {
					continue;
				}
				if (this.random.nextBoolean()) {
					loot(anyContainer(), x, y, 11, Direction.SOUTH);
				} else {
					lamp(x, y, 11);
				}
				if (this.random.nextInt(3) == 0) {
					set(x + 1, y + 3, 13, Blocks.COBWEB);
				}
			}
			for (int x = 5; x < WIDTH - 1; x += 9) {
				if (!(x >= 27 && x <= 33) && !isDoorColumn(x)) {
					loot(anyContainer(), x, y, 13, Direction.NORTH);
					candle(x, y + 1, 13);
				}
			}
			// chandeliers down the gallery, clear of the ladders and the stairs
			for (int x = 7; x < WIDTH - 3; x += 12) {
				if (x < 27 || x > 33) {
					chandelier(x, y + 4, 12);
				}
			}
			// wardrobes to hide in along the gallery
			for (int x : new int[]{7, 24, 38, 55}) {
				if (!isDoorColumn(x)) {
					wardrobe(x, y, 13, Direction.NORTH);
				}
			}
		}
		// the foyer behind the front door
		rug(ModRegistry.RED_RUG, 29, 1, 31, 9, 1);
		lamp(29, 1, 8);
		loot(ModRegistry.DISPLAY_CABINET, 31, 1, 8, Direction.WEST);
		painting(29, 3, 4, Direction.EAST);
		painting(31, 3, 5, Direction.WEST);
		// upstairs above the foyer: a small shrine at the end of the corridor
		int u = STOREY + 1;
		set(30, u, 1, ModRegistry.OLD_TABLE);
		set(30, u + 1, 1, Blocks.SKELETON_SKULL);
		candle(29, u, 1);
		candle(31, u, 1);
		rug(ModRegistry.RED_RUG, 30, 2, 30, 9, u);
		loot(ModRegistry.CABINET, 29, u, 6, Direction.EAST);
		loot(ModRegistry.DRESSER, 31, u, 6, Direction.WEST);
	}

	private static boolean isDoorColumn(int x) {
		for (int[] rx : ROOM_X) {
			if (Math.abs(x - (rx[0] + rx[1]) / 2) <= 1) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ basement and attic

	/**
	 * Basement under the north-east wing (x=33..59, z=1..13, y=-5..-1), reached by a ladder at the east
	 * end of the ground floor gallery. Dark until the fuse box on its west wall gets a fuse.
	 */
	private void basement() {
		fill(32, -7, 0, WIDTH, -1, 14, ModRegistry.DAMP_STONE);
		fill(33, -5, 1, WIDTH - 1, -1, 13, Blocks.AIR);
		BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST);
		for (int y = -5; y <= 1; y++) {
			set(WIDTH - 1, y, 12, ladder);
		}
		set(33, -3, 6, ModRegistry.FUSE_BOX.defaultBlockState().setValue(FuseBoxBlock.FACING, Direction.EAST));
		for (int[] l : new int[][]{{36, 3}, {46, 7}, {56, 3}}) {
			set(l[0], -5, l[1], ModRegistry.WEATHERED_BEAM);
			set(l[0], -4, l[1], ModRegistry.STATION_LAMP.defaultBlockState().setValue(LampBlock.LIT, false));
			this.basementLamps.add(at(l[0], -4, l[1]));
		}
		String previous = this.currentRoom;
		this.currentRoom = "room.lasttrain.basement";
		int before = this.lootSpots.size();
		for (int i = 0; i < 14; i++) {
			int x = 35 + this.random.nextInt(WIDTH - 37);
			int z = 1 + this.random.nextInt(13);
			if (z == 12 && x >= WIDTH - 2) {
				continue; // keep the ladder free
			}
			if (this.random.nextInt(3) == 0) {
				if (isAir(x, -5, z)) {
					set(x, -5, z, Blocks.BARREL);
				}
			} else {
				loot(ModRegistry.CRATE, x, -5, z, Direction.NORTH);
				if (this.random.nextBoolean() && isAir(x, -4, z)) {
					set(x, -4, z, ModRegistry.CRATE.defaultBlockState());
				}
			}
		}
		loot(ModRegistry.CABINET, 34, -5, 1, Direction.SOUTH);
		loot(Blocks.CHEST, 34, -5, 13, Direction.NORTH);
		set(40, -5, 10, ModRegistry.OLD_TABLE);
		candle(40, -4, 10);
		set(41, -5, 10, Blocks.SKELETON_SKULL);
		for (int x = 38; x < WIDTH - 2; x += 5) {
			set(x, -1, 5, Blocks.CHAIN);
			set(x, -2, 5, Blocks.CHAIN);
			set(x + 2, -1, 9, Blocks.COBWEB);
		}
		fill(50, -5, 9, 50, -3, 13, Blocks.IRON_BARS); // an old cell
		fill(50, -5, 9, 54, -3, 9, Blocks.IRON_BARS);
		set(52, -5, 11, Blocks.SKELETON_SKULL);
		// everything placed down here is basement loot
		this.basementSpots.addAll(this.lootSpots.subList(before, this.lootSpots.size()));
		this.lootSpots.subList(before, this.lootSpots.size()).clear();
		this.currentRoom = previous;
	}

	/** Attic under the roof (y=13..15), up a ladder at the west end of the upstairs gallery. */
	private void attic() {
		int floor = 2 * STOREY;
		for (int y = floor + 1; y <= floor + 3; y++) {
			for (int x = 0; x <= WIDTH; x++) {
				set(x, y, 0, ModRegistry.WEATHERED_PLANKS);
				set(x, y, DEPTH, ModRegistry.WEATHERED_PLANKS);
			}
			for (int z = 0; z <= DEPTH; z++) {
				set(0, y, z, ModRegistry.WEATHERED_PLANKS);
				set(WIDTH, y, z, ModRegistry.WEATHERED_PLANKS);
			}
		}
		fill(-1, floor + 4, -1, WIDTH + 1, floor + 4, DEPTH + 1, ModRegistry.ROOF_SHINGLES);
		fill(3, floor + 5, 3, WIDTH - 3, floor + 5, DEPTH - 3, ModRegistry.ROOF_SHINGLES);
		for (int x = 10; x < WIDTH; x += 10) {
			fill(x, floor + 1, 1, x, floor + 3, 1, ModRegistry.WEATHERED_BEAM); // rafters
			fill(x, floor + 1, DEPTH - 1, x, floor + 3, DEPTH - 1, ModRegistry.WEATHERED_BEAM);
		}
		BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.EAST);
		for (int y = STOREY + 1; y <= floor + 1; y++) {
			set(1, y, 12, ladder);
		}

		String previous = this.currentRoom;
		this.currentRoom = "room.lasttrain.attic";
		int before = this.lootSpots.size();
		int y = floor + 1;
		for (int i = 0; i < 26; i++) {
			int x = 3 + this.random.nextInt(WIDTH - 5);
			int z = 1 + this.random.nextInt(DEPTH - 1);
			if (Math.abs(x - 1) <= 2 && Math.abs(z - 12) <= 2) {
				continue;
			}
			switch (this.random.nextInt(5)) {
				case 0 -> set(x, y + 2, z, Blocks.COBWEB);
				case 1 -> {
					if (isAir(x, y, z)) {
						set(x, y, z, Blocks.BARREL);
					}
				}
				default -> {
					loot(ModRegistry.CRATE, x, y, z, Direction.NORTH);
					if (this.random.nextBoolean() && isAir(x, y + 1, z)) {
						set(x, y + 1, z, ModRegistry.CRATE.defaultBlockState());
					}
				}
			}
		}
		loot(ModRegistry.CABINET, 20, y, 1, Direction.SOUTH);
		loot(ModRegistry.DRESSER, 40, y, DEPTH - 1, Direction.NORTH);
		loot(Blocks.CHEST, WIDTH - 2, y, 12, Direction.WEST);
		bed(Blocks.GRAY_BED, 30, y, 1, true);
		wardrobe(33, y, 1, Direction.SOUTH);
		chair(28, y, 4, Direction.SOUTH);
		set(31, y, 3, Blocks.SKELETON_SKULL);
		rug(ModRegistry.DUSTY_RUG, 26, 3, 34, 7, y);
		lamp(6, y, 10);
		lamp(46, y, 14);
		this.atticSpots.addAll(this.lootSpots.subList(before, this.lootSpots.size()));
		this.lootSpots.subList(before, this.lootSpots.size()).clear();
		this.currentRoom = previous;
	}

	/** Some ground floor boards creak - you learn where they are or you pay for it. */
	private void creakyFloors() {
		for (int x = 1; x < WIDTH; x++) {
			for (int z = 1; z < DEPTH; z++) {
				if (this.random.nextInt(14) == 0 && this.level.getBlockState(at(x, 0, z)).is(ModRegistry.ROTTEN_FLOORBOARDS)
						&& !this.level.getBlockState(at(x, 1, z)).isSolid()) {
					set(x, 0, z, ModRegistry.CREAKY_FLOORBOARDS);
				}
			}
		}
	}

	// ------------------------------------------------------------------ loot

	/**
	 * Hides everything the escape needs: the key (house), the crowbar (basement), the fuse (attic),
	 * plus a flashlight, batteries, bottles to throw, six notes and the family photo.
	 */
	private void distributeLoot() {
		com.lasttrain.config.LastTrainConfig.Difficulty difficulty = com.lasttrain.config.LastTrainConfig.get().difficulty();
		// furniture placed later (the cell bars, the table, the attic bed...) may have replaced a crate: never
		// hide anything in a spot that isn't a container any more, or the crowbar or the fuse would be lost
		for (List<BlockPos> spots : List.of(this.lootSpots, this.basementSpots, this.atticSpots)) {
			spots.removeIf(pos -> !(this.level.getBlockEntity(pos) instanceof Container));
		}
		List<BlockPos> house = new ArrayList<>(this.lootSpots);
		Collections.shuffle(house, new java.util.Random(this.random.nextLong()));
		List<BlockPos> basement = new ArrayList<>(this.basementSpots);
		Collections.shuffle(basement, new java.util.Random(this.random.nextLong()));
		List<BlockPos> attic = new ArrayList<>(this.atticSpots);
		Collections.shuffle(attic, new java.util.Random(this.random.nextLong()));
		List<BlockPos> secret = this.secretChest != null && this.level.getBlockEntity(this.secretChest) instanceof Container
				? List.of(this.secretChest) : List.of();
		if (!secret.isEmpty()) {
			put(secret, 0, new ItemStack(ModRegistry.BATTERY, 2));
			put(secret, 0, new ItemStack(ModRegistry.THROWABLE_BOTTLE, 3));
		}

		BlockPos keySpot = put(house, 0, new ItemStack(ModRegistry.HOUSE_KEY));
		if (keySpot != null) {
			this.keyRoom = this.spotRoom.getOrDefault(keySpot, this.keyRoom);
		}
		put(basement.isEmpty() ? house : basement, 0, new ItemStack(ModRegistry.CROWBAR));
		put(attic.isEmpty() ? house : attic, 0, new ItemStack(ModRegistry.FUSE));
		put(house, 1, new ItemStack(ModRegistry.FLASHLIGHT));
		put(house, 2, new ItemStack(ModRegistry.FAMILY_PHOTO));
		for (int i = 0; i < Math.max(1, 4 + difficulty.extraBatteries); i++) {
			put(i % 2 == 0 ? house : attic, 3 + i, new ItemStack(ModRegistry.BATTERY));
		}
		for (int i = 0; i < Math.max(1, 8 + difficulty.extraBottles); i++) {
			put(house, 7 + i, new ItemStack(ModRegistry.THROWABLE_BOTTLE));
		}
		for (int n = 1; n <= NoteItem.COUNT; n++) {
			// note 4 waits in the secret room: without finding it there is no good ending
			List<BlockPos> where = n == 4 && !secret.isEmpty() ? secret : n == 5 ? basement : n == 6 ? attic : house;
			put(where.isEmpty() ? house : where, 15 + n, NoteItem.create(ModRegistry.NOTE, n));
		}
	}

	/** Puts the item into the container at {@code index} (wrapping around), in a free slot. */
	private BlockPos put(List<BlockPos> spots, int index, ItemStack stack) {
		if (spots.isEmpty()) {
			return null;
		}
		BlockPos pos = spots.get(index % spots.size());
		if (this.level.getBlockEntity(pos) instanceof Container container) {
			int size = container.getContainerSize();
			int start = this.random.nextInt(size);
			for (int i = 0; i < size; i++) {
				int slot = (start + i) % size;
				if (container.getItem(slot).isEmpty()) {
					container.setItem(slot, stack);
					return pos;
				}
			}
		}
		return null;
	}

}
