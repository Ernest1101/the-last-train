package com.lasttrain.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A lamp that can go out (it flickers when the Blind One is near; the basement ones need power). */
public class LampBlock extends ShapedBlock {
	public static final BooleanProperty LIT = BlockStateProperties.LIT;

	public LampBlock(Properties properties, VoxelShape shape) {
		super(properties.lightLevel(state -> state.getValue(LIT) ? 13 : 0), shape);
		this.registerDefaultState(this.stateDefinition.any().setValue(LIT, true));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(LIT);
	}
}
