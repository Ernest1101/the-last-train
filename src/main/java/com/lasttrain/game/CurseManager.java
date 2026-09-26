package com.lasttrain.game;

import com.lasttrain.LastTrain;
import com.lasttrain.network.ModNetwork;
import com.lasttrain.registry.ModRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The three-day curse. A normal world, but it gets worse every day: sounds behind you, then screamers.
 * On the third night every player "dies" and wakes up in the house in the nightmare dimension.
 * Catching the last train (or missing it) brings them back home with their things.
 */
public final class CurseManager {
	/** The diary's task for each day: bring it these items (bones, string, gold). */
	private record Task(net.minecraft.world.item.Item item, int count) {
	}

	private static final Task[] TASKS = {
			new Task(net.minecraft.world.item.Items.BONE, 3),
			new Task(net.minecraft.world.item.Items.STRING, 4),
			new Task(net.minecraft.world.item.Items.GOLD_INGOT, 1),
	};

	public static int days() {
		return com.lasttrain.config.LastTrainConfig.get().curseDays;
	}
	private static final int NIGHT_START = 13000;
	static final int DEATH_TICKS = 160;
	static final int RETURN_DELAY = 300;
	/** Where the house is built in the nightmare dimension (surface of the flat world). */
	private static final BlockPos NIGHTMARE_HOUSE = new BlockPos(0, 64, 0);

	/** id, volume, pitch - played somewhere behind the player. */
	private static final Object[][] SOUNDS = {
			{"minecraft:ambient.cave", 1.0f, 1.0f},
			{"minecraft:ambient.cave", 1.0f, 0.7f},
			{"minecraft:entity.warden.nearby_closer", 0.8f, 0.8f},
			{"minecraft:entity.warden.ambient", 0.7f, 0.6f},
			{"minecraft:block.sculk_shrieker.shriek", 0.5f, 0.6f},
			{"minecraft:entity.zombie.attack_wooden_door", 0.6f, 0.5f},
			{"minecraft:block.wooden_door.open", 0.8f, 0.6f},
			{"minecraft:entity.wolf.howl", 0.6f, 0.5f},
			{"minecraft:entity.ghast.scream", 0.4f, 0.4f},
			{"minecraft:entity.enderman.stare", 0.5f, 0.5f},
			{"minecraft:ambient.soul_sand_valley.mood", 1.0f, 0.8f},
			{"minecraft:entity.warden.heartbeat", 1.0f, 0.8f},
			{"lasttrain:footsteps", 1.0f, 1.0f},
			{"lasttrain:footsteps", 1.0f, 1.0f},
			{"lasttrain:ambient.whisper", 0.8f, 0.9f},
	};

	/** Sounds of the house itself: floorboards, doors, something upstairs. */
	private static final Object[][] HOUSE_SOUNDS = {
			{"lasttrain:footsteps", 1.0f, 1.0f},
			{"lasttrain:footsteps", 1.0f, 1.0f},
			{"minecraft:block.wooden_door.close", 0.9f, 0.6f},
			{"minecraft:block.wooden_door.open", 0.9f, 0.5f},
			{"minecraft:entity.zombie.attack_wooden_door", 0.7f, 0.6f},
			{"minecraft:block.chest.close", 0.8f, 0.6f},
			{"minecraft:entity.vex.ambient", 0.7f, 0.5f},
			{"minecraft:entity.allay.ambient_without_item", 0.6f, 0.4f},
			{"minecraft:block.bell.use", 0.4f, 0.4f},
			{"minecraft:entity.enderman.ambient", 0.5f, 0.4f},
			{"minecraft:ambient.cave", 1.0f, 0.8f},
			{"minecraft:block.wood.break", 0.7f, 0.5f},
			{"lasttrain:ambient.whisper", 0.9f, 1.0f},
			{"lasttrain:ambient.whisper", 0.9f, 0.8f},
			{"lasttrain:ambient.clock", 0.8f, 1.0f},
			{"lasttrain:ambient.lullaby", 0.5f, 1.0f},
	};

	private static final Map<UUID, Timers> TIMERS = new HashMap<>();
	private static final Map<UUID, Integer> PENDING_RETURNS = new HashMap<>();
	private static int deathCountdown = -1;

	private static final class Timers {
		int alive;
		int nextSound = 600;
		int nextScare = 2400;
	}

	private CurseManager() {
	}

	public static CurseData data(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(CurseData::load, CurseData::new, CurseData.NAME);
	}

	/** Day of the curse, 1-based. */
	public static int currentDay(MinecraftServer server) {
		CurseData data = data(server);
		if (data.startTime < 0) {
			return 1;
		}
		long day = server.overworld().getDayTime() / 24000L - data.startTime / 24000L;
		return (int) Math.max(1, day + 1);
	}

	public static void tick(MinecraftServer server) {
		CurseData data = data(server);
		ServerLevel overworld = server.overworld();
		if (data.startTime < 0) {
			if (server.getPlayerList().getPlayerCount() == 0) {
				return;
			}
			data.startTime = overworld.getDayTime();
			data.setDirty();
		}
		tickReturns(server);
		tickHouse(server);

		if (deathCountdown >= 0) {
			if (--deathCountdown <= 0) {
				deathCountdown = -1;
				transferAll(server);
			}
			return;
		}
		if (data.triggered || data.finished) {
			return;
		}

		int day = currentDay(server);
		long timeOfDay = overworld.getDayTime() % 24000L;
		boolean night = timeOfDay >= NIGHT_START && timeOfDay < 23000;
		if (server.getTickCount() % 40 == 0) {
			for (ServerPlayer player : overworld.players()) {
				ModNetwork.sendCurseDay(player, Math.min(day, days()), days(), night, data.tasks.getOrDefault(player.getUUID(), 0));
				checkTask(data, player, Math.min(day, days()));
			}
		}
		if (day > days() || (day == days() && timeOfDay >= NIGHT_START)) {
			trigger(server);
			return;
		}
		RandomSource random = overworld.getRandom();
		for (ServerPlayer player : overworld.players()) {
			if (player.isSpectator()) {
				continue;
			}
			Timers t = TIMERS.computeIfAbsent(player.getUUID(), uuid -> new Timers());
			t.alive++;
			double frequency = com.lasttrain.config.LastTrainConfig.get().scareFrequency;
			if (frequency <= 0.0) {
				continue;
			}
			float speed = (float) ((night ? 2.0 : 1.0) * frequency);
			if (--t.nextSound <= 0) {
				int[] range = day == 1 ? new int[]{1400, 2600} : day == 2 ? new int[]{800, 1800} : new int[]{400, 1000};
				t.nextSound = (int) ((range[0] + random.nextInt(range[1] - range[0])) / speed);
				playBehind(player, random);
			}
			if (day >= 2 && --t.nextScare <= 0) {
				int[] range = day == 2 ? new int[]{6000, 12000} : new int[]{2400, 5000};
				t.nextScare = (int) ((range[0] + random.nextInt(range[1] - range[0])) / speed);
				if (t.alive > 2400) {
					ModNetwork.sendScreamer(player, random.nextInt(2));
				}
			}
		}
		if (server.getTickCount() % 200 == 0) {
			TIMERS.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
		}
	}

	/** Creepy noises and screamers while exploring the house in the nightmare dimension. */
	private static void tickHouse(MinecraftServer server) {
		ServerLevel nightmare = server.getLevel(ModRegistry.NIGHTMARE);
		if (nightmare == null) {
			return;
		}
		RandomSource random = nightmare.getRandom();
		for (ServerPlayer player : nightmare.players()) {
			if (player.isSpectator()) {
				continue;
			}
			Timers t = TIMERS.computeIfAbsent(player.getUUID(), uuid -> new Timers());
			t.alive++;
			double frequency = com.lasttrain.config.LastTrainConfig.get().scareFrequency;
			if (frequency <= 0.0) {
				continue;
			}
			if (--t.nextSound <= 0) {
				t.nextSound = (int) ((300 + random.nextInt(700)) / frequency);
				playBehind(player, random, HOUSE_SOUNDS);
			}
			if (--t.nextScare <= 0) {
				t.nextScare = (int) ((2400 + random.nextInt(3600)) / frequency);
				if (t.alive > 1200 && GameSession.safeRoomTouching(nightmare, player.getBoundingBox()) == null) {
					ModNetwork.sendScreamer(player, random.nextInt(2));
				}
			}
		}
	}

	private static void playBehind(ServerPlayer player, RandomSource random) {
		playBehind(player, random, SOUNDS);
	}

	private static void playBehind(ServerPlayer player, RandomSource random, Object[][] sounds) {
		Object[] sound = sounds[random.nextInt(sounds.length)];
		double yaw = Math.toRadians(player.getYRot() + 180.0f + (random.nextFloat() - 0.5f) * 120.0f);
		double distance = 5.0 + random.nextDouble() * 8.0;
		Vec3 offset = new Vec3(-Math.sin(yaw) * distance, random.nextDouble() * 2.0 - 0.5, Math.cos(yaw) * distance);
		ModNetwork.sendAmbient(player, new ResourceLocation((String) sound[0]), offset, (float) sound[1], (float) sound[2]);
	}

	/** Third night: everyone in the overworld "dies". */
	public static void trigger(MinecraftServer server) {
		CurseData data = data(server);
		data.triggered = true;
		data.setDirty();
		deathCountdown = DEATH_TICKS;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.isSpectator() || player.level().dimension() == ModRegistry.NIGHTMARE) {
				continue;
			}
			ModNetwork.sendCurseDeath(player);
			player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, DEATH_TICKS + 40, 4, false, false));
			player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, DEATH_TICKS + 40, 3, false, false));
		}
	}

	private static void transferAll(MinecraftServer server) {
		ServerLevel nightmare = server.getLevel(ModRegistry.NIGHTMARE);
		if (nightmare == null) {
			LastTrain.LOGGER.error("Nightmare dimension is missing, the curse can't continue");
			return;
		}
		// everyone who isn't already there - the Nether and the End don't save you
		List<ServerPlayer> victims = new ArrayList<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.isSpectator() && player.level().dimension() != ModRegistry.NIGHTMARE) {
				victims.add(player);
			}
		}
		if (victims.isEmpty()) {
			return;
		}
		for (ServerPlayer player : victims) {
			stash(server, player);
			player.removeAllEffects();
		}
		GameSession.start(nightmare, NIGHTMARE_HOUSE, victims);
		CurseData data = data(server);
		for (ServerPlayer player : victims) {
			int bits = data.tasks.getOrDefault(player.getUUID(), 0);
			if ((bits & 1) != 0) {
				player.getInventory().add(new ItemStack(ModRegistry.FLASHLIGHT));
			}
			if ((bits & 2) != 0) {
				player.getInventory().add(new ItemStack(ModRegistry.THROWABLE_BOTTLE, 3));
			}
			if ((bits & 4) != 0 && GameSession.keyRoom() != null) {
				player.sendSystemMessage(Component.translatable("message.lasttrain.key_hint",
						Component.translatable(GameSession.keyRoom())).withStyle(ChatFormatting.DARK_PURPLE));
			}
		}
	}

	/** The diary takes its offering for today as soon as the player carries it. */
	private static void checkTask(CurseData data, ServerPlayer player, int day) {
		int index = Math.min(day, TASKS.length) - 1;
		int bits = data.tasks.getOrDefault(player.getUUID(), 0);
		Task task = TASKS[index];
		if ((bits & (1 << index)) != 0 || player.getInventory().countItem(task.item()) < task.count()) {
			return;
		}
		player.getInventory().clearOrCountMatchingItems(stack -> stack.is(task.item()), task.count(), player.inventoryMenu.getCraftSlots());
		data.tasks.put(player.getUUID(), bits | (1 << index));
		data.setDirty();
		player.playNotifySound(net.minecraft.sounds.SoundEvents.SOUL_ESCAPE, net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 0.6f);
		GameSession.title(player, Component.translatable("title.lasttrain.offering").withStyle(ChatFormatting.DARK_RED),
				Component.translatable("subtitle.lasttrain.offering." + (index + 1)));
	}

	/** Called when the family photo burned: the curse is over for good. */
	public static void onCurseLifted(MinecraftServer server) {
		CurseData data = data(server);
		data.lifted = true;
		data.finished = true;
		data.setDirty();
	}

	static void stash(MinecraftServer server, ServerPlayer player) {
		CurseData data = data(server);
		if (data.stash.containsKey(player.getUUID())) {
			return;
		}
		CompoundTag tag = new CompoundTag();
		tag.put("Inventory", player.getInventory().save(new ListTag()));
		tag.putInt("XpLevel", player.experienceLevel);
		tag.putFloat("XpProgress", player.experienceProgress);
		BlockPos respawn = player.getRespawnPosition();
		if (respawn != null) {
			tag.putLong("RespawnPos", respawn.asLong());
			tag.putString("RespawnDim", player.getRespawnDimension().location().toString());
			tag.putFloat("RespawnAngle", player.getRespawnAngle());
			tag.putBoolean("RespawnForced", player.isRespawnForced());
		}
		data.stash.put(player.getUUID(), tag);
		data.setDirty();
		player.getInventory().clearContent();
	}

	/** Called when the train has left, for every player of the game. */
	public static void onGameOver(ServerPlayer player) {
		if (data(player.getServer()).stash.containsKey(player.getUUID())) {
			PENDING_RETURNS.put(player.getUUID(), RETURN_DELAY);
		}
	}

	private static void tickReturns(MinecraftServer server) {
		Iterator<Map.Entry<UUID, Integer>> it = PENDING_RETURNS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Integer> entry = it.next();
			if (entry.getValue() > 1) {
				entry.setValue(entry.getValue() - 1);
				continue;
			}
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player != null && !player.isAlive()) {
				continue; // on the death screen: wait for the respawn, or the items would be lost with the body
			}
			it.remove();
			if (player != null) {
				returnHome(player);
			}
		}
	}

	private static void returnHome(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		CurseData data = data(server);
		CompoundTag tag = data.stash.remove(player.getUUID());
		data.finished = true;
		data.setDirty();
		ServerLevel overworld = server.overworld();

		player.setGameMode(GameType.SURVIVAL);
		if (tag != null) {
			player.getInventory().load(tag.getList("Inventory", Tag.TAG_COMPOUND));
			player.experienceLevel = tag.getInt("XpLevel");
			player.experienceProgress = tag.getFloat("XpProgress");
		}

		Vec3 target = null;
		if (tag != null && tag.contains("RespawnPos")) {
			BlockPos pos = BlockPos.of(tag.getLong("RespawnPos"));
			ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(tag.getString("RespawnDim")));
			float angle = tag.getFloat("RespawnAngle");
			boolean forced = tag.getBoolean("RespawnForced");
			player.setRespawnPosition(dim, pos, angle, forced, false);
			if (dim == Level.OVERWORLD) {
				Optional<Vec3> spot = Player.findRespawnPositionAndUseSpawnBlock(overworld, pos, angle, forced, true);
				target = spot.orElse(null);
			}
		} else {
			player.setRespawnPosition(Level.OVERWORLD, null, 0.0f, false, false);
		}
		if (target == null) {
			BlockPos spawn = overworld.getSharedSpawnPos();
			int y = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ());
			target = new Vec3(spawn.getX() + 0.5, y, spawn.getZ() + 0.5);
		}
		player.teleportTo(overworld, target.x, target.y, target.z, player.getYRot(), 0.0f);
		player.setHealth(player.getMaxHealth());
		GameSession.title(player, Component.translatable("title.lasttrain.woke_up").withStyle(ChatFormatting.GRAY),
				Component.translatable("subtitle.lasttrain.woke_up"));
	}

	/** Players who log in while the curse is running get pulled into it. */
	public static void onJoin(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		CurseData data = data(server);
		if (!data.triggered && !data.finished && !data.lifted && data.diaryGiven.add(player.getUUID())) {
			giveDiary(player);
			data.setDirty();
		}
		ServerLevel nightmare = server.getLevel(ModRegistry.NIGHTMARE);
		if (nightmare == null) {
			return;
		}
		boolean inNightmare = player.level().dimension() == ModRegistry.NIGHTMARE;
		boolean cursed = data.triggered && !data.finished && deathCountdown < 0;
		if (!inNightmare && !cursed) {
			return;
		}
		if (!inNightmare) {
			stash(server, player);
		}
		GameSession session = GameSession.current();
		if (session != null && session.level() == nightmare) {
			session.join(player);
		} else {
			GameSession.start(nightmare, NIGHTMARE_HOUSE, List.of(player));
		}
	}

	/** The red book "You have 3 days", right into the player's hand. */
	private static void giveDiary(ServerPlayer player) {
		ItemStack diary = new ItemStack(ModRegistry.CURSED_DIARY);
		if (player.getMainHandItem().isEmpty()) {
			player.setItemInHand(InteractionHand.MAIN_HAND, diary);
		} else if (!player.getInventory().add(diary)) {
			player.drop(diary, false);
		}
		GameSession.title(player, Component.translatable("title.lasttrain.three_days").withStyle(ChatFormatting.DARK_RED),
				Component.translatable("subtitle.lasttrain.three_days"));
	}

	public static void reset(MinecraftServer server) {
		CurseData data = data(server);
		data.startTime = server.overworld().getDayTime();
		data.triggered = false;
		data.finished = false;
		data.lifted = false;
		data.diaryGiven.clear();
		data.tasks.clear();
		data.setDirty();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			onJoin(player);
		}
		TIMERS.clear();
		deathCountdown = -1;
	}

	public static void clearRuntime() {
		TIMERS.clear();
		PENDING_RETURNS.clear();
		deathCountdown = -1;
	}
}
