package com.lasttrain.block;

import com.lasttrain.noise.NoiseSystem;
import com.lasttrain.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Looks almost like the rest of the floor, but creaks loudly when stepped on. Sneaking only softens it. */
public class CreakyFloorBlock extends Block {
	private static final Map<UUID, Long> LAST_CREAK = new HashMap<>();

	public CreakyFloorBlock(Properties properties) {
		super(properties);
	}

	@Override
	public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
		if (level instanceof ServerLevel serverLevel && entity instanceof ServerPlayer player && !player.isCreative() && !player.isSpectator()) {
			long now = level.getGameTime();
			Long last = LAST_CREAK.get(player.getUUID());
			if (last == null || now - last > 14) {
				LAST_CREAK.put(player.getUUID(), now);
				boolean careful = player.isShiftKeyDown();
				level.playSound(null, pos, ModSounds.CREAK, SoundSource.BLOCKS, careful ? 0.5f : 1.0f, 0.8f + level.random.nextFloat() * 0.3f);
				NoiseSystem.emit(serverLevel, Vec3.atCenterOf(pos), careful ? 6.0f : 13.0f, player);
			}
		}
		super.stepOn(level, pos, state, entity);
	}
}
