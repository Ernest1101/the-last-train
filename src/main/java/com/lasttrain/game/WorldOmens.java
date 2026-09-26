package com.lasttrain.game;

import com.lasttrain.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The overworld changes as the curse goes on: from day two grave crosses with candles appear
 * somewhere behind you; on the last day the animals walk up to you and stare.
 * (Faceless villagers are done on the client, see VillagerRendererMixin.)
 */
public final class WorldOmens {
	private static final Map<UUID, Integer> CROSS_TIMERS = new HashMap<>();

	private WorldOmens() {
	}

	public static void tick(MinecraftServer server) {
		CurseData data = CurseManager.data(server);
		if (data.startTime < 0 || data.triggered || data.finished) {
			return;
		}
		int day = CurseManager.currentDay(server);
		ServerLevel overworld = server.overworld();
		for (ServerPlayer player : overworld.players()) {
			if (player.isSpectator()) {
				continue;
			}
			if (day >= 2) {
				int timer = CROSS_TIMERS.getOrDefault(player.getUUID(), 20 * 60) - 1;
				if (timer <= 0) {
					timer = 20 * 60 * 3 + player.getRandom().nextInt(20 * 60 * 3);
					placeCross(overworld, player);
				}
				CROSS_TIMERS.put(player.getUUID(), timer);
			}
			if (day >= CurseManager.days() && server.getTickCount() % 20 == 0) {
				gatherAnimals(overworld, player);
			}
		}
	}

	private static void placeCross(ServerLevel level, ServerPlayer player) {
		RandomSource random = player.getRandom();
		for (int attempt = 0; attempt < 12; attempt++) {
			// behind the player, so it's just there when they turn around
			double yaw = Math.toRadians(player.getYRot() + 180.0f + (random.nextFloat() - 0.5f) * 90.0f);
			double distance = 18.0 + random.nextDouble() * 18.0;
			int x = (int) Math.floor(player.getX() - Math.sin(yaw) * distance);
			int z = (int) Math.floor(player.getZ() + Math.cos(yaw) * distance);
			BlockPos pos = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
			if (!level.getBlockState(pos.below()).is(BlockTags.DIRT) || !level.getBlockState(pos).isAir()
					|| !level.getBlockState(pos.above()).isAir()) {
				continue;
			}
			level.setBlock(pos, ModRegistry.WOODEN_CROSS.defaultBlockState(), 3);
			BlockPos candle = pos.relative(net.minecraft.core.Direction.Plane.HORIZONTAL.getRandomDirection(random));
			if (level.getBlockState(candle).isAir() && level.getBlockState(candle.below()).isSolid()) {
				level.setBlock(candle, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 1 + random.nextInt(3))
						.setValue(CandleBlock.LIT, true), 3);
			}
			return;
		}
	}

	private static void gatherAnimals(ServerLevel level, ServerPlayer player) {
		for (Animal animal : level.getEntitiesOfClass(Animal.class, player.getBoundingBox().inflate(24.0))) {
			double distance = animal.distanceTo(player);
			if (distance > 4.0) {
				animal.getNavigation().moveTo(player, 1.0);
			} else {
				animal.getNavigation().stop();
			}
			Vec3 eye = player.getEyePosition();
			animal.getLookControl().setLookAt(eye.x, eye.y, eye.z, 360.0f, 360.0f);
		}
	}

	public static void reset() {
		CROSS_TIMERS.clear();
	}
}
