package com.lasttrain.game;

import com.lasttrain.block.WardrobeBlock;
import com.lasttrain.block.CabinetBlock;
import com.lasttrain.block.LockedDoorBlock;
import com.lasttrain.entity.BlindOneEntity;
import com.lasttrain.entity.DoppelgangerEntity;
import com.lasttrain.entity.DollEntity;
import com.lasttrain.entity.ThrownBottleEntity;
import com.lasttrain.entity.TrainEntity;
import com.lasttrain.entity.WatcherEntity;
import com.lasttrain.item.FlashlightItem;
import com.lasttrain.item.NoteItem;
import com.lasttrain.noise.NoiseSystem;
import com.lasttrain.registry.ModRegistry;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plays the game instead of a person: the whole escape from the house to the train, the monster's hearing
 * and hunting, hiding, lures, the Watcher, the doll, the Double, the lamps, the random map, the secret room, the good ending
 * and the curse's days.
 * Run with {@code ./gradlew runGametest}. Each test has its own batch (they share global game state).
 */
public class LastTrainPlaythroughTests implements FabricGameTest {
	private static final int ARENA = 18;

	private static BlockPos spot(int index) {
		return new BlockPos(-30000 + index * 1500, 120, -30000);
	}

	/** A flat stone floor with open air above, far from everything else. */
	private static void arena(ServerLevel level, BlockPos c) {
		forceArena(level, c, true);
		for (int x = -ARENA; x <= ARENA; x++) {
			for (int z = -ARENA; z <= ARENA; z++) {
				level.setBlock(c.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
				for (int y = 0; y <= 5; y++) {
					level.setBlock(c.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
				}
			}
		}
	}

	/**
	 * Vanilla's mock server player always reports creative (and the mod ignores creative players),
	 * so survival players are made here the same way, minus that override.
	 */
	private static ServerPlayer survivor(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = new ServerPlayer(level.getServer(), level,
				new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "test-survivor")) {
			@Override
			public boolean isSpectator() {
				return false;
			}
		};
		level.getServer().getPlayerList().placeNewPlayer(
				new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND), player);
		player.setGameMode(GameType.SURVIVAL);
		return player;
	}

	/** The test server only ticks chunks near the test grid; force-load the area a test plays in. */
	private static void forceChunks(ServerLevel level, BlockPos from, BlockPos to, boolean load) {
		for (int cx = Math.min(from.getX(), to.getX()) >> 4; cx <= Math.max(from.getX(), to.getX()) >> 4; cx++) {
			for (int cz = Math.min(from.getZ(), to.getZ()) >> 4; cz <= Math.max(from.getZ(), to.getZ()) >> 4; cz++) {
				level.setChunkForced(cx, cz, load);
			}
		}
	}

	private static void forceArena(ServerLevel level, BlockPos c, boolean load) {
		forceChunks(level, c.offset(-ARENA, 0, -ARENA), c.offset(ARENA, 0, ARENA), load);
	}

	/** The house, the platform and the stretch of track the train uses. */
	private static void forceHouse(ServerLevel level, BlockPos feet, boolean load) {
		forceChunks(level, feet.offset(-120, 0, -24), feet.offset(100, 0, 32), load);
	}

	private static ServerPlayer player(GameTestHelper helper, BlockPos at, float yaw, GameType mode) {
		ServerPlayer player = mode == GameType.CREATIVE ? helper.makeMockServerPlayerInLevel() : survivor(helper);
		player.teleportTo(helper.getLevel(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw, 0.0f);
		player.setYHeadRot(yaw); // where it looks (a real client sends this)
		return player;
	}

	private static BlindOneEntity monster(ServerLevel level, BlockPos at) {
		BlindOneEntity monster = ModRegistry.BLIND_ONE.create(level);
		monster.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
		level.addFreshEntity(monster);
		return monster;
	}

	/** Forced chunks load their entities a few ticks later; until then nothing can find the monster (and it can't hear). */
	private static void loadedIn(ServerLevel level, Entity entity) {
		check(entity.tickCount > 2 && level.getEntitiesOfClass(entity.getClass(), entity.getBoundingBox()).contains(entity),
				"the test arena never loaded");
	}

	private static void cleanUp(GameTestHelper helper, BlockPos around, ServerPlayer... players) {
		MinecraftServer server = helper.getLevel().getServer();
		GameSession.stop(server);
		HideManager.reset();
		forceArena(helper.getLevel(), around, false);
		forceHouse(helper.getLevel(), around, false);
		AABB area = new AABB(around).inflate(420, 60, 140);
		ServerLevel level = helper.getLevel();
		for (Class<? extends Entity> type : List.of(BlindOneEntity.class, DollEntity.class, WatcherEntity.class, TrainEntity.class, DoppelgangerEntity.class)) {
			level.getEntitiesOfClass(type, area).forEach(Entity::discard);
		}
		for (ServerPlayer player : players) {
			CurseManager.data(server).stash.remove(player.getUUID());
			BreathManager.setWantsToHold(player, false);
			server.getPlayerList().remove(player);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new GameTestAssertException(message);
		}
	}

	private static void useOn(ServerLevel level, ServerPlayer player, BlockPos pos, ItemStack stack) {
		player.setItemInHand(InteractionHand.MAIN_HAND, stack);
		BlockState state = level.getBlockState(pos);
		state.use(level, player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false));
	}

	private static String describe(ServerLevel level, BlockPos from, BlockPos to) {
		int containers = 0;
		Map<Item, Integer> items = new HashMap<>();
		Map<String, Integer> blocks = new HashMap<>();
		for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
			BlockState state = level.getBlockState(pos);
			if (!state.isAir()) {
				blocks.merge(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath(), 1, Integer::sum);
			}
			if (level.getBlockEntity(pos) instanceof Container container) {
				containers++;
				for (int i = 0; i < container.getContainerSize(); i++) {
					if (!container.getItem(i).isEmpty()) {
						items.merge(container.getItem(i).getItem(), container.getItem(i).getCount(), Integer::sum);
					}
				}
			}
		}
		return containers + " containers, items " + items + ", blocks " + blocks;
	}

	/** Everything inside the house's containers, basement and attic included. */
	private static Map<Item, Integer> loot(ServerLevel level, BlockPos origin, Set<Integer> notes) {
		Map<Item, Integer> found = new HashMap<>();
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(0, -7, 0), origin.offset(HouseBuilder.WIDTH, 16, HouseBuilder.DEPTH))) {
			if (level.getBlockEntity(pos) instanceof Container container) {
				for (int i = 0; i < container.getContainerSize(); i++) {
					ItemStack stack = container.getItem(i);
					if (!stack.isEmpty()) {
						found.merge(stack.getItem(), stack.getCount(), Integer::sum);
						if (stack.is(ModRegistry.NOTE)) {
							notes.add(NoteItem.number(stack));
						}
					}
				}
			}
		}
		return found;
	}

	// ------------------------------------------------------------------ the whole escape

	/**
	 * The full game: the house hides exactly one key, crowbar and fuse; boards + padlock + fuse open the
	 * front door; leaving the house calls the train; climbing onto it at the platform boards it;
	 * the train leaves and the game ends.
	 */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_01_escape", timeoutTicks = 4000)
	public void fullEscapeFromTheHouseToTheTrain(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos feet = spot(0);
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.getAbilities().invulnerable = true; // this test is about the escape, not about the monster
		forceHouse(level, feet, true);
		GameSession.start(level, feet, List.of(player));
		GameSession session = GameSession.current();
		HouseBuilder.Layout layout = session.layout();
		BlockPos o = layout.origin();

		Set<Integer> notes = new HashSet<>();
		Map<Item, Integer> loot = loot(level, o, notes);
		check(loot.getOrDefault(ModRegistry.HOUSE_KEY, 0) == 1, "keys in the house: " + loot.get(ModRegistry.HOUSE_KEY));
		check(loot.getOrDefault(ModRegistry.CROWBAR, 0) == 1, "crowbars in the house: " + loot.get(ModRegistry.CROWBAR)
				+ " (basement: " + describe(level, o.offset(33, -5, 1), o.offset(59, -1, 13)) + ")");
		check(loot.getOrDefault(ModRegistry.FUSE, 0) == 1, "fuses in the house: " + loot.get(ModRegistry.FUSE));
		check(loot.getOrDefault(ModRegistry.FAMILY_PHOTO, 0) == 1, "family photos: " + loot.get(ModRegistry.FAMILY_PHOTO));
		check(loot.getOrDefault(ModRegistry.FLASHLIGHT, 0) == 1, "flashlights: " + loot.get(ModRegistry.FLASHLIGHT));
		check(notes.size() == NoteItem.COUNT, "different notes found: " + notes);
		check(player.level() == level && layout.safeRooms().get(0).box().contains(player.position()), "the player didn't wake up in their bedroom");

		BlockPos door = layout.exitDoor();
		useOn(level, player, door, new ItemStack(ModRegistry.CROWBAR));
		useOn(level, player, door, new ItemStack(ModRegistry.HOUSE_KEY));
		check(!level.getBlockState(door).getValue(DoorBlock.OPEN), "the door opened without power");
		useOn(level, player, o.offset(33, -3, 6), new ItemStack(ModRegistry.FUSE));
		check(level.getBlockState(door).getValue(DoorBlock.OPEN), "the fuse was the last lock but the door stayed shut");

		player.teleportTo(level, o.getX() + 30.5, o.getY() + 1, o.getZ() - 6.5, 180.0f, 0.0f); // out on the path
		AABB railway = new AABB(o.offset(-200, 0, HouseBuilder.RAIL_Z - 4), o.offset(400, 6, HouseBuilder.RAIL_Z + 4));
		helper.startSequence()
				.thenWaitUntil(() -> check(session.stage() != GameSession.Stage.IN_HOUSE, "leaving the house didn't start the escape"))
				.thenWaitUntil(() -> check(level.getEntitiesOfClass(TrainEntity.class, railway).stream()
						.anyMatch(t -> t.getPhase() == TrainEntity.Phase.STOPPED), "the train hasn't stopped at the platform yet"))
				.thenExecute(() -> {
					// step onto the platform edge right next to the wagon
					player.teleportTo(level, layout.trainStopX(), o.getY() + 2, o.getZ() + HouseBuilder.RAIL_Z + 2.5, 0.0f, 0.0f);
				})
				.thenWaitUntil(() -> check(player.getVehicle() instanceof TrainEntity, "climbing onto the train didn't board it"))
				.thenWaitUntil(() -> check(GameSession.current() != session, "the train left but the game didn't end"))
				.thenWaitUntil(() -> {
					// the finale of the cutscene: it came out of the house and stands on the platform after the train
					Vec3 platform = new Vec3(o.getX() + HouseBuilder.DOOR_X + 0.5, o.getY() + 2.0, o.getZ() + HouseBuilder.RAIL_Z + 3.5);
					check(level.getEntitiesOfClass(BlindOneEntity.class, railway.inflate(0, 4, 40)).stream()
							.anyMatch(m -> m.inFinale() && m.position().distanceToSqr(platform.x, m.getY(), platform.z) < 2.5 * 2.5),
							"the Blind One didn't come out onto the platform after the train");
				})
				.thenExecute(() -> cleanUp(helper, feet, player))
				.thenSucceed();
	}

	/**
	 * The nightmare house is built where no player is (and no chunk is loaded) - exactly like here.
	 * Its loot must survive: without the key, crowbar and fuse the game can't be won.
	 */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_15_nobody_there", timeoutTicks = 200)
	public void aHouseBuiltWhereNobodyIsKeepsItsLoot(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos feet = spot(12);
		GameSession.start(level, feet, List.of());
		BlockPos o = GameSession.current().layout().origin();
		Map<Item, Integer> now = loot(level, o, new HashSet<>());
		helper.startSequence()
				.thenIdle(20)
				.thenExecute(() -> {
					forceHouse(level, feet, true); // load it again to look inside
					Map<Item, Integer> later = loot(level, o, new HashSet<>());
					String report = "right after building: key " + now.get(ModRegistry.HOUSE_KEY) + ", crowbar " + now.get(ModRegistry.CROWBAR)
							+ ", fuse " + now.get(ModRegistry.FUSE) + "; a second later: key " + later.get(ModRegistry.HOUSE_KEY)
							+ ", crowbar " + later.get(ModRegistry.CROWBAR) + ", fuse " + later.get(ModRegistry.FUSE);
					cleanUp(helper, feet);
					for (Item item : List.of(ModRegistry.HOUSE_KEY, ModRegistry.CROWBAR, ModRegistry.FUSE)) {
						check(now.getOrDefault(item, 0) == 1 && later.getOrDefault(item, 0) == 1, report);
					}
				})
				.thenSucceed();
	}

	// ------------------------------------------------------------------ the Blind One

	/** It hears a noise and walks there. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_02_hearing", timeoutTicks = 600)
	public void theMonsterWalksToANoise(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos c = spot(1);
		arena(level, c);
		ServerPlayer watcher = player(helper, c.offset(0, 0, -12), 0.0f, GameType.CREATIVE); // keeps the chunks ticking, makes no noise
		BlindOneEntity monster = monster(level, c);
		Vec3 noise = Vec3.atBottomCenterOf(c.offset(14, 0, 0));
		helper.startSequence()
				.thenWaitUntil(() -> loadedIn(level, monster))
				.thenExecute(() -> NoiseSystem.emit(level, noise, 16.0f, null))
				.thenWaitUntil(() -> check(monster.position().distanceTo(noise) < 3.0,
						"the monster is still " + (int) monster.position().distanceTo(noise) + " blocks from the noise (monster " + monster.position()
								+ ", noise " + noise + ", heard " + monster.getNoisePos() + ", nav done " + monster.getNavigation().isDone() + ", alive " + monster.isAlive() + ")"))
				.thenExecute(() -> cleanUp(helper, c, watcher))
				.thenSucceed();
	}

	/** A player making noise right next to it gets hunted and struck. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_03_hunt", timeoutTicks = 400)
	public void theMonsterKillsANoisyPlayerNextToIt(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos c = spot(2);
		arena(level, c);
		ServerPlayer victim = player(helper, c.offset(3, 0, 0), 90.0f, GameType.SURVIVAL);
		BlindOneEntity monster = monster(level, c);
		helper.startSequence()
				.thenWaitUntil(() -> loadedIn(level, monster))
				.thenExecute(() -> NoiseSystem.emit(level, victim.position(), 7.0f, victim))
				.thenWaitUntil(() -> check(monster.getPrey() == victim || !victim.isAlive(), "the monster didn't go after the player (monster " + monster.position()
						+ ", victim " + victim.position() + ", heard " + monster.getNoisePos() + ", victim mode " + victim.gameMode.getGameModeForPlayer() + ", creative " + victim.isCreative() + ")"))
				.thenWaitUntil(() -> check(!victim.isAlive() || victim.getHealth() < victim.getMaxHealth(), "the monster didn't strike the player"))
				.thenExecute(() -> cleanUp(helper, c, victim))
				.thenSucceed();
	}

	/** Hidden in a wardrobe and holding the breath: it stands right in front and never finds you. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_04_hide", timeoutTicks = 400)
	public void hidingAndHoldingTheBreathIsSafe(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos c = spot(3);
		arena(level, c);
		BlockState wardrobe = ModRegistry.WARDROBE.defaultBlockState().setValue(WardrobeBlock.FACING, Direction.NORTH);
		level.setBlock(c, wardrobe.setValue(WardrobeBlock.HALF, DoubleBlockHalf.LOWER), 3);
		level.setBlock(c.above(), wardrobe.setValue(WardrobeBlock.HALF, DoubleBlockHalf.UPPER), 3);
		ServerPlayer player = player(helper, c.offset(0, 0, -1), 0.0f, GameType.SURVIVAL); // facing the wardrobe
		HideManager.toggle(player, c, Direction.NORTH);
		// inside: turned round to look out through the doors, not visible to anyone
		check(Math.abs(net.minecraft.util.Mth.wrapDegrees(player.getYRot() - Direction.NORTH.toYRot())) < 1.0f,
				"hiding didn't turn the player to look out of the doors (yaw " + player.getYRot() + ")");
		double inFront = (c.getZ() + 0.5) - player.getZ();
		check(inFront > -0.35 && inFront < -0.15 && Math.abs(player.getX() - (c.getX() + 0.5)) < 0.01,
				"the hidden player isn't sitting inside, against the back wall (" + String.format("%.2f", inFront) + " in front of the middle)");
		check(player.isInvisible(), "a hidden player can be seen");
		BreathManager.setWantsToHold(player, true);
		BlindOneEntity monster = monster(level, c.offset(0, 0, -2));
		helper.startSequence()
				.thenIdle(150)
				.thenExecute(() -> {
					boolean alive = player.isAlive() && player.getHealth() >= player.getMaxHealth();
					boolean hidden = HideManager.isHidden(player);
					boolean hunted = monster.getPrey() == player;
					HideManager.release(player);
					boolean visibleAgain = !player.isInvisible();
					cleanUp(helper, c, player);
					check(visibleAgain, "the player stayed invisible after leaving the wardrobe");
					check(hidden, "the player was pulled out of the wardrobe");
					check(!hunted, "the monster is hunting a hidden player who holds the breath");
					check(alive, "a hidden player who holds the breath got hurt");
				})
				.thenSucceed();
	}

	/** A thrown bottle smashes and lures it to where it landed. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_05_bottle", timeoutTicks = 200)
	public void aThrownBottleLuresTheMonster(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos c = spot(4);
		arena(level, c);
		ServerPlayer watcher = player(helper, c.offset(-12, 0, 0), 0.0f, GameType.CREATIVE);
		BlindOneEntity monster = monster(level, c);
		ThrownBottleEntity bottle = new ThrownBottleEntity(ModRegistry.THROWN_BOTTLE, level);
		Vec3 target = Vec3.atBottomCenterOf(c.offset(12, 0, 0));
		bottle.setPos(target.x, target.y + 3.0, target.z);
		bottle.setDeltaMovement(0.0, -0.6, 0.0);
		level.addFreshEntity(bottle);
		helper.startSequence()
				.thenWaitUntil(() -> check(bottle.isRemoved(), "the bottle didn't smash"))
				.thenWaitUntil(() -> check(monster.getNoisePos() != null && monster.getNoisePos().distanceTo(target) < 3.0,
						"the monster didn't hear the bottle smash (heard: " + monster.getNoisePos() + ")"))
				.thenExecute(() -> cleanUp(helper, c, watcher))
				.thenSucceed();
	}

	/** Stepping on a creaky board is heard. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_06_creak", timeoutTicks = 200)
	public void aCreakyBoardIsHeard(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos c = spot(5);
		arena(level, c);
		BlockPos board = c.offset(8, -1, 0);
		level.setBlock(board, ModRegistry.CREAKY_FLOORBOARDS.defaultBlockState(), 3);
		ServerPlayer player = player(helper, board.above(), 0.0f, GameType.SURVIVAL);
		BlindOneEntity monster = monster(level, c);
		helper.startSequence()
				.thenIdle(20)
				.thenExecute(() -> check(monster.getNoisePos() == null, "the monster heard something before the board creaked"))
				.thenExecute(() -> ModRegistry.CREAKY_FLOORBOARDS.stepOn(level, board, level.getBlockState(board), player))
				.thenWaitUntil(() -> check(monster.getNoisePos() != null && monster.getNoisePos().distanceTo(Vec3.atCenterOf(board)) < 2.0,
						"the creak wasn't heard"))
				.thenExecute(() -> cleanUp(helper, c, player))
				.thenSucceed();
	}

	/** It never walks into a player's bedroom: put inside, it steps straight back out. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_07_saferoom", timeoutTicks = 200)
	public void theMonsterCantStayInTheBedroom(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos feet = spot(6);
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.getAbilities().invulnerable = true;
		forceHouse(level, feet, true);
		GameSession.start(level, feet, List.of(player));
		HouseBuilder.SafeRoom room = GameSession.current().layout().safeRooms().get(0);
		BlindOneEntity monster = monster(level, room.spawn().east());
		helper.startSequence()
				.thenWaitUntil(() -> check(!monster.getBoundingBox().intersects(room.box()), "the monster is still inside the bedroom"))
				.thenExecute(() -> cleanUp(helper, feet, player))
				.thenSucceed();
	}

	/** The lamps flicker when it comes close. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_08_lamps", timeoutTicks = 200)
	public void lampsFlickerNearTheMonster(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos feet = spot(7);
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.getAbilities().invulnerable = true;
		forceHouse(level, feet, true);
		GameSession.start(level, feet, List.of(player));
		BlockPos lamp = GameSession.current().layout().lamps().get(0);
		monster(level, lamp.below().north(2));
		helper.startSequence()
				.thenWaitUntil(() -> check(!level.getBlockState(lamp).getValue(com.lasttrain.block.LampBlock.LIT), "the lamp never flickered"))
				.thenExecute(() -> cleanUp(helper, feet, player))
				.thenSucceed();
	}

	// ------------------------------------------------------------------ the Watcher and the doll

	/** Looking straight at the Watcher makes it vanish (and jump at you). */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_09_watcher", timeoutTicks = 200)
	public void theWatcherVanishesWhenLookedAt(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos c = spot(8);
		arena(level, c);
		ServerPlayer player = player(helper, c, -90.0f, GameType.SURVIVAL); // looking towards +X
		WatcherEntity seen = ModRegistry.WATCHER.create(level);
		seen.moveTo(c.getX() + 10.5, c.getY(), c.getZ() + 0.5, 0.0f, 0.0f);
		level.addFreshEntity(seen);
		WatcherEntity behind = ModRegistry.WATCHER.create(level);
		behind.moveTo(c.getX() - 9.5, c.getY(), c.getZ() + 0.5, 0.0f, 0.0f);
		level.addFreshEntity(behind);
		helper.startSequence()
				.thenWaitUntil(() -> {
					Vec3 to = seen.position().add(0.0, seen.getBbHeight() * 0.8, 0.0).subtract(player.getEyePosition());
					check(seen.isRemoved(), "the Watcher in front of the player is still there (ticks " + seen.tickCount
							+ ", look dot " + player.getViewVector(1.0f).dot(to.normalize()) + ", line of sight " + player.hasLineOfSight(seen)
							+ ", yaw " + player.getYRot() + ", player " + player.position() + ", watcher " + seen.position() + ", head " + player.getYHeadRot() + ")");
				})
				.thenExecute(() -> {
					boolean stayed = !behind.isRemoved();
					cleanUp(helper, c, player);
					check(stayed, "the Watcher behind the player vanished although nobody looked at it");
				})
				.thenSucceed();
	}

	/** The doll moves when nobody is looking at it. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_10_doll", timeoutTicks = 2400)
	public void theDollMovesWhenNobodyLooks(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos c = spot(9);
		arena(level, c);
		ServerPlayer player = player(helper, c, 90.0f, GameType.SURVIVAL); // looking towards -X
		DollEntity doll = ModRegistry.DOLL.create(level);
		doll.moveTo(c.getX() + 6.5, c.getY(), c.getZ() + 0.5, 0.0f, 0.0f); // behind the player
		level.addFreshEntity(doll);
		Vec3 start = doll.position();
		helper.startSequence()
				.thenWaitUntil(() -> check(doll.position().distanceTo(start) > 0.5, "the doll never moved"))
				.thenExecute(() -> cleanUp(helper, c, player))
				.thenSucceed();
	}

	// ------------------------------------------------------------------ the good ending, items, the curse

	/** Burning the family photo needs all six notes; then the monster dies and the game ends. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_11_ending", timeoutTicks = 200)
	public void burningThePhotoWithAllNotesLiftsTheCurse(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos feet = spot(10);
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.getAbilities().invulnerable = true;
		forceHouse(level, feet, true);
		GameSession.start(level, feet, List.of(player));
		HouseBuilder.Layout layout = GameSession.current().layout();
		BlockPos fire = layout.origin();
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModRegistry.FAMILY_PHOTO)); // before the notes, or it replaces one
		for (int n = 1; n < NoteItem.COUNT; n++) {
			player.getInventory().add(NoteItem.create(ModRegistry.NOTE, n));
		}
		check(!GameSession.tryLiftCurse(player, fire), "the curse was lifted with only five notes");
		player.getInventory().add(NoteItem.create(ModRegistry.NOTE, NoteItem.COUNT));
		GameSession before = GameSession.current();
		String state = "session " + (before == null ? "none" : before.stage() + (before.level() == player.level() ? "" : " in another level"))
				+ ", notes " + player.getInventory().items.stream().filter(i -> i.is(ModRegistry.NOTE)).map(NoteItem::number).toList()
				+ ", in hand " + player.getMainHandItem();
		check(GameSession.tryLiftCurse(player, fire), "all six notes and the photo didn't lift the curse (" + state + ")");
		AABB house = new AABB(layout.houseMin(), layout.houseMax()).inflate(64.0);
		boolean monsterDead = level.getEntitiesOfClass(BlindOneEntity.class, house, BlindOneEntity::isAlive).isEmpty();
		boolean ended = GameSession.current() == null;
		CurseManager.reset(level.getServer());
		cleanUp(helper, feet, player);
		check(monsterDead, "the Blind One survived the photo burning");
		check(ended, "the game didn't end");
		helper.succeed();
	}

	/** The flashlight drains while on and switches itself off when empty. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_12_flashlight", timeoutTicks = 20)
	public void theFlashlightRunsOutOfBattery(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = survivor(helper);
		ItemStack light = new ItemStack(ModRegistry.FLASHLIGHT);
		light.getOrCreateTag().putBoolean("On", true);
		player.setItemInHand(InteractionHand.MAIN_HAND, light);
		int drains = 0;
		while (FlashlightItem.isOn(light) && drains < 100) {
			player.tickCount = drains * 200;
			light.inventoryTick(level, player, 0, true);
			drains++;
		}
		boolean empty = light.getDamageValue() >= light.getMaxDamage() - 1;
		boolean off = !FlashlightItem.isOn(light);
		server(helper).getPlayerList().remove(player);
		check(off, "the flashlight never switched off");
		check(empty, "the flashlight switched off with charge left: " + FlashlightItem.charge(light));
		check(drains > 5, "the battery ran out after only " + drains + " drains");
		helper.succeed();
	}

	/** The diary takes today's offering (3 bones on day one) and remembers it. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_13_offering", timeoutTicks = 200)
	public void theDiaryTakesTheOffering(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		CurseManager.reset(server);
		ServerPlayer player = survivor(helper);
		player.getInventory().add(new ItemStack(Items.BONE, 5));
		helper.startSequence()
				.thenWaitUntil(() -> check((CurseManager.data(server).tasks.getOrDefault(player.getUUID(), 0) & 1) != 0,
						"the diary didn't take the bones"))
				.thenExecute(() -> {
					int bones = player.getInventory().countItem(Items.BONE);
					CurseManager.reset(server);
					server.getPlayerList().remove(player);
					check(bones == 2, "the diary should take exactly 3 of 5 bones, " + bones + " left");
				})
				.thenSucceed();
	}

	/** The countdown: on the third night the curse strikes. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_14_third_night", timeoutTicks = 100)
	public void theCurseStrikesOnTheThirdNight(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		long before = level.getDayTime();
		CurseManager.reset(server);
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		CurseData data = CurseManager.data(server);
		long dayStart = data.startTime - data.startTime % 24000L;
		level.setDayTime(dayStart + 1 * 24000L + 15000L); // second night: nothing yet
		helper.startSequence()
				.thenIdle(3)
				.thenExecute(() -> check(!data.triggered, "the curse struck on the second night"))
				.thenExecute(() -> level.setDayTime(dayStart + 2 * 24000L + 13100L)) // third night
				.thenWaitUntil(() -> check(data.triggered, "the curse didn't strike on the third night"))
				.thenExecute(() -> {
					boolean dying = player.hasEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE);
					CurseManager.clearRuntime();
					CurseManager.reset(server);
					level.setDayTime(before);
					server.getPlayerList().remove(player);
					check(dying, "the player didn't get the death sequence");
				})
				.thenSucceed();
	}

	// ------------------------------------------------------------------ the Double

	private static DoppelgangerEntity double_(ServerLevel level, BlockPos at, ServerPlayer copied, ServerPlayer haunted) {
		DoppelgangerEntity double_ = ModRegistry.DOPPELGANGER.create(level);
		double_.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
		double_.imitate(copied, haunted);
		level.addFreshEntity(double_);
		return double_;
	}

	/** It stands still while you watch it and creeps up on you when you look away. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_16_double_follows", timeoutTicks = 600)
	public void theDoubleCreepsCloserWhenNotWatched(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos c = spot(13);
		arena(level, c);
		ServerPlayer player = player(helper, c, -90.0f, GameType.SURVIVAL); // looking at it (+X)
		DoppelgangerEntity double_ = double_(level, c.offset(12, 0, 0), player, player);
		Vec3[] start = new Vec3[1];
		helper.startSequence()
				.thenWaitUntil(() -> loadedIn(level, double_))
				.thenExecute(() -> start[0] = double_.position())
				.thenIdle(60)
				.thenExecute(() -> {
					check(double_.position().distanceTo(start[0]) < 0.5, "it moved while the player was looking at it ("
							+ String.format("%.1f", double_.position().distanceTo(start[0])) + " blocks)");
					check(!double_.isRemoved() && !double_.isRevealed(), "it gave itself away from 12 blocks");
					player.setYRot(90.0f); // turn away
					player.setYHeadRot(90.0f);
				})
				.thenWaitUntil(() -> check(double_.distanceTo(player) < 8.0f, "it didn't follow the player: still "
						+ (int) double_.distanceTo(player) + " blocks away"))
				.thenExecute(() -> {
					boolean hurt = player.getHealth() < player.getMaxHealth();
					cleanUp(helper, c, player);
					check(!hurt, "the Double hurt the player");
				})
				.thenSucceed();
	}

	/** Walk up to it and it shrieks, vanishes, and the Blind One comes to the noise. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_17_double_shrieks", timeoutTicks = 400)
	public void theDoubleShrieksAndTheMonsterHearsIt(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos c = spot(14);
		arena(level, c);
		ServerPlayer player = player(helper, c, -90.0f, GameType.SURVIVAL);
		player.getAbilities().invulnerable = true; // about the shriek, not about the monster
		DoppelgangerEntity double_ = double_(level, c.offset(8, 0, 0), player, player);
		BlindOneEntity monster = monster(level, c.offset(8, 0, -16)); // too far to hear the player, close enough for a shriek
		helper.startSequence()
				.thenWaitUntil(() -> {
					loadedIn(level, monster);
					loadedIn(level, double_);
				})
				.thenExecute(() -> {
					check(monster.getNoisePos() == null, "the monster already heard something before the shriek");
					player.teleportTo(level, c.getX() + 6.5, c.getY(), c.getZ() + 0.5, -90.0f, 0.0f); // two blocks from it
				})
				.thenWaitUntil(() -> check(double_.isRemoved(), "the Double didn't drop its act when the player walked up to it"))
				.thenExecute(() -> {
					Vec3 heard = monster.getNoisePos();
					cleanUp(helper, c, player);
					check(heard != null && heard.distanceTo(Vec3.atBottomCenterOf(c.offset(8, 0, 0))) < 3.0,
							"the monster didn't hear the shriek (heard " + heard + ")");
				})
				.thenSucceed();
	}

	/** In the house it turns up in the gallery, out of sight, wearing the friend's face (or yours, alone). */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_18_double_house", timeoutTicks = 200)
	public void theHouseSendsADoubleWithAFriendsFace(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos feet = spot(15);
		forceHouse(level, feet, true);
		ServerPlayer alice = survivor(helper);
		ServerPlayer bob = survivor(helper);
		GameSession.start(level, feet, List.of(alice, bob));
		GameSession session = GameSession.current();
		HouseBuilder.Layout layout = session.layout();
		BlockPos o = layout.origin();
		// alice walks out of her bedroom into the upstairs gallery, near the stairs
		alice.teleportTo(level, o.getX() + 30.5, o.getY() + HouseBuilder.STOREY + 1, o.getZ() + 12.5, 90.0f, 0.0f);
		alice.setYHeadRot(90.0f);
		helper.startSequence()
				.thenIdle(5)
				.thenExecute(() -> {
					check(DoppelgangerManager.spawnFor(level, layout, bob, List.of(alice, bob)) == null,
							"a Double came for a player who is still in their bedroom");
					DoppelgangerEntity double_ = DoppelgangerManager.spawnFor(level, layout, alice, List.of(alice, bob));
					check(double_ != null, "no Double came for a player in the gallery");
					double distance = double_.distanceTo(alice);
					check(distance >= DoppelgangerManager.MIN_DISTANCE - 0.5 && distance <= DoppelgangerManager.MAX_DISTANCE + 0.5,
							"it appeared " + (int) distance + " blocks away");
					check(Math.abs(double_.getY() - alice.getY()) < 0.5, "it appeared on another floor");
					check(bob.getUUID().equals(double_.copied().orElse(null)), "it doesn't wear the friend's face");
					check(double_.getCustomName() != null && double_.getCustomName().getString().equals(bob.getName().getString()),
							"it doesn't carry the friend's name");
					GameSession.stop(level.getServer());
					check(double_.isRemoved(), "the Double stayed after the game ended");
					cleanUp(helper, feet, alice, bob);
				})
				.thenSucceed();
	}

	/** A boarded-up door doesn't open, and tugging at it rattles the boards loud enough for the monster. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_21_boarded_door", timeoutTicks = 200)
	public void aBoardedDoorStaysShutAndRattles(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos c = spot(18);
		arena(level, c);
		BlockState boards = ModRegistry.BOARDED_DOOR.defaultBlockState();
		BlockPos door = c.offset(2, 0, 0);
		level.setBlock(door, boards.setValue(com.lasttrain.block.BoardedDoorBlock.HALF, DoubleBlockHalf.LOWER), 3);
		level.setBlock(door.above(), boards.setValue(com.lasttrain.block.BoardedDoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
		ServerPlayer player = player(helper, c, -90.0f, GameType.SURVIVAL);
		BlindOneEntity monster = monster(level, c.offset(2, 0, 5));
		helper.startSequence()
				.thenWaitUntil(() -> loadedIn(level, monster))
				.thenExecute(() -> {
					useOn(level, player, door, ItemStack.EMPTY);
					boolean shut = level.getBlockState(door).is(ModRegistry.BOARDED_DOOR) && level.getBlockState(door.above()).is(ModRegistry.BOARDED_DOOR);
					boolean solid = !level.getBlockState(door).getCollisionShape(level, door).isEmpty();
					Vec3 heard = monster.getNoisePos();
					cleanUp(helper, c, player);
					check(shut && solid, "the boarded-up door opened");
					check(heard != null && heard.distanceTo(Vec3.atCenterOf(door)) < 1.5, "the monster didn't hear the boards rattle (heard " + heard + ")");
				})
				.thenSucceed();
	}

	// ------------------------------------------------------------------ what the models do

	/** Watched, the doll never moves a hair. Turn away and it turns its back on you - all but its head. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_22_doll_head", timeoutTicks = 2400)
	public void theDollTurnsItsHeadRoundWhenYouLookAway(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos c = spot(19);
		arena(level, c);
		ServerPlayer player = player(helper, c, -90.0f, GameType.SURVIVAL); // looking at the doll (+X)
		DollEntity doll = ModRegistry.DOLL.create(level);
		doll.moveTo(c.getX() + 5.5, c.getY(), c.getZ() + 0.5, 90.0f, 0.0f);
		level.addFreshEntity(doll);
		helper.startSequence()
				.thenIdle(200)
				.thenExecute(() -> {
					check(!doll.isHeadTurned(), "the doll turned its head while the player was looking at it");
					player.setYRot(90.0f); // turn away
					player.setYHeadRot(90.0f);
				})
				.thenWaitUntil(() -> check(doll.isHeadTurned(), "the doll never turned its head round"))
				.thenExecute(() -> {
					Vec3 d = player.position().subtract(doll.position());
					float towardsPlayer = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
					float away = Math.abs(net.minecraft.util.Mth.wrapDegrees(doll.getYRot() - towardsPlayer));
					cleanUp(helper, c, player);
					check(away > 150.0f, "its body should face away from the player (it is turned " + (int) away + " degrees from them)");
				})
				.thenSucceed();
	}

	/** Caught at the edge of your sight, the Watcher leans its head over - it doesn't vanish unless you look straight at it. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_23_watcher_tilt", timeoutTicks = 300)
	public void theWatcherTiltsItsHeadWhenNoticed(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos c = spot(20);
		arena(level, c);
		ServerPlayer player = player(helper, c, -90.0f, GameType.SURVIVAL); // looking towards +X
		WatcherEntity aside = ModRegistry.WATCHER.create(level);
		aside.moveTo(c.getX() + 10.5, c.getY(), c.getZ() + 4.5, 0.0f, 0.0f); // about 22 degrees off to the side
		level.addFreshEntity(aside);
		WatcherEntity behind = ModRegistry.WATCHER.create(level);
		behind.moveTo(c.getX() - 9.5, c.getY(), c.getZ() + 0.5, 0.0f, 0.0f);
		level.addFreshEntity(behind);
		helper.startSequence()
				.thenWaitUntil(() -> check(aside.isNoticed(), "the Watcher at the edge of sight never noticed it was seen"))
				.thenIdle(20)
				.thenExecute(() -> {
					boolean stayed = !aside.isRemoved();
					boolean behindNoticed = behind.isNoticed();
					cleanUp(helper, c, player);
					check(stayed, "a Watcher seen only from the corner of the eye vanished");
					check(!behindNoticed, "the Watcher behind the player acted as if it was seen");
				})
				.thenSucceed();
	}

	/** Two ticking clocks (with their invisible tops) and chandeliers, some of them lit, in every house. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_24_furniture", timeoutTicks = 200)
	public void theHouseHasClocksAndChandeliers(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos feet = spot(21);
		forceHouse(level, feet, true);
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		GameSession.start(level, feet, List.of(player));
		HouseBuilder.Layout layout = GameSession.current().layout();
		List<BlockPos> clocks = new java.util.ArrayList<>();
		int chandeliers = 0;
		int lit = 0;
		for (BlockPos pos : BlockPos.betweenClosed(layout.houseMin(), layout.houseMax())) {
			BlockState state = level.getBlockState(pos);
			if (state.is(ModRegistry.GRANDFATHER_CLOCK)) {
				clocks.add(pos.immutable());
			} else if (state.is(ModRegistry.CHANDELIER)) {
				chandeliers++;
				if (state.getValue(com.lasttrain.block.ChandelierBlock.LIT)) {
					lit++;
				}
			}
		}
		check(clocks.size() == 2, "clocks in the house: " + clocks.size());
		for (BlockPos clock : clocks) {
			check(level.getBlockState(clock.above()).is(ModRegistry.CLOCK_TOP), "a clock has no top half");
			check(level.getBlockEntity(clock) instanceof com.lasttrain.block.GrandfatherClockBlockEntity, "a clock has no clockwork (block entity)");
		}
		check(chandeliers >= 8, "chandeliers in the house: " + chandeliers);
		check(lit >= 1 && lit < chandeliers, "lit chandeliers: " + lit + " of " + chandeliers);
		BlockPos first = clocks.get(0);
		level.destroyBlock(first, false);
		boolean topGone = level.getBlockState(first.above()).isAir();
		cleanUp(helper, feet, player);
		check(topGone, "breaking the clock left its invisible top standing");
		helper.succeed();
	}

	// ------------------------------------------------------------------ difficulty

	/** Easy / nightmare change the monster's temper, the supplies in the house and how long the train waits. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_25_difficulty", timeoutTicks = 200)
	public void difficultyChangesTheGame(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos feet = spot(22);
		forceHouse(level, feet, true);
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		com.lasttrain.config.LastTrainConfig config = com.lasttrain.config.LastTrainConfig.get();
		com.lasttrain.config.LastTrainConfig.Difficulty before = config.difficulty;
		Map<com.lasttrain.config.LastTrainConfig.Difficulty, int[]> seen = new HashMap<>();
		try {
			for (com.lasttrain.config.LastTrainConfig.Difficulty d : com.lasttrain.config.LastTrainConfig.Difficulty.values()) {
				config.difficulty = d;
				GameSession.start(level, feet, List.of(player));
				HouseBuilder.Layout layout = GameSession.current().layout();
				Map<Item, Integer> loot = loot(level, layout.origin(), new HashSet<>());
				seen.put(d, new int[]{GameSession.anger(level), loot.getOrDefault(ModRegistry.THROWABLE_BOTTLE, 0),
						loot.getOrDefault(ModRegistry.BATTERY, 0), config.effectiveTrainWaitSeconds()});
				GameSession.stop(level.getServer());
			}
		} finally {
			config.difficulty = before; // other tests share this config
		}
		cleanUp(helper, feet, player);
		int[] easy = seen.get(com.lasttrain.config.LastTrainConfig.Difficulty.EASY);
		int[] normal = seen.get(com.lasttrain.config.LastTrainConfig.Difficulty.NORMAL);
		int[] nightmare = seen.get(com.lasttrain.config.LastTrainConfig.Difficulty.NIGHTMARE);
		String report = "anger/bottles/batteries/train: easy " + java.util.Arrays.toString(easy) + ", normal "
				+ java.util.Arrays.toString(normal) + ", nightmare " + java.util.Arrays.toString(nightmare);
		check(easy[0] == 0 && normal[0] == 0 && nightmare[0] == 1, "the monster's starting anger is wrong: " + report);
		check(easy[1] > normal[1] && normal[1] > nightmare[1], "bottles don't follow the difficulty: " + report);
		check(easy[2] > normal[2] && normal[2] > nightmare[2], "batteries don't follow the difficulty: " + report);
		check(easy[3] > normal[3] && normal[3] > nightmare[3], "the train's wait doesn't follow the difficulty: " + report);
		helper.succeed();
	}

	// ------------------------------------------------------------------ outside

	/** A fenced yard with dead trees, a graveyard and a well - and nothing in the way to the train. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_26_grounds", timeoutTicks = 200)
	public void theYardIsFencedAndTheWayToTheTrainIsClear(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos feet = spot(23);
		forceHouse(level, feet, true);
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		GameSession.start(level, feet, List.of(player));
		HouseBuilder.Layout layout = GameSession.current().layout();
		BlockPos o = layout.origin();
		int fences = 0, joined = 0, gates = 0, trunks = 0, graves = 0, water = 0;
		for (BlockPos pos : BlockPos.betweenClosed(o.offset(HouseBuilder.YARD_MIN_X, -8, HouseBuilder.YARD_FRONT_Z),
				o.offset(HouseBuilder.YARD_MAX_X, 16, HouseBuilder.YARD_BACK_Z))) {
			BlockState state = level.getBlockState(pos);
			if (state.is(Blocks.DARK_OAK_FENCE) && pos.getY() == o.getY() + 1) {
				fences++;
				if (state.getValue(net.minecraft.world.level.block.FenceBlock.EAST) || state.getValue(net.minecraft.world.level.block.FenceBlock.WEST)
						|| state.getValue(net.minecraft.world.level.block.FenceBlock.NORTH) || state.getValue(net.minecraft.world.level.block.FenceBlock.SOUTH)) {
					joined++;
				}
			} else if (state.is(Blocks.DARK_OAK_FENCE_GATE) && state.getValue(net.minecraft.world.level.block.FenceGateBlock.OPEN)) {
				gates++;
			} else if (state.is(Blocks.DARK_OAK_LOG) && pos.getY() == o.getY() + 2
					&& state.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS) == Direction.Axis.Y
					&& level.getBlockState(pos.below()).is(Blocks.DARK_OAK_LOG)) {
				trunks++;
			} else if (state.is(ModRegistry.WOODEN_CROSS) || state.is(Blocks.STONE_BRICK_WALL) || state.is(Blocks.MOSSY_STONE_BRICK_WALL)) {
				graves++;
			} else if (state.is(Blocks.WATER)) {
				water++;
			}
		}
		String report = "fences " + fences + " (joined " + joined + "), open gates " + gates + ", tree trunks " + trunks
				+ ", graves " + graves + ", well water " + water;
		// the way out: from the front door down the path, through the gate, onto the platform
		boolean pathClear = true;
		for (int z = HouseBuilder.YARD_FRONT_Z; z <= -1; z++) {
			BlockPos cell = o.offset(HouseBuilder.DOOR_X, 1, z);
			if (!level.getBlockState(cell).getCollisionShape(level, cell).isEmpty()
					|| !level.getBlockState(cell.above()).getCollisionShape(level, cell.above()).isEmpty()) {
				pathClear = false;
				report += "; the path is blocked at z=" + z + " by " + level.getBlockState(cell).getBlock().getName().getString();
			}
		}
		boolean railwayClear = true;
		for (BlockPos pos : BlockPos.betweenClosed(o.offset(-40, 1, HouseBuilder.RAIL_Z - 3), o.offset(HouseBuilder.WIDTH + 40, 8, HouseBuilder.RAIL_Z + 5))) {
			BlockState state = level.getBlockState(pos);
			if (state.is(Blocks.DARK_OAK_LOG) || state.is(Blocks.DARK_OAK_FENCE) || state.is(Blocks.MOSSY_STONE_BRICKS)) {
				railwayClear = false;
				report += "; " + state.getBlock().getName().getString() + " on the railway at " + pos.subtract(o).toShortString();
				break;
			}
		}
		cleanUp(helper, feet, player);
		check(fences > 150 && joined > fences * 0.9, "the yard fence is missing or doesn't join up: " + report);
		check(gates == 2, "the gate isn't open: " + report);
		check(trunks >= 8, "too few dead trees: " + report);
		check(graves == 12, "the graveyard isn't there: " + report);
		check(water >= 3, "the well has no water: " + report);
		check(pathClear, "the way to the train is blocked: " + report);
		check(railwayClear, "something from the yard stands on the railway: " + report);
		helper.succeed();
	}

	/** With nothing to hear, it doesn't get stuck in one room: it wanders the house, room to room - never into a bedroom. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_27_patrol", timeoutTicks = 12000)
	public void theMonsterWandersFromRoomToRoom(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos feet = spot(24);
		forceHouse(level, feet, true);
		ServerPlayer player = helper.makeMockServerPlayerInLevel(); // creative: makes no noise
		GameSession.start(level, feet, List.of(player));
		HouseBuilder.Layout layout = GameSession.current().layout();
		AABB house = new AABB(layout.houseMin(), layout.houseMax()).inflate(2.0);
		List<BlindOneEntity> monsters = level.getEntitiesOfClass(BlindOneEntity.class, house);
		check(monsters.size() == 1, "monsters in the house: " + monsters.size());
		BlindOneEntity monster = monsters.get(0);
		BlockPos o = layout.origin();
		Set<BlockPos> rooms = new HashSet<>();
		Set<BlockPos> visited = new HashSet<>();
		boolean[] trespassed = {false};
		helper.startSequence()
				.thenWaitUntil(() -> {
					if (GameSession.safeRoomAt(level, monster.position()) != null) {
						trespassed[0] = true;
					}
					for (BlockPos point : layout.patrolPoints()) {
						if (point.distToCenterSqr(monster.position()) < 2.5 * 2.5) {
							visited.add(point);
							int z = point.getZ() - o.getZ();
							if (z < 10 || z > 14) {
								rooms.add(point);
							}
						}
					}
					if (rooms.size() >= 3 && visited.size() >= 5) {
						return;
					}
					StringBuilder around = new StringBuilder();
					BlockPos m = monster.blockPosition();
					for (int dz = -2; dz <= 2; dz++) {
						for (int dx = -1; dx <= 1; dx++) {
							for (int dy = 0; dy <= 1; dy++) {
								BlockState st = level.getBlockState(m.offset(dx, dy, dz));
								if (!st.isAir()) {
									around.append(m.offset(dx, dy, dz).subtract(o).toShortString()).append('=')
											.append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath())
											.append(st.hasProperty(DoorBlock.OPEN) ? (st.getValue(DoorBlock.OPEN) ? "(open)" : "(shut)") : "").append(' ');
								}
							}
						}
					}
					net.minecraft.world.level.pathfinder.Path path = monster.getNavigation().getPath();
					throw new net.minecraft.gametest.framework.GameTestAssertException("it only got round " + rooms.size() + " rooms and "
							+ visited.size() + " places of " + layout.patrolPoints().size() + " (now at " + monster.position().subtract(Vec3.atLowerCornerOf(o))
							+ ", path " + (path == null ? "none" : path.getNodeCount() + " nodes to " + path.getTarget().subtract(o).toShortString() + " done " + path.isDone())
							+ ", noise " + monster.getNoisePos() + ", prey " + monster.getPrey() + "; around: " + around + ")");
				})
				.thenExecute(() -> {
					cleanUp(helper, feet, player);
					check(!trespassed[0], "the monster walked into a player's bedroom");
				})
				.thenSucceed();
	}

	/** Turn your back on the doll and it walks up to you; it grabs your leg and laughs, and the Blind One hears it. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_28_doll_grab", timeoutTicks = 2400)
	public void theDollCreepsUpAndGrabsYourLeg(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos c = spot(25);
		arena(level, c);
		ServerPlayer player = player(helper, c, 90.0f, GameType.SURVIVAL); // back to the doll (looking towards -X)
		player.getAbilities().invulnerable = true;
		DollEntity doll = ModRegistry.DOLL.create(level);
		doll.moveTo(c.getX() + 7.5, c.getY(), c.getZ() + 0.5, 90.0f, 0.0f);
		level.addFreshEntity(doll);
		BlindOneEntity monster = monster(level, c.offset(0, 0, -15));
		boolean[] crept = {false};
		helper.startSequence()
				.thenWaitUntil(() -> loadedIn(level, monster))
				.thenWaitUntil(() -> {
					if (doll.isCreeping()) {
						crept[0] = true;
					}
					check(player.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN), "the doll never grabbed the player's leg");
				})
				.thenExecute(() -> {
					Vec3 heard = monster.getNoisePos();
					double gone = doll.distanceTo(player);
					cleanUp(helper, c, player);
					check(crept[0], "the doll got there without creeping up");
					check(heard != null && heard.distanceTo(player.position()) < 2.0, "the monster didn't hear the doll laugh (heard " + heard + ")");
					check(gone > 5.0, "the doll stayed at the player's feet after grabbing (" + (int) gone + " blocks away)");
				})
				.thenSucceed();
	}

	/** Creeping up on you, it stops dead the moment you look back - and doesn't move while you watch. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_29_doll_freeze", timeoutTicks = 2400)
	public void theDollFreezesWhenYouLookBack(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos c = spot(26);
		arena(level, c);
		ServerPlayer player = player(helper, c, 90.0f, GameType.SURVIVAL); // back to the doll
		DollEntity doll = ModRegistry.DOLL.create(level);
		doll.moveTo(c.getX() + 12.5, c.getY(), c.getZ() + 0.5, 90.0f, 0.0f);
		level.addFreshEntity(doll);
		Vec3[] frozenAt = new Vec3[1];
		helper.startSequence()
				.thenWaitUntil(() -> check(doll.isCreeping() && doll.distanceTo(player) < 10.0, "the doll isn't coming"))
				.thenExecute(() -> {
					// look back at it, wherever it is
					Vec3 d = doll.position().subtract(player.position());
					float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
					player.setYRot(yaw);
					player.setYHeadRot(yaw);
				})
				.thenIdle(3)
				.thenExecute(() -> frozenAt[0] = doll.position())
				.thenIdle(80)
				.thenExecute(() -> {
					double moved = doll.position().distanceTo(frozenAt[0]);
					boolean watched = doll.isWatched();
					cleanUp(helper, c, player);
					check(watched, "the doll doesn't know it is being watched (doll " + doll.position() + ", player " + player.position()
							+ " yaw " + player.getYRot() + "/" + player.getYHeadRot() + ", creeping " + doll.isCreeping()
							+ ", slowed " + player.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN) + ")");
					check(moved < 0.05, "the doll kept moving while watched (" + String.format("%.2f", moved) + " blocks)");
				})
				.thenSucceed();
	}

	// ------------------------------------------------------------------ its own mind

	/** It clicks, and the echo gives away a player standing still and silent close by. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_30_echo", timeoutTicks = 400)
	public void itsClicksFindAStillSilentPlayer(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos c = spot(27);
		arena(level, c);
		ServerPlayer player = player(helper, c.offset(3, 0, 0), 90.0f, GameType.SURVIVAL);
		player.getAbilities().invulnerable = true;
		BreathManager.setWantsToHold(player, true); // not a sound
		BlindOneEntity monster = monster(level, c.offset(0, 0, 0));
		monster.setNoAi(true); // it stays put and just clicks (clicking is not a goal: it goes on)
		helper.startSequence()
				.thenWaitUntil(() -> loadedIn(level, monster))
				.thenWaitUntil(() -> check(monster.getPrey() == player, "its clicks didn't find the player standing 3 blocks away"))
				.thenExecute(() -> cleanUp(helper, c, player))
				.thenSucceed();
	}

	/** Crouching, the echo barely catches you: at the same distance it clicks right past. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_31_echo_crouch", timeoutTicks = 400)
	public void crouchingHidesYouFromItsClicks(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos c = spot(28);
		arena(level, c);
		ServerPlayer player = player(helper, c.offset(3, 0, 0), 90.0f, GameType.SURVIVAL);
		player.getAbilities().invulnerable = true;
		player.setShiftKeyDown(true);
		BreathManager.setWantsToHold(player, true);
		BlindOneEntity monster = monster(level, c);
		monster.setNoAi(true); // stays put: only its clicks are tested here
		helper.startSequence()
				.thenWaitUntil(() -> loadedIn(level, monster))
				.thenIdle(170) // several clicks, still within one breath
				.thenExecute(() -> {
					boolean found = monster.getPrey() == player;
					BreathManager.setWantsToHold(player, false);
					cleanUp(helper, c, player);
					check(!found, "its clicks found a crouching player 3 blocks away");
				})
				.thenSucceed();
	}

	/** Lost you as you hid? It searches, taps the wardrobes - and if you breathe in there, it drags you out. */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_32_search", timeoutTicks = 1200)
	public void itSearchesTheWardrobesForSomeoneBreathing(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos c = spot(29);
		arena(level, c);
		BlockState wardrobe = ModRegistry.WARDROBE.defaultBlockState().setValue(WardrobeBlock.FACING, Direction.NORTH);
		level.setBlock(c, wardrobe.setValue(WardrobeBlock.HALF, DoubleBlockHalf.LOWER), 3);
		level.setBlock(c.above(), wardrobe.setValue(WardrobeBlock.HALF, DoubleBlockHalf.UPPER), 3);
		ServerPlayer player = player(helper, c.offset(0, 0, -1), 0.0f, GameType.SURVIVAL);
		player.getAbilities().invulnerable = true;
		BlindOneEntity monster = monster(level, c.offset(0, 0, -5));
		boolean[] searched = {false};
		helper.startSequence()
				.thenWaitUntil(() -> loadedIn(level, monster))
				.thenExecute(() -> {
					NoiseSystem.emit(level, player.position(), 8.0f, player); // it hears you...
					HideManager.toggle(player, c, Direction.NORTH);           // ...and you slip into the wardrobe
				})
				.thenWaitUntil(() -> {
					if (monster.getMood() == BlindOneEntity.MOOD_SEARCH) {
						searched[0] = true;
					}
					check(!HideManager.isHidden(player), "it never found the player breathing in the wardrobe (mood " + monster.getMood()
							+ ", searched " + searched[0] + ", at " + monster.position().subtract(Vec3.atLowerCornerOf(c)) + ", " + String.format("%.2f", monster.distanceTo(player))
							+ " from the player, noise " + monster.getNoisePos() + ", wardrobe taps " + monster.wardrobeTaps + ")");
				})
				.thenWaitUntil(() -> check(monster.getPrey() == player, "it dragged the player out but isn't after them"))
				.thenExecute(() -> {
					cleanUp(helper, c, player);
					check(searched[0], "it didn't search - it just knew");
				})
				.thenSucceed();
	}

	/** A room with the door shut: it opens the door and walks out (at 0.7 wide it used to catch on the open leaf). */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_33_door", timeoutTicks = 400)
	public void theMonsterWalksOutThroughAShutDoor(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos c = spot(30);
		arena(level, c);
		for (int x = -3; x <= 3; x++) {
			for (int z = -6; z <= 0; z++) {
				for (int y = 0; y <= 3; y++) {
					if (x == -3 || x == 3 || z == -6 || z == 0 || y == 3) {
						level.setBlock(c.offset(x, y, z), ModRegistry.WEATHERED_PLANKS.defaultBlockState(), 3);
					}
				}
			}
		}
		BlockState door = ModRegistry.OLD_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
		level.setBlock(c, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 3);
		level.setBlock(c.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
		BlindOneEntity monster = monster(level, c.offset(0, 0, -3));
		BlockPos outside = c.offset(0, 0, 6);
		helper.startSequence()
				.thenWaitUntil(() -> loadedIn(level, monster))
				.thenExecute(() -> monster.getNavigation().moveTo(outside.getX() + 0.5, outside.getY(), outside.getZ() + 0.5, 1.0))
				.thenWaitUntil(() -> check(monster.getZ() > c.getZ() + 1.0 || monster.getZ() < c.getZ() - 6.0
						|| Math.abs(monster.getX() - (c.getX() + 0.5)) > 3.5, "it is still in the room, at "
						+ monster.position().subtract(Vec3.atLowerCornerOf(c)) + " (door open " + level.getBlockState(c).getValue(DoorBlock.OPEN) + ")"))
				.thenExecute(() -> cleanUp(helper, c))
				.thenSucceed();
	}

	// ------------------------------------------------------------------ the secret room

	/**
	 * Every house hides a room behind a shelf with a red book sticking out. It holds note 4 (no good ending
	 * without it), batteries and bottles. You can't get in until you pull the book; then you can.
	 */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_20_secret_room", timeoutTicks = 200)
	public void theRedBookOpensTheSecretRoom(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos feet = spot(17);
		forceHouse(level, feet, true);
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		GameSession.start(level, feet, List.of(player));
		HouseBuilder.Layout layout = GameSession.current().layout();
		BlockPos o = layout.origin();
		BlockPos chest = layout.secretChest();
		check(chest != null, "the house has no secret room");
		List<BlockPos> shelves = BlockPos.betweenClosedStream(layout.houseMin(), layout.houseMax())
				.filter(pos -> level.getBlockState(pos).is(ModRegistry.SECRET_BOOKSHELF)).map(BlockPos::immutable).toList();
		check(shelves.size() == 1, "shelves with a red book: " + shelves.size());
		BlockPos shelf = shelves.get(0);
		check(level.getBlockState(shelf.above()).is(ModRegistry.OLD_BOOKSHELF), "the secret shelf has no shelf on top of it");

		Set<Integer> notes = new HashSet<>();
		int batteries = 0;
		int bottles = 0;
		check(level.getBlockEntity(chest) instanceof Container, "there is no chest in the secret room");
		Container container = (Container) level.getBlockEntity(chest);
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.getItem(i);
			if (stack.is(ModRegistry.NOTE)) {
				notes.add(NoteItem.number(stack));
			} else if (stack.is(ModRegistry.BATTERY)) {
				batteries += stack.getCount();
			} else if (stack.is(ModRegistry.THROWABLE_BOTTLE)) {
				bottles += stack.getCount();
			}
		}
		check(notes.contains(4), "note 4 isn't in the secret room: " + notes);
		check(batteries >= 2 && bottles >= 3, "the secret room's reward is missing: " + batteries + " batteries, " + bottles + " bottles");

		int y = chest.getY() - o.getY();
		List<BlockPos> starts = y == 1 ? List.of(o.offset(HouseBuilder.DOOR_X, 1, 1))
				: layout.safeRooms().stream().map(HouseBuilder.SafeRoom::spawn).toList();
		check(!canOpen(level, walk(level, o, y, starts, false), chest), "the hidden chest can be reached without opening the shelf");
		useOn(level, player, shelf, ItemStack.EMPTY);
		check(level.getBlockState(shelf).isAir() && level.getBlockState(shelf.above()).isAir(), "pulling the red book didn't move the shelf");
		check(canOpen(level, walk(level, o, y, starts, false), chest), "the shelf moved but the hidden room still can't be entered");
		cleanUp(helper, feet, player);
		helper.succeed();
	}

	// ------------------------------------------------------------------ the random map

	/**
	 * Builds several houses (each one different) and walks through them: from the front door and from the
	 * bedrooms, every cupboard, the stairs, both ladders and all bedrooms must be reachable on foot,
	 * despite the boarded-up doors and the caved-in gallery.
	 */
	@GameTest(template = EMPTY_STRUCTURE, batch = "lt_play_19_random_map", timeoutTicks = 200)
	public void everyRandomHouseCanBeWalkedThrough(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos feet = spot(16);
		forceHouse(level, feet, true);
		int sideDoors = 0;
		int boarded = 0;
		int rubble = 0;
		Set<String> maps = new HashSet<>();
		for (int build = 0; build < 20; build++) {
			HouseBuilder.Layout layout = HouseBuilder.build(level, feet, 2);
			BlockPos o = layout.origin();
			check(layout.secretChest() != null, "build " + build + ": no secret room");
			StringBuilder map = new StringBuilder();
			for (int y : new int[]{1, HouseBuilder.STOREY + 1}) {
				Set<Long> reached = walk(level, o, y, y == 1 ? List.of(o.offset(HouseBuilder.DOOR_X, 1, 1))
						: layout.safeRooms().stream().map(HouseBuilder.SafeRoom::spawn).toList(), true); // pulls every red book
				String floor = y == 1 ? "ground floor" : "upstairs";
				if (y == 1) {
					check(anyReached(reached, o, y, 29, 31, 23, 24), "build " + build + ": the stairs can't be reached from the front door");
					check(anyReached(reached, o, y, 58, 58, 12, 12), "build " + build + ": the basement ladder can't be reached");
				} else {
					check(anyReached(reached, o, y, 29, 30, 15, 17), "build " + build + ": the stairs can't be reached from the bedrooms");
					check(anyReached(reached, o, y, 2, 2, 12, 12), "build " + build + ": the attic ladder can't be reached");
					for (HouseBuilder.SafeRoom room : layout.safeRooms()) {
						check(reached.contains(room.spawn().asLong()), "build " + build + ": a bedroom is cut off");
					}
				}
				for (int x = 1; x < HouseBuilder.WIDTH; x++) {
					for (int z = 1; z < HouseBuilder.DEPTH; z++) {
						BlockPos pos = o.offset(x, y, z);
						BlockState state = level.getBlockState(pos);
						if ((state.getBlock() instanceof CabinetBlock || state.getBlock() instanceof ChestBlock)
								&& !canOpen(level, reached, pos)) {
							throw new GameTestAssertException("build " + build + ": a " + state.getBlock().getName().getString()
									+ " on the " + floor + " at (" + x + ", " + z + ") can't be reached\n" + sketch(level, o, y, reached, x, z));
						}
					}
				}
				for (int wx : new int[]{9, 18, 41, 50}) {
					for (int z = 1; z < HouseBuilder.DEPTH; z++) {
						if (level.getBlockState(o.offset(wx, y, z)).is(ModRegistry.OLD_DOOR)) {
							sideDoors++;
							map.append(y).append(':').append(wx).append(',').append(z).append(' ');
						}
					}
				}
				for (int x = 1; x < HouseBuilder.WIDTH; x++) {
					if (level.getBlockState(o.offset(x, y, 12)).is(ModRegistry.DAMP_STONE)
							|| level.getBlockState(o.offset(x, y + 1, 12)).is(ModRegistry.WEATHERED_BEAM)) {
						rubble++;
						break;
					}
				}
				for (int x = 1; x < HouseBuilder.WIDTH; x++) {
					for (int z : new int[]{10, 14}) {
						if (level.getBlockState(o.offset(x, y, z)).is(ModRegistry.BOARDED_DOOR)
								&& level.getBlockState(o.offset(x, y + 1, z)).is(ModRegistry.BOARDED_DOOR)) {
							boarded++;
						}
					}
				}
			}
			maps.add(map.toString());
		}
		level.getEntitiesOfClass(net.minecraft.world.entity.decoration.Painting.class, new AABB(feet).inflate(80, 30, 40)).forEach(Entity::discard);
		cleanUp(helper, feet);
		check(sideDoors > 0, "no house had a door between two rooms");
		check(rubble > 0, "no house had a caved-in gallery");
		check(boarded > 0, "no house had a boarded-up door");
		check(maps.size() > 1, "all the houses had the same doors: " + maps);
		helper.succeed();
	}

	/** Every cell a player can walk to on one floor of the house (no jumping), from {@code starts}. */
	private static Set<Long> walk(ServerLevel level, BlockPos o, int y, List<BlockPos> starts, boolean openSecrets) {
		Set<Long> seen = new HashSet<>();
		java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
		for (BlockPos start : starts) {
			if (seen.add(start.asLong())) {
				queue.add(start);
			}
		}
		while (!queue.isEmpty()) {
			BlockPos pos = queue.poll();
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos next = pos.relative(d);
				int lx = next.getX() - o.getX();
				int lz = next.getZ() - o.getZ();
				if (lx < 0 || lx > HouseBuilder.WIDTH || lz < 0 || lz > HouseBuilder.DEPTH || next.getY() != o.getY() + y) {
					continue;
				}
				boolean secret = openSecrets && level.getBlockState(next).is(ModRegistry.SECRET_BOOKSHELF);
				if (!seen.contains(next.asLong()) && (secret || passable(level, next, true) && passable(level, next.above(), false))) {
					seen.add(next.asLong());
					queue.add(next);
				}
			}
		}
		return seen;
	}

	private static boolean passable(ServerLevel level, BlockPos pos, boolean feet) {
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof DoorBlock) {
			return !(state.getBlock() instanceof LockedDoorBlock);
		}
		net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(level, pos);
		return shape.isEmpty() || feet && shape.max(Direction.Axis.Y) <= 0.5;
	}

	/**
	 * A player can open it from a cell next to it, or from two blocks away when what stands in between is
	 * low (a crate, a table) and nothing is on top of it: the arm reaches over.
	 */
	/** The ray test needs some entity (it is never added to the world). */
	private static final java.util.function.Function<ServerLevel, Entity> RAY_OWNER = level -> net.minecraft.world.entity.EntityType.MARKER.create(level);

	private static boolean canOpen(ServerLevel level, Set<Long> reached, BlockPos container) {
		Vec3[] targets = {Vec3.atCenterOf(container), Vec3.atCenterOf(container).add(0.0, 0.4, 0.0)};
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				BlockPos from = container.offset(dx, 0, dz);
				if (!reached.contains(from.asLong())) {
					continue;
				}
				if (Math.max(Math.abs(dx), Math.abs(dz)) <= 1) {
					return true;
				}
				// as the game does it: a ray from the eyes that hits this very block, within arm's reach
				Vec3 eye = Vec3.atBottomCenterOf(from).add(0.0, 1.62, 0.0);
				for (Vec3 target : targets) {
					if (eye.distanceTo(target) > 4.5) {
						continue;
					}
					BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(eye, target,
							net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, RAY_OWNER.apply(level)));
					if (hit.getBlockPos().equals(container)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** The floor around (cx, cz): # wall, D door, C container, . walked, _ free but never reached, o other things. */
	private static String sketch(ServerLevel level, BlockPos o, int y, Set<Long> reached, int cx, int cz) {
		StringBuilder out = new StringBuilder();
		for (int z = Math.max(0, cz - 12); z <= Math.min(HouseBuilder.DEPTH, cz + 12); z++) {
			out.append(String.format("%2d ", z));
			for (int x = Math.max(0, cx - 12); x <= Math.min(HouseBuilder.WIDTH, cx + 12); x++) {
				BlockPos pos = o.offset(x, y, z);
				BlockState state = level.getBlockState(pos);
				char c;
				if (x == cx && z == cz) {
					c = '@';
				} else if (reached.contains(pos.asLong())) {
					c = '.';
				} else if (state.getBlock() instanceof DoorBlock) {
					c = 'D';
				} else if (state.getBlock() instanceof CabinetBlock || state.getBlock() instanceof ChestBlock) {
					c = 'C';
				} else if (passable(level, pos, true) && passable(level, pos.above(), false)) {
					c = '_';
				} else if (level.getBlockState(pos.above(2)).isSolidRender(level, pos.above(2))) {
					c = '#';
				} else {
					c = 'o';
				}
				out.append(c);
			}
			out.append('\n');
		}
		return out.toString();
	}

	private static boolean anyReached(Set<Long> reached, BlockPos o, int y, int x1, int x2, int z1, int z2) {
		for (int x = x1; x <= x2; x++) {
			for (int z = z1; z <= z2; z++) {
				if (reached.contains(o.offset(x, y, z).asLong())) {
					return true;
				}
			}
		}
		return false;
	}

	private static MinecraftServer server(GameTestHelper helper) {
		return helper.getLevel().getServer();
	}
}
