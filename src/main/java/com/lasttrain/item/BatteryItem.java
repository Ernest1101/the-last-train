package com.lasttrain.item;

import com.lasttrain.registry.ModSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Use to recharge the flashlight you are carrying. */
public class BatteryItem extends Item {
	public BatteryItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack battery = player.getItemInHand(hand);
		java.util.List<ItemStack> carried = new java.util.ArrayList<>(player.getInventory().items);
		carried.add(player.getOffhandItem());
		for (ItemStack stack : carried) {
			if (stack.getItem() instanceof FlashlightItem && stack.getDamageValue() > 0) {
				if (!level.isClientSide) {
					stack.setDamageValue(0);
					if (!player.getAbilities().instabuild) {
						battery.shrink(1);
					}
					level.playSound(null, player.getX(), player.getY(), player.getZ(), ModSounds.CLICK, SoundSource.PLAYERS, 0.6f, 0.7f);
					player.displayClientMessage(Component.translatable("message.lasttrain.battery_replaced").withStyle(ChatFormatting.GREEN), true);
				}
				return InteractionResultHolder.sidedSuccess(battery, level.isClientSide);
			}
		}
		return InteractionResultHolder.fail(battery);
	}
}
