package com.lasttrain.game;

import com.lasttrain.entity.BlindOneEntity;
import com.lasttrain.entity.TrainEntity;
import com.lasttrain.network.ModNetwork;
import com.lasttrain.noise.NoiseSystem;
import com.lasttrain.registry.ModRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One run of the game: wake up in the house, find the key, get out, reach the platform
 * and get on the last train before it leaves.
 */
public final class GameSession {
	public enum Stage { IN_HOUSE, ESCAPED, TRAIN, FINISHED }

	public static final int TRAIN_DELAY_TICKS = 25 * 20;

	@Nullable
	private static GameSession current;

	private final ServerLevel level;
	private final HouseBuilder.Layout layout;
	private final Set<UUID> players = new LinkedHashSet<>();
	private Stage stage = Stage.IN_HOUSE;
	private int timer;
	/** The three locks on the front door: padlock (key), boards (crowbar), electric lock (power). */
	private boolean keyUnlocked;
	private boolean boardsRemoved;
	private boolean powerRestored;
	/** Player deaths so far; every death makes the Blind One faster and sharper-eared. */
	private int deaths;
	/** Nightmare difficulty starts the monster off angry. */
	private final int startAnger = com.lasttrain.config.LastTrainConfig.get().difficulty().startAnger;
	private final Set<UUID> cracked = new java.util.HashSet<>();
	private final DoppelgangerManager doubles = new DoppelgangerManager();

	private GameSession(ServerLevel level, HouseBuilder.Layout layout) {
		this.level = level;
		this.layout = layout;
	}

	@Nullable
	public static GameSession current() {
		return current;
	}

	public ServerLevel level() {
		return this.level;
	}

	public Stage stage() {
		return this.stage;
	}

	public HouseBuilder.Layout layout() {
		return this.layout;
	}

	/** The players' bedroom that contains {@code pos}, if any. The Blind One may not enter those. */
	@Nullable
	public static HouseBuilder.SafeRoom safeRoomAt(Level level, Vec3 pos) {
		if (current == null || current.level != level) {
			return null;
		}
		for (HouseBuilder.SafeRoom room : current.layout.safeRooms()) {
			if (room.box().contains(pos)) {
				return room;
			}
		}
		return null;
	}

	/** The players' bedroom that {@code box} touches, if any. */
	@Nullable
	public static HouseBuilder.SafeRoom safeRoomTouching(Level level, AABB box) {
		if (current == null || current.level != level) {
			return null;
		}
		for (HouseBuilder.SafeRoom room : current.layout.safeRooms()) {
			if (room.box().intersects(box)) {
				return room;
			}
		}
		return null;
	}

	/** 0..4, how angry the Blind One is in this level (grows with every player death). */
	public static int anger(Level level) {
		return current != null && current.level == level ? Math.min(current.deaths + current.startAnger, 4) : 0;
	}

	/** Next to one of the players' beds, for the doll. */
	@Nullable
	public static BlockPos randomBedside(Level level, net.minecraft.util.RandomSource random) {
		if (current == null || current.level != level || current.layout.safeRooms().isEmpty()) {
			return null;
		}
		List<HouseBuilder.SafeRoom> rooms = current.layout.safeRooms();
		return rooms.get(random.nextInt(rooms.size())).spawn().east();
	}

	/** Where the Blind One wanders between rooms when it hears nothing (empty without a game here). */
	public static List<BlockPos> patrolPoints(Level level) {
		return current != null && current.level == level ? current.layout.patrolPoints() : List.of();
	}

	/** The foot and the head of the stairs: the only places it may set off for the other floor. */
	public static List<BlockPos> stairLandings(Level level) {
		return current != null && current.level == level ? current.layout.stairLandings() : List.of();
	}

	@Nullable
	public static String keyRoom() {
		return current != null ? current.layout.keyRoom() : null;
	}

	public static boolean isSafe(Level level, BlockPos pos) {
		return safeRoomAt(level, Vec3.atBottomCenterOf(pos)) != null;
	}

	/** {@code /lasttrain start}: build the house right here for the player and anyone nearby. */
	public static void start(ServerPlayer starter) {
		List<ServerPlayer> participants = new ArrayList<>();
		participants.add(starter);
		for (ServerPlayer other : starter.serverLevel().players()) {
			if (other != starter && other.distanceTo(starter) < 48.0f && !other.isSpectator()) {
				participants.add(other);
			}
		}
		start(starter.serverLevel(), starter.blockPosition(), participants);
	}

	public static void start(ServerLevel level, BlockPos feet, List<ServerPlayer> participants) {
		if (current != null) {
			stop(level.getServer());
		}
		HouseBuilder.Layout layout = HouseBuilder.build(level, feet, participants.size());
		// 3. a rebuilt house must not keep the monsters of the previous game
		AABB area = new AABB(layout.houseMin(), layout.houseMax()).inflate(48.0, 16.0, 48.0);
		level.getEntitiesOfClass(BlindOneEntity.class, area).forEach(net.minecraft.world.entity.Entity::discard);
		level.getEntitiesOfClass(com.lasttrain.entity.DollEntity.class, area).forEach(net.minecraft.world.entity.Entity::discard);
		level.getEntitiesOfClass(com.lasttrain.entity.WatcherEntity.class, area).forEach(net.minecraft.world.entity.Entity::discard);
		level.getEntitiesOfClass(com.lasttrain.entity.DoppelgangerEntity.class, area).forEach(net.minecraft.world.entity.Entity::discard);
		level.getEntitiesOfClass(TrainEntity.class, area.inflate(200.0, 0.0, 0.0)).forEach(net.minecraft.world.entity.Entity::discard);
		GameSession session = new GameSession(level, layout);
		current = session;

		if (level.dimension() == Level.OVERWORLD) {
			// the nightmare dimension is always night on its own
			MinecraftServer server = level.getServer();
			level.setDayTime(level.getDayTime() - level.getDayTime() % 24000L + 18000L);
			level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
			level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
			level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
		}

		BlindOneEntity monster = ModRegistry.BLIND_ONE.create(level);
		if (monster != null) {
			Vec3 m = Vec3.atBottomCenterOf(layout.monsterSpawn());
			monster.moveTo(m.x, m.y, m.z, 180.0f, 0.0f);
			monster.finalizeSpawn(level, level.getCurrentDifficultyAt(layout.monsterSpawn()), MobSpawnType.COMMAND, null, null);
			level.addFreshEntity(monster);
		}

		com.lasttrain.entity.DollEntity doll = ModRegistry.DOLL.create(level);
		if (doll != null) {
			Vec3 d = Vec3.atBottomCenterOf(layout.dollSpawn());
			doll.moveTo(d.x, d.y, d.z, level.random.nextFloat() * 360.0f, 0.0f);
			level.addFreshEntity(doll);
		}

		for (ServerPlayer player : participants) {
			session.join(player);
		}
	}

	/** Puts a player into this game: into their own bedroom, in adventure mode. */
	public void join(ServerPlayer player) {
		this.players.add(player.getUUID());
		List<HouseBuilder.SafeRoom> rooms = this.layout.safeRooms();
		int index = new ArrayList<>(this.players).indexOf(player.getUUID());
		BlockPos bed = rooms.get(Math.max(0, index) % rooms.size()).spawn();
		Vec3 spawn = Vec3.atBottomCenterOf(bed);
		player.teleportTo(this.level, spawn.x, spawn.y, spawn.z, 0.0f, 0.0f);
		player.setGameMode(GameType.ADVENTURE);
		player.setHealth(player.getMaxHealth());
		player.getFoodData().setFoodLevel(20);
		player.setRespawnPosition(this.level.dimension(), bed, 0.0f, true, false);
		title(player, Component.translatable("title.lasttrain.intro").withStyle(ChatFormatting.DARK_RED),
				Component.translatable("subtitle.lasttrain.intro"));
		player.sendSystemMessage(Component.translatable("message.lasttrain.intro").withStyle(ChatFormatting.GRAY));
	}

	public static void stop(MinecraftServer server) {
		if (current == null) {
			return;
		}
		for (ServerPlayer player : current.onlinePlayers()) {
			player.setGameMode(GameType.SURVIVAL);
		}
		AABB house = new AABB(current.layout.houseMin(), current.layout.houseMax()).inflate(48.0, 16.0, 48.0);
		current.level.getEntitiesOfClass(com.lasttrain.entity.DoppelgangerEntity.class, house).forEach(net.minecraft.world.entity.Entity::discard);
		if (current.level.dimension() == Level.OVERWORLD) {
			current.level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true, server);
			current.level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(true, server);
			current.level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(true, server);
		}
		current = null;
	}

	public static void reset() {
		current = null;
	}

	public static void tick(MinecraftServer server) {
		if (current != null) {
			current.tickSession();
		}
	}

	private void tickSession() {
		if (this.level.getGameTime() % 3 == 0) {
			this.tickLamps();
		}
		switch (this.stage) {
			case IN_HOUSE -> {
				this.doubles.tick(this, this.onlinePlayers());
				BlockState door = this.level.getBlockState(this.layout.exitDoor());
				boolean doorOpen = door.is(ModRegistry.LOCKED_DOOR) && door.getValue(DoorBlock.OPEN);
				if (!doorOpen) {
					return;
				}
				for (ServerPlayer player : this.onlinePlayers()) {
					if (player.isAlive() && player.level() == this.level && !this.layout.isInsideHouse(player.position())) {
						this.escape();
						return;
					}
				}
			}
			case ESCAPED -> {
				this.timer--;
				if (this.timer % 20 == 0 && this.timer > 0) {
					for (ServerPlayer player : this.onlinePlayers()) {
						player.displayClientMessage(Component.translatable("message.lasttrain.train_in", this.timer / 20)
								.withStyle(ChatFormatting.GOLD), true);
					}
				}
				if (this.timer <= 0) {
					this.spawnTrain();
				}
			}
			default -> {
			}
		}
	}

	/** Lamps near the Blind One flicker; the others burn steadily (basement ones only with power). */
	private void tickLamps() {
		List<BlindOneEntity> monsters = this.level.getEntitiesOfClass(BlindOneEntity.class,
				new AABB(this.layout.houseMin(), this.layout.houseMax()).inflate(16.0));
		for (BlockPos lamp : this.layout.lamps()) {
			setLamp(lamp, !isNearMonster(lamp, monsters) || this.level.random.nextFloat() < 0.55f);
		}
		for (BlockPos lamp : this.layout.basementLamps()) {
			setLamp(lamp, this.powerRestored && (!isNearMonster(lamp, monsters) || this.level.random.nextFloat() < 0.55f));
		}
	}

	private static boolean isNearMonster(BlockPos lamp, List<BlindOneEntity> monsters) {
		for (BlindOneEntity monster : monsters) {
			if (monster.blockPosition().distSqr(lamp) < 10 * 10) {
				return true;
			}
		}
		return false;
	}

	private void setLamp(BlockPos pos, boolean lit) {
		BlockState state = this.level.getBlockState(pos);
		if (state.is(ModRegistry.STATION_LAMP) && state.getValue(com.lasttrain.block.LampBlock.LIT) != lit) {
			this.level.setBlock(pos, state.setValue(com.lasttrain.block.LampBlock.LIT, lit), 3);
		}
	}

	// ------------------------------------------------------------------ the front door

	public enum UnlockResult { NOT_A_TOOL, ALREADY_DONE, PROGRESS, OPENED }

	/** A player used {@code tool} on the front door. */
	public static UnlockResult tryUnlock(ServerPlayer player, net.minecraft.world.item.ItemStack tool) {
		GameSession session = current;
		if (session == null || session.level != player.level()) {
			return tool.is(ModRegistry.HOUSE_KEY) ? UnlockResult.OPENED : UnlockResult.NOT_A_TOOL;
		}
		if (tool.is(ModRegistry.HOUSE_KEY)) {
			if (session.keyUnlocked) {
				return UnlockResult.ALREADY_DONE;
			}
			session.keyUnlocked = true;
		} else if (tool.is(ModRegistry.CROWBAR)) {
			if (session.boardsRemoved) {
				return UnlockResult.ALREADY_DONE;
			}
			session.boardsRemoved = true;
			session.level.playSound(null, session.layout.exitDoor(), SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.BLOCKS, 1.0f, 0.8f);
		} else {
			session.reportLocks(player);
			return UnlockResult.NOT_A_TOOL;
		}
		if (session.allUnlocked()) {
			return UnlockResult.OPENED;
		}
		session.reportLocks(player);
		return UnlockResult.PROGRESS;
	}

	private boolean allUnlocked() {
		return this.keyUnlocked && this.boardsRemoved && this.powerRestored;
	}

	private int locksOpen() {
		return (this.keyUnlocked ? 1 : 0) + (this.boardsRemoved ? 1 : 0) + (this.powerRestored ? 1 : 0);
	}

	private void reportLocks(ServerPlayer player) {
		Component status = Component.translatable("message.lasttrain.locks", this.locksOpen(),
				Component.translatable(this.keyUnlocked ? "lock.lasttrain.done" : "lock.lasttrain.key"),
				Component.translatable(this.boardsRemoved ? "lock.lasttrain.done" : "lock.lasttrain.boards"),
				Component.translatable(this.powerRestored ? "lock.lasttrain.done" : "lock.lasttrain.power"));
		player.displayClientMessage(status.copy().withStyle(ChatFormatting.GOLD), false);
	}

	/** The fuse went into the basement fuse box. */
	public static void onPowerRestored(ServerLevel level, net.minecraft.world.entity.player.Player player) {
		GameSession session = current;
		if (session == null || session.level != level || session.powerRestored) {
			return;
		}
		session.powerRestored = true;
		for (ServerPlayer p : session.onlinePlayers()) {
			p.displayClientMessage(Component.translatable("message.lasttrain.power_restored").withStyle(ChatFormatting.YELLOW), true);
		}
		if (player instanceof ServerPlayer serverPlayer) {
			session.reportLocks(serverPlayer);
		}
		if (session.allUnlocked()) {
			session.openFrontDoor();
		}
	}

	private void openFrontDoor() {
		BlockState door = this.level.getBlockState(this.layout.exitDoor());
		if (door.getBlock() instanceof DoorBlock doorBlock) {
			doorBlock.setOpen(null, this.level, door, this.layout.exitDoor(), true);
		}
		NoiseSystem.emit(this.level, Vec3.atCenterOf(this.layout.exitDoor()), 14.0f, null);
	}

	// ------------------------------------------------------------------ deaths, the good ending

	/** A player died in this game: their lens cracks and the monster gets angrier. */
	public static void onPlayerDeath(ServerPlayer player) {
		GameSession session = current;
		if (session == null || session.level != player.level() || !session.players.contains(player.getUUID())) {
			return;
		}
		session.deaths++;
		ModNetwork.sendRewind(player); // the bodycam rewinds the tape once they are back in bed
		if (session.cracked.add(player.getUUID())) {
			ModNetwork.sendLensCrack(player, true);
		}
		if (session.deaths <= 4) {
			for (ServerPlayer p : session.onlinePlayers()) {
				title(p, Component.translatable("title.lasttrain.angrier").withStyle(ChatFormatting.DARK_RED),
						Component.translatable("subtitle.lasttrain.angrier", session.deaths));
			}
		}
	}

	/**
	 * Burning the family photo in a fireplace with all the notes read lifts the curse for good:
	 * the Blind One dies, the house goes quiet and everyone wakes up at home.
	 */
	public static boolean tryLiftCurse(ServerPlayer player, BlockPos fire) {
		GameSession session = current;
		if (session == null || session.level != player.level() || session.stage == Stage.FINISHED) {
			return false;
		}
		java.util.Set<Integer> notes = new java.util.HashSet<>();
		for (net.minecraft.world.item.ItemStack stack : player.getInventory().items) {
			if (stack.is(ModRegistry.NOTE)) {
				notes.add(com.lasttrain.item.NoteItem.number(stack));
			}
		}
		notes.remove(0);
		if (notes.size() < com.lasttrain.item.NoteItem.COUNT) {
			player.displayClientMessage(Component.translatable("message.lasttrain.need_notes", notes.size(),
					com.lasttrain.item.NoteItem.COUNT).withStyle(ChatFormatting.GRAY), false);
			return false;
		}
		player.getMainHandItem().shrink(1);
		session.level.playSound(null, fire, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 1.5f, 0.6f);
		session.level.playSound(null, fire, com.lasttrain.registry.ModSounds.BLIND_SCREAM, SoundSource.HOSTILE, 3.0f, 0.5f);
		AABB house = new AABB(session.layout.houseMin(), session.layout.houseMax()).inflate(64.0);
		for (BlindOneEntity monster : session.level.getEntitiesOfClass(BlindOneEntity.class, house)) {
			monster.kill();
		}
		session.level.getEntitiesOfClass(com.lasttrain.entity.DollEntity.class, house).forEach(net.minecraft.world.entity.Entity::discard);
		session.level.getEntitiesOfClass(com.lasttrain.entity.WatcherEntity.class, house).forEach(net.minecraft.world.entity.Entity::discard);
		session.stage = Stage.FINISHED;
		List<ServerPlayer> everyone = session.onlinePlayers();
		for (ServerPlayer p : everyone) {
			session.cutscene(p, ModNetwork.ENDING_FREED, -1);
			ModNetwork.sendEnding(p, ModNetwork.ENDING_FREED);
		}
		CurseManager.onCurseLifted(player.getServer());
		stop(player.getServer());
		for (ServerPlayer p : everyone) {
			CurseManager.onGameOver(p);
		}
		return true;
	}

	private void escape() {
		this.stage = Stage.ESCAPED;
		this.timer = TRAIN_DELAY_TICKS;
		// the monster heard the door and knows you're outside
		NoiseSystem.emit(this.level, Vec3.atCenterOf(this.layout.exitDoor()), 40.0f, null);
		for (ServerPlayer player : this.onlinePlayers()) {
			title(player, Component.translatable("title.lasttrain.escaped").withStyle(ChatFormatting.GOLD),
					Component.translatable("subtitle.lasttrain.escaped", TRAIN_DELAY_TICKS / 20));
		}
	}

	private void spawnTrain() {
		this.stage = Stage.TRAIN;
		TrainEntity train = ModRegistry.TRAIN.create(this.level);
		if (train == null) {
			return;
		}
		Vec3 start = this.layout.trainStart();
		train.moveTo(start.x, start.y, start.z, -90.0f, 0.0f);
		train.setStopX(this.layout.trainStopX());
		this.level.addFreshEntity(train);
		for (ServerPlayer player : this.onlinePlayers()) {
			title(player, Component.translatable("title.lasttrain.train").withStyle(ChatFormatting.YELLOW),
					Component.translatable("subtitle.lasttrain.train"));
			player.playNotifySound(SoundEvents.BELL_BLOCK, SoundSource.AMBIENT, 1.0f, 0.5f);
		}
	}

	public static void onBoarded(ServerPlayer player, TrainEntity train) {
		player.displayClientMessage(Component.translatable("message.lasttrain.boarded").withStyle(ChatFormatting.GREEN), true);
		if (current != null && current.level == player.level()) {
			current.cutscene(player, ModNetwork.ENDING_ESCAPED, train.getId());
		}
	}

	/** The doors have closed: the Blind One comes out of the house and follows the train onto the platform. */
	public static void onTrainDeparting(TrainEntity train) {
		GameSession session = current;
		if (session == null || session.level != train.level() || train.getPassengers().isEmpty()) {
			return;
		}
		AABB house = new AABB(session.layout.houseMin(), session.layout.houseMax()).inflate(64.0);
		Vec3 door = session.doorInside();
		session.level.getEntitiesOfClass(BlindOneEntity.class, house).stream()
				.filter(monster -> !monster.isDeadOrDying())
				.min(java.util.Comparator.comparingDouble(monster -> monster.distanceToSqr(door)))
				.ifPresent(monster -> monster.beginFinale(session.platformSpot(), door, train));
	}

	private Vec3 doorInside() {
		BlockPos door = this.layout.exitDoor();
		return new Vec3(door.getX() + 0.5, door.getY(), door.getZ() + 1.5);
	}

	private Vec3 platformSpot() {
		BlockPos o = this.layout.origin();
		return new Vec3(o.getX() + HouseBuilder.DOOR_X + 0.5, o.getY() + 2.0, o.getZ() + HouseBuilder.RAIL_Z + 3.5);
	}

	private void cutscene(ServerPlayer player, int kind, int trainId) {
		BlockPos door = this.layout.exitDoor();
		Vec3 house = Vec3.atCenterOf(this.layout.houseMin()).add(Vec3.atCenterOf(this.layout.houseMax())).scale(0.5);
		ModNetwork.sendCutscene(player, kind, trainId, new Vec3(door.getX() + 0.5, door.getY(), door.getZ() + 0.5),
				this.platformSpot(), house);
	}

	/** Called once the train is 40 blocks past the platform. */
	public static void onTrainLeft(TrainEntity train, List<ServerPlayer> riders) {
		List<ServerPlayer> everyone = new ArrayList<>(riders);
		for (ServerPlayer rider : riders) {
			ModNetwork.sendEnding(rider, ModNetwork.ENDING_ESCAPED);
		}
		if (current != null) {
			for (ServerPlayer player : current.onlinePlayers()) {
				if (!riders.contains(player)) {
					current.cutscene(player, ModNetwork.ENDING_MISSED, train.getId());
					ModNetwork.sendEnding(player, ModNetwork.ENDING_MISSED);
					everyone.add(player);
				}
			}
			current.stage = Stage.FINISHED;
			stop(current.level.getServer());
		}
		for (ServerPlayer player : everyone) {
			CurseManager.onGameOver(player);
		}
	}

	List<ServerPlayer> onlinePlayers() {
		List<ServerPlayer> result = new ArrayList<>();
		for (UUID uuid : this.players) {
			ServerPlayer player = this.level.getServer().getPlayerList().getPlayer(uuid);
			if (player != null) {
				result.add(player);
			}
		}
		return result;
	}

	static void title(ServerPlayer player, Component title, Component subtitle) {
		player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
		player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
		player.connection.send(new ClientboundSetTitleTextPacket(title));
	}
}
