package com.lasttrain.block;

import com.lasttrain.game.GameSession;
import com.lasttrain.noise.NoiseSystem;
import com.lasttrain.registry.ModRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** The basement fuse box. Put a fuse in it to get the power (and the front door's electric lock) back. */
public class FuseBoxBlock extends HorizontalDirectionalBlock {
	public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
	private static final VoxelShape NORTH = Block.box(3, 2, 13, 13, 14, 16);
	private static final VoxelShape SOUTH = Block.box(3, 2, 0, 13, 14, 3);
	private static final VoxelShape EAST = Block.box(0, 2, 3, 3, 14, 13);
	private static final VoxelShape WEST = Block.box(13, 2, 3, 16, 14, 13);

	public FuseBoxBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(POWERED, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, POWERED);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	@SuppressWarnings("deprecation")
	public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return switch (state.getValue(FACING)) {
			case SOUTH -> SOUTH;
			case EAST -> EAST;
			case WEST -> WEST;
			default -> NORTH;
		};
	}

	@Override
	@SuppressWarnings("deprecation")
	public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		if (level.isClientSide) {
			return InteractionResult.SUCCESS;
		}
		if (state.getValue(POWERED)) {
			player.displayClientMessage(Component.translatable("message.lasttrain.power_on"), true);
			return InteractionResult.CONSUME;
		}
		ItemStack held = player.getItemInHand(hand);
		if (!held.is(ModRegistry.FUSE)) {
			player.displayClientMessage(Component.translatable("message.lasttrain.need_fuse").withStyle(ChatFormatting.GRAY), true);
			return InteractionResult.CONSUME;
		}
		if (!player.getAbilities().instabuild) {
			held.shrink(1);
		}
		level.setBlock(pos, state.setValue(POWERED, true), Block.UPDATE_ALL);
		level.playSound(null, pos, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 1.0f, 0.6f);
		level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 0.8f, 0.5f);
		NoiseSystem.emit((ServerLevel) level, Vec3.atCenterOf(pos), 10.0f, player);
		GameSession.onPowerRestored((ServerLevel) level, player);
		return InteractionResult.CONSUME;
	}
}
