package com.lasttrain.block;

import com.lasttrain.noise.NoiseSystem;
import com.lasttrain.registry.ModRegistry;
import com.lasttrain.registry.ModSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A bookshelf with one red book sticking out. Pull it and the shelf (with the one above it) slides away
 * with a long creak, opening the way into a hidden room. The Blind One hears that creak.
 */
public class SecretBookshelfBlock extends HorizontalDirectionalBlock {
	/** How far the creak of the sliding shelf carries. */
	public static final float CREAK_RADIUS = 12.0f;

	public SecretBookshelfBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
	}

	/** FACING: the side the red book sticks out of (towards the room). */
	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	@SuppressWarnings("deprecation")
	public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		if (!level.isClientSide) {
			open((ServerLevel) level, pos, player);
		}
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	/** Slides the shelf away: this block and the plain shelf on top of it become the doorway. */
	public static void open(ServerLevel level, BlockPos pos, Player player) {
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ModRegistry.OLD_BOOKSHELF.defaultBlockState()),
				pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 40, 0.3, 0.8, 0.3, 0.05);
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		if (level.getBlockState(pos.above()).is(ModRegistry.OLD_BOOKSHELF)) {
			level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 3);
		}
		level.playSound(null, pos, SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 1.0f, 0.5f);
		level.playSound(null, pos, ModSounds.CREAK, SoundSource.BLOCKS, 1.5f, 0.6f);
		player.displayClientMessage(Component.translatable("message.lasttrain.secret_opened").withStyle(ChatFormatting.GRAY), true);
		NoiseSystem.emit(level, Vec3.atCenterOf(pos), CREAK_RADIUS, player);
	}
}
