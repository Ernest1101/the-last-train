package com.lasttrain.game;

import com.lasttrain.entity.WatcherEntity;
import com.lasttrain.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Now and then puts a {@link WatcherEntity} somewhere around each player: in the overworld while the
 * curse counts down (more often each day), and inside the nightmare house.
 */
public final class WatcherManager {
	private static final Map<UUID, Integer> TIMERS = new HashMap<>();
	private static final Map<UUID, UUID> ACTIVE = new HashMap<>();

	private WatcherManager() {
	}

	public static void tick(MinecraftServer server) {
		CurseData data = CurseManager.data(server);
		boolean cursedOverworld = data.startTime >= 0 && !data.triggered && !data.finished;
		int day = CurseManager.currentDay(server);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.isSpectator() || player.isCreative() || !player.isAlive()) {
				continue;
			}
			boolean inNightmare = player.level().dimension() == ModRegistry.NIGHTMARE;
			if (!inNightmare && !(cursedOverworld && player.level() == server.overworld())) {
				continue;
			}
			UUID active = ACTIVE.get(player.getUUID());
			if (active != null && player.serverLevel().getEntity(active) != null) {
				continue;
			}
			ACTIVE.remove(player.getUUID());
			int timer = TIMERS.getOrDefault(player.getUUID(), 20 * 60 * 2) - 1;
			if (timer > 0) {
				TIMERS.put(player.getUUID(), timer);
				continue;
			}
			RandomSource random = player.getRandom();
			int base = inNightmare ? 20 * 60 * 2 : day <= 1 ? 20 * 60 * 6 : day == 2 ? 20 * 60 * 4 : 20 * 60 * 2;
			TIMERS.put(player.getUUID(), base + random.nextInt(base));
			WatcherEntity watcher = spawnNear(player, inNightmare);
			if (watcher != null) {
				ACTIVE.put(player.getUUID(), watcher.getUUID());
			}
		}
	}

	private static WatcherEntity spawnNear(ServerPlayer player, boolean indoors) {
		ServerLevel level = player.serverLevel();
		RandomSource random = player.getRandom();
		for (int attempt = 0; attempt < 30; attempt++) {
			double angle = random.nextDouble() * Math.PI * 2.0;
			double distance = indoors ? 7.0 + random.nextDouble() * 12.0 : 16.0 + random.nextDouble() * 16.0;
			int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
			int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
			BlockPos pos = indoors ? findFloor(level, x, player.getBlockY(), z)
					: new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
			if (pos == null || !level.getBlockState(pos.below()).isSolid()
					|| !level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()) {
				continue;
			}
			if (indoors && GameSession.isSafe(level, pos)) {
				continue;
			}
			WatcherEntity watcher = ModRegistry.WATCHER.create(level);
			if (watcher == null) {
				return null;
			}
			watcher.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0f, 0.0f);
			level.addFreshEntity(watcher);
			return watcher;
		}
		return null;
	}

	private static BlockPos findFloor(ServerLevel level, int x, int y, int z) {
		for (int dy = 2; dy >= -2; dy--) {
			BlockPos pos = new BlockPos(x, y + dy, z);
			if (level.getBlockState(pos.below()).isSolid() && level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()) {
				return pos;
			}
		}
		return null;
	}

	public static void reset() {
		TIMERS.clear();
		ACTIVE.clear();
	}
}
