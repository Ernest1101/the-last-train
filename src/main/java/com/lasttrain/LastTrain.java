package com.lasttrain;

import com.lasttrain.config.LastTrainConfig;
import com.lasttrain.game.BreathManager;
import com.lasttrain.game.HideManager;
import com.lasttrain.game.WorldOmens;
import com.lasttrain.registry.ModSounds;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.CampfireBlock;
import com.lasttrain.game.CurseManager;
import com.lasttrain.game.GameSession;
import com.lasttrain.game.LastTrainCommand;
import com.lasttrain.game.WatcherManager;
import com.lasttrain.noise.NoiseEvents;
import com.lasttrain.noise.PlayerNoiseTracker;
import com.lasttrain.network.ModNetwork;
import com.lasttrain.registry.ModRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LastTrain implements ModInitializer {
	public static final String MOD_ID = "lasttrain";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static ResourceLocation id(String path) {
		return new ResourceLocation(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		LastTrainConfig.load();
		ModRegistry.init();
		ModSounds.init();
		ModNetwork.initServer();
		NoiseEvents.init();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				LastTrainCommand.register(dispatcher));

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			PlayerNoiseTracker.tick(server);
			BreathManager.tick(server);
			GameSession.tick(server);
			CurseManager.tick(server);
			if (LastTrainConfig.get().watcherEnabled) {
				WatcherManager.tick(server);
			}
			HideManager.tick(server);
			WorldOmens.tick(server);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			GameSession.reset();
			BreathManager.reset();
			PlayerNoiseTracker.reset();
			CurseManager.clearRuntime();
			WatcherManager.reset();
			HideManager.reset();
			WorldOmens.reset();
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player) {
				GameSession.onPlayerDeath(player);
			}
		});
		// burning the family photo in a fireplace (with all the notes) lifts the curse
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!level.isClientSide && hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer serverPlayer
					&& player.getMainHandItem().is(ModRegistry.FAMILY_PHOTO)
					&& level.getBlockState(hit.getBlockPos()).getBlock() instanceof CampfireBlock) {
				GameSession.tryLiftCurse(serverPlayer, hit.getBlockPos());
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				server.execute(() -> CurseManager.onJoin(handler.getPlayer())));
	}
}
