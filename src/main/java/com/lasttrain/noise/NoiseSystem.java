package com.lasttrain.noise;

import com.lasttrain.config.LastTrainConfig;
import com.lasttrain.entity.BlindOneEntity;
import com.lasttrain.game.GameSession;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Sound propagation for the blind monster. Every noise has a radius in blocks; any Blind One inside
 * it hears the noise with a strength that falls off linearly with distance.
 */
public final class NoiseSystem {
	private NoiseSystem() {
	}

	public static void emit(ServerLevel level, Vec3 pos, float radius, @Nullable Entity source) {
		if (radius <= 0.0f) {
			return;
		}
		// sharper hearing from the config and with every player death
		radius *= (float) (LastTrainConfig.get().effectiveMonsterHearing() * (1.0 + 0.15 * GameSession.anger(level)));
		if (source instanceof Player player && (player.isCreative() || player.isSpectator())) {
			return;
		}
		AABB area = new AABB(pos, pos).inflate(radius);
		for (BlindOneEntity monster : level.getEntitiesOfClass(BlindOneEntity.class, area)) {
			double distance = monster.position().distanceTo(pos);
			if (distance <= radius) {
				monster.hear(pos, (float) (1.0 - distance / radius), source);
			}
		}
	}
}
