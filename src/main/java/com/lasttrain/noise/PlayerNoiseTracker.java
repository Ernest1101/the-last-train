package com.lasttrain.noise;

import com.lasttrain.game.HideManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns player movement into noise: footsteps (sneak / walk / sprint) and landings.
 * Server-side player velocity is unreliable, so movement is measured from position deltas.
 */
public final class PlayerNoiseTracker {
	private static final Map<UUID, Track> TRACKS = new HashMap<>();

	private static final class Track {
		Vec3 last;
		boolean onGround = true;
		double peakY;
		int stepTimer;
	}

	private PlayerNoiseTracker() {
	}

	public static void tick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.isCreative() || player.isSpectator() || !player.isAlive()) {
				TRACKS.remove(player.getUUID());
				continue;
			}
			Track track = TRACKS.computeIfAbsent(player.getUUID(), uuid -> new Track());
			Vec3 pos = player.position();
			if (track.last == null) {
				track.last = pos;
			}
			double dx = pos.x - track.last.x;
			double dz = pos.z - track.last.z;
			double horizontal = Math.sqrt(dx * dx + dz * dz);
			track.last = pos;
			if (player.isPassenger() || HideManager.isHidden(player)) {
				continue;
			}

			boolean onGround = player.onGround();
			if (!onGround) {
				track.peakY = track.onGround ? pos.y : Math.max(track.peakY, pos.y);
			} else if (!track.onGround) {
				double drop = track.peakY - pos.y;
				if (drop > 0.5) {
					NoiseSystem.emit(player.serverLevel(), pos, (float) Math.min(20.0, 5.0 + drop * 3.0), player);
				}
			}
			track.onGround = onGround;

			// horizontal > 2 means a teleport, not a step
			if (onGround && horizontal > 0.02 && horizontal < 2.0 && --track.stepTimer <= 0) {
				float radius;
				if (player.isShiftKeyDown()) {
					radius = 1.5f;
					track.stepTimer = 12;
				} else if (player.isSprinting() || horizontal > 0.24) {
					radius = 14.0f;
					track.stepTimer = 6;
				} else {
					radius = 7.0f;
					track.stepTimer = 9;
				}
				NoiseSystem.emit(player.serverLevel(), pos, radius, player);
			}
		}
	}

	public static void reset() {
		TRACKS.clear();
	}
}
