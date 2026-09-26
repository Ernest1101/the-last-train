package com.lasttrain.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.function.IntConsumer;

/**
 * A page of the family's story, found around the mansion. NBT "Note" = 1..{@link #COUNT}.
 * Knowing all of them (and burning the family photo) lifts the curse.
 */
public class NoteItem extends Item {
	public static final int COUNT = 6;
	private static final String NOTE = "Note";
	/** Set by the client entrypoint; opens the note with the given number. */
	public static IntConsumer openScreen = number -> {
	};

	public NoteItem(Properties properties) {
		super(properties);
	}

	public static ItemStack create(Item item, int number) {
		ItemStack stack = new ItemStack(item);
		stack.getOrCreateTag().putInt(NOTE, number);
		return stack;
	}

	public static int number(ItemStack stack) {
		return stack.hasTag() ? stack.getTag().getInt(NOTE) : 0;
	}

	@Override
	public Component getName(ItemStack stack) {
		int n = number(stack);
		return n > 0 ? Component.translatable("item.lasttrain.note.numbered", n) : super.getName(stack);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (level.isClientSide) {
			openScreen.accept(Math.max(1, number(stack)));
		}
		return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
	}
}
