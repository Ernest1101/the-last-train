package com.lasttrain.client;

import com.lasttrain.client.render.BlindOneRenderer;
import com.lasttrain.client.render.DollRenderer;
import com.lasttrain.client.render.TrainRenderer;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import com.lasttrain.client.render.WatcherRenderer;
import com.lasttrain.item.CursedDiaryItem;
import com.lasttrain.entity.BlindOneEntity;
import com.lasttrain.network.ModNetwork;
import com.lasttrain.registry.ModRegistry;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

public class LastTrainClient implements ClientModInitializer {
	private static KeyMapping holdBreathKey;
	private static KeyMapping toggleBodycamKey;
	private static boolean sentHold;
	private static int heartbeatTimer;

	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(ModRegistry.BLIND_ONE, BlindOneRenderer::new);
		EntityRendererRegistry.register(ModRegistry.TRAIN, TrainRenderer::new);
		EntityRendererRegistry.register(ModRegistry.WATCHER, WatcherRenderer::new);
		EntityRendererRegistry.register(ModRegistry.DOLL, DollRenderer::new);
		EntityRendererRegistry.register(ModRegistry.DOPPELGANGER, com.lasttrain.client.render.DoppelgangerRenderer::new);
		EntityRendererRegistry.register(ModRegistry.THROWN_BOTTLE, ThrownItemRenderer::new);
		com.lasttrain.item.NoteItem.openScreen = DiaryScreen::openNote;
		CursedDiaryItem.openScreen = DiaryScreen::open;
		BlockRenderLayerMap.INSTANCE.putBlocks(RenderType.cutout(), ModRegistry.LOCKED_DOOR, ModRegistry.OLD_DOOR,
				ModRegistry.TRACK_SLEEPERS, ModRegistry.TRACK_RAIL_NORTH, ModRegistry.TRACK_RAIL_SOUTH,
				ModRegistry.LAMP_POLE, ModRegistry.STATION_LAMP, ModRegistry.BOARDED_DOOR);
		BlockRenderLayerMap.INSTANCE.putBlock(ModRegistry.BOARDED_WINDOW, RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlocks(RenderType.cutout(), ModRegistry.CABINET, ModRegistry.DRESSER,
				ModRegistry.NIGHTSTAND, ModRegistry.CRATE, ModRegistry.OLD_TABLE, ModRegistry.OLD_CHAIR,
				ModRegistry.PLATE, ModRegistry.CUP, ModRegistry.BOOK_STACK);
		BlockRenderLayerMap.INSTANCE.putBlock(ModRegistry.DISPLAY_CABINET, RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlock(ModRegistry.CHANDELIER, RenderType.cutout());
		net.minecraft.client.renderer.blockentity.BlockEntityRenderers.register(ModRegistry.CLOCK_ENTITY,
				com.lasttrain.client.render.GrandfatherClockRenderer::new);

		holdBreathKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.lasttrain.hold_breath",
				InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, "category.lasttrain"));
		toggleBodycamKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.lasttrain.toggle_bodycam",
				InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, "category.lasttrain"));

		ClientPlayNetworking.registerGlobalReceiver(ModNetwork.BREATH, (client, handler, buf, responseSender) -> {
			float breath = buf.readFloat();
			boolean holding = buf.readBoolean();
			boolean exhausted = buf.readBoolean();
			client.execute(() -> {
				ClientState.breath = breath;
				ClientState.holding = holding;
				ClientState.exhausted = exhausted;
			});
		});
		ClientPlayNetworking.registerGlobalReceiver(ModNetwork.SCARE, (client, handler, buf, responseSender) ->
				client.execute(() -> {
					ClientState.scareTicks = ClientState.SCARE_LENGTH;
					if (client.player != null) {
						client.player.playSound(com.lasttrain.registry.ModSounds.BLIND_SCREAM, 1.0f, 1.2f);
					}
				}));
		ClientPlayNetworking.registerGlobalReceiver(ModNetwork.ENDING, (client, handler, buf, responseSender) -> {
			int ending = buf.readVarInt();
			client.execute(() -> {
				ClientState.lensCracked = false;
				ClientState.hidden = false;
				if (!Cutscene.deferEnding(ending)) {
					client.setScreen(new EndingScreen(ending));
				}
			});
		});
		ClientPlayNetworking.registerGlobalReceiver(ModNetwork.CUTSCENE, (client, handler, buf, responseSender) -> {
			int kind = buf.readVarInt();
			int train = buf.readVarInt();
			net.minecraft.world.phys.Vec3[] points = new net.minecraft.world.phys.Vec3[3];
			for (int i = 0; i < 3; i++) {
				points[i] = new net.minecraft.world.phys.Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
			}
			client.execute(() -> Cutscene.start(kind, train, points[0], points[1], points[2]));
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientState.reset();
			CurseClient.reset();
			RewindEffect.reset();
			Cutscene.reset();
			sentHold = false;
		});
		HudRenderCallback.EVENT.register(HudOverlay::render);
		BodycamPostEffect.init();
		CurseClient.init();
		ClientTickEvents.END_CLIENT_TICK.register(LastTrainClient::tick);
	}

	private static void tick(Minecraft mc) {
		BodycamPostEffect.tick(mc);
		CurseClient.tick(mc);
		DroneSound.update(mc);
		RewindEffect.tick(mc);
		Cutscene.tick(mc);
		while (toggleBodycamKey.consumeClick()) {
			ClientState.bodycamEnabled = !ClientState.bodycamEnabled;
		}
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			return;
		}

		boolean hold = holdBreathKey.isDown() && mc.screen == null;
		if (hold != sentHold && ClientPlayNetworking.canSend(ModNetwork.HOLD_BREATH)) {
			sentHold = hold;
			FriendlyByteBuf buf = PacketByteBufs.create();
			buf.writeBoolean(hold);
			ClientPlayNetworking.send(ModNetwork.HOLD_BREATH, buf);
		}
		if (ClientState.holding) {
			player.setSprinting(false);
		}
		if (ClientState.scareTicks > 0) {
			ClientState.scareTicks--;
		}

		// fear = proximity of the closest Blind One
		double closest = Double.MAX_VALUE;
		for (BlindOneEntity monster : mc.level.getEntitiesOfClass(BlindOneEntity.class, player.getBoundingBox().inflate(20.0))) {
			closest = Math.min(closest, monster.distanceTo(player));
		}
		float target = closest == Double.MAX_VALUE ? 0.0f : Mth.clamp(1.0f - (float) (closest - 2.0) / 16.0f, 0.0f, 1.0f);
		ClientState.fear += (target - ClientState.fear) * 0.1f;

		if (ClientState.fear > 0.08f && --heartbeatTimer <= 0) {
			heartbeatTimer = (int) Mth.lerp(ClientState.fear, 40.0f, 11.0f);
			mc.level.playLocalSound(player.getX(), player.getY(), player.getZ(), com.lasttrain.registry.ModSounds.HEARTBEAT,
					SoundSource.PLAYERS, 0.4f + ClientState.fear * 0.8f, 1.0f, false);
		}
	}
}
