package com.lasttrain.client;

import com.lasttrain.LastTrain;
import com.lasttrain.network.ModNetwork;
import com.lasttrain.registry.ModRegistry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Client side of the three-day curse: sounds behind you, screamers, the death on the third night. */
public final class CurseClient {
	public static final ResourceLocation FOOTSTEPS = LastTrain.id("footsteps");
	public static final int SCREAMER_LENGTH = 18;
	public static final int DEATH_BLACKOUT = 110;
	private static final int DEATH_END = 420;

	/** Current curse day (1..3), 0 when not shown. */
	public static int day;
	public static int totalDays = 3;
	public static boolean night;
	/** Diary tasks done, bit 0 = day one. */
	public static int tasks;
	private static int dayTimeout;
	public static int screamerTicks;
	public static int screamerFace;
	/** Ticks since the death sequence started, -1 when not running. */
	public static int deathTicks = -1;

	private static final List<Step> STEPS = new ArrayList<>();

	private record Step(Vec3 offset, int delay) {
	}

	private CurseClient() {
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(ModNetwork.AMBIENT, (client, handler, buf, sender) -> {
			ResourceLocation id = buf.readResourceLocation();
			Vec3 offset = new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat());
			float volume = buf.readFloat();
			float pitch = buf.readFloat();
			client.execute(() -> playAmbient(client, id, offset, volume, pitch));
		});
		ClientPlayNetworking.registerGlobalReceiver(ModNetwork.SCREAMER, (client, handler, buf, sender) -> {
			int face = buf.readVarInt();
			client.execute(() -> screamer(client, face));
		});
		ClientPlayNetworking.registerGlobalReceiver(ModNetwork.DOPPEL_SCARE, (client, handler, buf, sender) -> {
			java.util.UUID skin = buf.readUUID();
			client.execute(() -> doppelScare(client, skin));
		});
		ClientPlayNetworking.registerGlobalReceiver(ModNetwork.REWIND, (client, handler, buf, sender) ->
				client.execute(RewindEffect::onDeath));
		ClientPlayNetworking.registerGlobalReceiver(ModNetwork.WATCHER_SCARE, (client, handler, buf, sender) ->
				client.execute(() -> watcherScare(client)));
		ClientPlayNetworking.registerGlobalReceiver(ModNetwork.HIDDEN, (client, handler, buf, sender) -> {
			boolean hidden = buf.readBoolean();
			client.execute(() -> ClientState.hidden = hidden);
		});
		ClientPlayNetworking.registerGlobalReceiver(ModNetwork.LENS_CRACK, (client, handler, buf, sender) -> {
			boolean cracked = buf.readBoolean();
			client.execute(() -> {
				ClientState.lensCracked = cracked;
				if (cracked && client.player != null) {
					client.player.playSound(net.minecraft.sounds.SoundEvents.GLASS_BREAK, 1.0f, 1.4f);
				}
			});
		});
		ClientPlayNetworking.registerGlobalReceiver(ModNetwork.CURSE_DEATH, (client, handler, buf, sender) ->
				client.execute(() -> deathTicks = 0));
		ClientPlayNetworking.registerGlobalReceiver(ModNetwork.CURSE_DAY, (client, handler, buf, sender) -> {
			int d = buf.readVarInt();
			int total = buf.readVarInt();
			boolean n = buf.readBoolean();
			int done = buf.readVarInt();
			client.execute(() -> {
				day = d;
				totalDays = total;
				night = n;
				tasks = done;
				dayTimeout = 200;
			});
		});
	}

	private static void playAmbient(Minecraft mc, ResourceLocation id, Vec3 offset, float volume, float pitch) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			return;
		}
		if (FOOTSTEPS.equals(id)) {
			// someone walks up behind you... and stops
			for (int i = 0; i < 6; i++) {
				STEPS.add(new Step(offset.scale(1.0 - i * 0.13), i * 9));
			}
			return;
		}
		SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(id);
		if (sound != null) {
			Vec3 at = player.position().add(offset);
			mc.level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.AMBIENT, volume, pitch, false);
		}
	}

	/** Face of the Watcher (texture screamer_2), shown with "SOON THE END". */
	public static final int WATCHER_FACE = 2;
	public static final int WATCHER_LENGTH = 40;

	/** You looked at the Watcher: it is right in front of you now. */
	public static void watcherScare(Minecraft mc) {
		screamerTicks = WATCHER_LENGTH;
		screamerFace = WATCHER_FACE;
		ClientState.scareTicks = ClientState.SCARE_LENGTH;
		if (mc.player != null) {
			mc.player.playSound(com.lasttrain.registry.ModSounds.STATIC, 1.0f, 0.6f);
			mc.player.playSound(SoundEvents.ENDERMAN_SCREAM, 1.0f, 0.4f);
		}
	}

	/** The Double dropped its act: its (your friend's) face, torn open. Drawn from the skin, not a texture. */
	public static final int DOPPEL_FACE = 3;
	public static final int DOPPEL_LENGTH = 30;
	public static java.util.UUID doppelSkin = new java.util.UUID(0L, 0L);

	public static void doppelScare(Minecraft mc, java.util.UUID skin) {
		screamerTicks = DOPPEL_LENGTH;
		screamerFace = DOPPEL_FACE;
		doppelSkin = skin;
		ClientState.scareTicks = ClientState.SCARE_LENGTH;
		if (mc.player != null) {
			mc.player.playSound(com.lasttrain.registry.ModSounds.DOPPEL_SCREAM, 0.8f, 1.2f);
		}
	}

	public static void screamer(Minecraft mc, int face) {
		screamerTicks = SCREAMER_LENGTH;
		screamerFace = face;
		ClientState.scareTicks = ClientState.SCARE_LENGTH;
		if (mc.player != null) {
			mc.player.playSound(com.lasttrain.registry.ModSounds.BLIND_SCREAM, 1.0f, 1.3f);
			mc.player.playSound(SoundEvents.ENDERMAN_SCREAM, 1.0f, 0.6f);
			mc.player.playSound(SoundEvents.GHAST_HURT, 1.0f, 0.5f);
		}
	}

	public static void tick(Minecraft mc) {
		if (screamerTicks > 0) {
			screamerTicks--;
		}
		// the server stops sending while the game is paused - don't forget the day then
		if (!mc.isPaused() && dayTimeout > 0 && --dayTimeout == 0) {
			day = 0;
		}
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			STEPS.clear();
			return;
		}

		tickSignal(mc, player);
		tickFlashlight(player);

		Iterator<Step> it = STEPS.iterator();
		List<Step> later = new ArrayList<>();
		while (it.hasNext()) {
			Step step = it.next();
			it.remove();
			if (step.delay() <= 0) {
				Vec3 at = player.position().add(step.offset());
				mc.level.playLocalSound(at.x, at.y, at.z, mc.level.dimension() == ModRegistry.NIGHTMARE ? SoundEvents.WOOD_STEP : SoundEvents.GRASS_STEP,
						SoundSource.AMBIENT, 0.9f, 0.7f, false);
			} else {
				later.add(new Step(step.offset(), step.delay() - 1));
			}
		}
		STEPS.addAll(later);

		if (deathTicks >= 0) {
			if (mc.level.dimension() == ModRegistry.NIGHTMARE || deathTicks > DEATH_END) {
				deathTicks = -1; // woke up in the house
				return;
			}
			deathTicks++;
			ClientState.fear = Math.max(ClientState.fear, Math.min(1.0f, deathTicks / 80.0f));
			if (deathTicks < DEATH_BLACKOUT && deathTicks % Math.max(4, 16 - deathTicks / 8) == 0) {
				player.playSound(com.lasttrain.registry.ModSounds.HEARTBEAT, 1.0f, 1.0f);
			}
			if (deathTicks == 40) {
				player.playSound(SoundEvents.SOUL_ESCAPE, 1.0f, 0.5f);
				player.playSound(com.lasttrain.registry.ModSounds.WHISPER, 1.0f, 0.7f);
			}
			if (deathTicks == 90) {
				screamer(mc, 0);
			}
			if (deathTicks == DEATH_BLACKOUT) {
				player.playSound(SoundEvents.PLAYER_DEATH, 1.0f, 0.6f);
			}
		}
	}

	/** The picture drops out now and then while a Watcher is around. */
	private static void tickSignal(Minecraft mc, LocalPlayer player) {
		if (ClientState.noSignalTicks > 0) {
			ClientState.noSignalTicks--;
			return;
		}
		boolean watcherNear = !mc.level.getEntitiesOfClass(com.lasttrain.entity.WatcherEntity.class,
				player.getBoundingBox().inflate(24.0)).isEmpty();
		if (watcherNear) {
			ClientState.fear = Math.max(ClientState.fear, 0.45f);
			if (player.getRandom().nextFloat() < 0.012f) {
				ClientState.noSignalTicks = 8 + player.getRandom().nextInt(12);
				player.playSound(com.lasttrain.registry.ModSounds.STATIC, 0.8f, 1.0f);
			}
		}
	}

	private static void tickFlashlight(LocalPlayer player) {
		net.minecraft.world.item.ItemStack light = com.lasttrain.item.FlashlightItem.isOn(player.getMainHandItem())
				? player.getMainHandItem() : player.getOffhandItem();
		if (!com.lasttrain.item.FlashlightItem.isOn(light)) {
			ClientState.flashlight = 0.0f;
			return;
		}
		float charge = com.lasttrain.item.FlashlightItem.charge(light);
		boolean flicker = charge < 0.15f && player.getRandom().nextFloat() < 0.25f;
		ClientState.flashlight = flicker ? 0.15f : 0.6f + 0.4f * Math.min(1.0f, charge * 3.0f);
	}

	public static void reset() {
		day = 0;
		screamerTicks = 0;
		deathTicks = -1;
		STEPS.clear();
	}
}
