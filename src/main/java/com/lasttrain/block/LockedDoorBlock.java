package com.lasttrain.block;

import com.lasttrain.game.GameSession;
import com.lasttrain.noise.NoiseSystem;
import com.lasttrain.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The mansion's front door. Behaves like an iron door (mobs and bare hands can't open it). In a game it has
 * three locks: a padlock (the key), nailed boards (the crowbar) and an electric lock (the basement fuse box).
 * Rattling a locked door is loud.
 */
public class LockedDoorBlock extends DoorBlock {
	public LockedDoorBlock(Properties properties) {
		super(properties, BlockSetType.IRON);
	}

	@Override
	@SuppressWarnings("deprecation")
	public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		if (state.getValue(OPEN)) {
			return InteractionResult.PASS;
		}
		if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.SUCCESS;
		}
		ItemStack held = player.getItemInHand(hand);
		ServerLevel serverLevel = (ServerLevel) level;
		Vec3 centre = Vec3.atCenterOf(pos);
		switch (GameSession.tryUnlock(serverPlayer, held)) {
			case OPENED -> {
				setOpen(player, level, state, pos, true);
				consumeKey(player, held);
				NoiseSystem.emit(serverLevel, centre, 12.0f, player);
			}
			case PROGRESS -> {
				level.playSound(null, pos, SoundEvents.CHAIN_BREAK, SoundSource.BLOCKS, 1.0f, 0.7f);
				consumeKey(player, held);
				NoiseSystem.emit(serverLevel, centre, 9.0f, player);
			}
			case ALREADY_DONE -> player.displayClientMessage(Component.translatable("message.lasttrain.lock_done"), true);
			case NOT_A_TOOL -> {
				level.playSound(null, pos, SoundEvents.CHAIN_HIT, SoundSource.BLOCKS, 1.0f, 0.6f);
				player.displayClientMessage(Component.translatable("message.lasttrain.door_locked"), true);
				NoiseSystem.emit(serverLevel, centre, 7.0f, player);
			}
		}
		return InteractionResult.CONSUME;
	}

	private static void consumeKey(Player player, ItemStack held) {
		if (held.is(ModRegistry.HOUSE_KEY) && !player.getAbilities().instabuild) {
			held.shrink(1);
		}
	}
}
