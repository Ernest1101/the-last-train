package com.lasttrain.noise;

import com.lasttrain.block.CabinetBlock;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.phys.Vec3;

/** Noise from interacting with the world: doors, chests, breaking blocks. */
public final class NoiseEvents {
	private NoiseEvents() {
	}

	public static void init() {
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide || hand != InteractionHand.MAIN_HAND) {
				return InteractionResult.PASS;
			}
			Block block = level.getBlockState(hit.getBlockPos()).getBlock();
			float radius = 0.0f;
			if (block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock) {
				radius = 9.0f;
			} else if (block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof CabinetBlock) {
				radius = 6.0f;
			}
			NoiseSystem.emit((ServerLevel) level, Vec3.atCenterOf(hit.getBlockPos()), radius, player);
			return InteractionResult.PASS;
		});

		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) ->
				NoiseSystem.emit((ServerLevel) level, Vec3.atCenterOf(pos), 12.0f, player));

		// getting hurt makes you cry out
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (entity instanceof net.minecraft.world.entity.player.Player && entity.level() instanceof ServerLevel level) {
				NoiseSystem.emit(level, entity.position(), 10.0f, entity);
			}
			return true;
		});
	}
}
