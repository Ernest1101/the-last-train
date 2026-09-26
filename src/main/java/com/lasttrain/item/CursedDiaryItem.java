package com.lasttrain.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** The red book every player finds in their hand: "You have 3 days". Opens a book screen on the client. */
public class CursedDiaryItem extends Item {
	/** Set by the client entrypoint; opens the diary screen. */
	public static Runnable openScreen = () -> {
	};

	public CursedDiaryItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		if (level.isClientSide) {
			openScreen.run();
		}
		return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(Component.translatable("item.lasttrain.cursed_diary.tooltip").withStyle(net.minecraft.ChatFormatting.DARK_RED));
	}
}
