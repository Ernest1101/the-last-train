package com.lasttrain.game;

import com.lasttrain.network.ModNetwork;
import com.lasttrain.noise.NoiseSystem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Breath holding. While the key is held the player makes no breathing noise, but the air runs out.
 * When it does, the player gasps loudly and has to recover before holding again.
 * Normal breathing is a quiet noise; panting after holding (or when out of air) is louder.
 */
public final class BreathManager {
	public static final int MAX_BREATH = 200;
	private static final int RECOVER_THRESHOLD = MAX_BREATH * 2 / 5;
	private static final Map<UUID, State> STATES = new HashMap<>();

	private static final class State {
		int breath = MAX_BREATH;
		boolean wantsToHold;
		boolean holding;
		boolean exhausted;
		int noiseTimer;
		int sentBreath = -1;
		boolean sentHolding;
		boolean sentExhausted;
	}

	private BreathManager() {
	}

	public static void setWantsToHold(ServerPlayer player, boolean hold) {
		STATES.computeIfAbsent(player.getUUID(), uuid -> new State()).wantsToHold = hold;
	}

	/** Holding the breath right now (not just pressing the key while out of air). */
	public static boolean isHolding(net.minecraft.world.entity.player.Player player) {
		State s = STATES.get(player.getUUID());
		return s != null && s.holding;
	}

	public static void tick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			State s = STATES.computeIfAbsent(player.getUUID(), uuid -> new State());
			s.holding = s.wantsToHold && !s.exhausted && player.isAlive();

			if (s.holding) {
				player.setSprinting(false);
				if (--s.breath <= 0) {
					s.breath = 0;
					s.holding = false;
					s.exhausted = true;
					gasp(player);
				}
			} else {
				s.breath = Math.min(MAX_BREATH, s.breath + (s.exhausted ? 1 : 2));
				if (s.exhausted && s.breath >= RECOVER_THRESHOLD) {
					s.exhausted = false;
				}
				if (--s.noiseTimer <= 0 && player.isAlive()) {
					boolean panting = s.breath < MAX_BREATH * 3 / 5;
					s.noiseTimer = panting ? 15 : 40;
					float radius = panting ? 5.0f : 2.5f;
					if (HideManager.isHidden(player)) {
						radius *= 0.5f; // muffled by the wardrobe
					}
					NoiseSystem.emit(player.serverLevel(), player.getEyePosition(), radius, player);
				}
			}

			if (s.breath != s.sentBreath || s.holding != s.sentHolding || s.exhausted != s.sentExhausted) {
				s.sentBreath = s.breath;
				s.sentHolding = s.holding;
				s.sentExhausted = s.exhausted;
				ModNetwork.sendBreath(player, s.breath / (float) MAX_BREATH, s.holding, s.exhausted);
			}
		}
		if (server.getTickCount() % 200 == 0) {
			STATES.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
		}
	}

	private static void gasp(ServerPlayer player) {
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.PLAYER_HURT_DROWN, SoundSource.PLAYERS, 1.2f, 0.6f);
		player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 50, 1, false, false));
		NoiseSystem.emit(player.serverLevel(), player.getEyePosition(), 16.0f, player);
	}

	public static void reset() {
		STATES.clear();
	}
}
