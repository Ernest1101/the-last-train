package com.lasttrain.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A brass chandelier with eight candles hanging from the ceiling. Some still burn (LIT), most went out long ago. */
public class ChandelierBlock extends Block {
	public static final BooleanProperty LIT = BlockStateProperties.LIT;
	private static final VoxelShape SHAPE = Block.box(1, 3, 1, 15, 16, 15);
	/** Candle wick tips, in block pixels (see tools/gen_furniture.py): four on the arms, four turned 45 degrees. */
	private static final double[][] WICKS;

	static {
		double[][] straight = {{2, 8}, {14, 8}, {8, 2}, {8, 14}};
		WICKS = new double[8][];
		for (int i = 0; i < 4; i++) {
			WICKS[i] = straight[i];
			double dx = straight[i][0] - 8, dz = straight[i][1] - 8;
			double c = Math.cos(Math.toRadians(45)), s = Math.sin(Math.toRadians(45));
			WICKS[4 + i] = new double[]{8 + dx * c - dz * s, 8 + dx * s + dz * c};
		}
	}

	public ChandelierBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(LIT, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(LIT);
	}

	@Override
	@SuppressWarnings("deprecation")
	public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
		if (!state.getValue(LIT)) {
			return;
		}
		for (double[] wick : WICKS) {
			if (random.nextFloat() < 0.6f) {
				level.addParticle(ParticleTypes.SMALL_FLAME, pos.getX() + wick[0] / 16.0, pos.getY() + 11.4 / 16.0, pos.getZ() + wick[1] / 16.0, 0, 0, 0);
			}
			if (random.nextFloat() < 0.04f) {
				level.addParticle(ParticleTypes.SMOKE, pos.getX() + wick[0] / 16.0, pos.getY() + 11.8 / 16.0, pos.getZ() + wick[1] / 16.0, 0, 0.01, 0);
			}
		}
	}
}
