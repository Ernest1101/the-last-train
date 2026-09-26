package com.lasttrain.item;

import com.lasttrain.entity.ThrownBottleEntity;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** An empty bottle to throw: it smashes somewhere else and the Blind One goes to check the noise. */
public class ThrowableBottleItem extends Item {
	public ThrowableBottleItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.4f, 0.6f);
		if (!level.isClientSide) {
			ThrownBottleEntity bottle = new ThrownBottleEntity(level, player);
			bottle.setItem(stack);
			bottle.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0f, 1.3f, 1.0f);
			level.addFreshEntity(bottle);
		}
		if (!player.getAbilities().instabuild) {
			stack.shrink(1);
		}
		player.getCooldowns().addCooldown(this, 10);
		return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
	}
}
