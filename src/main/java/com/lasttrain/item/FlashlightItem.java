package com.lasttrain.item;

import com.lasttrain.noise.NoiseSystem;
import com.lasttrain.registry.ModSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Lights up the middle of the bodycam picture (see the Flashlight uniform in bodycam.fsh).
 * The battery is the item's durability; the click can be heard by the monster.
 */
public class FlashlightItem extends Item {
	public static final int BATTERY_TICKS = 20 * 150;
	private static final int DRAIN_STEP = 200;
	private static final String ON = "On";

	public FlashlightItem(Properties properties) {
		super(properties.durability(BATTERY_TICKS));
	}

	public static boolean isOn(ItemStack stack) {
		return stack.getItem() instanceof FlashlightItem && stack.hasTag() && stack.getTag().getBoolean(ON);
	}

	/** 0..1 */
	public static float charge(ItemStack stack) {
		return 1.0f - stack.getDamageValue() / (float) stack.getMaxDamage();
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		boolean on = !isOn(stack) && stack.getDamageValue() < stack.getMaxDamage() - 1;
		stack.getOrCreateTag().putBoolean(ON, on);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), ModSounds.CLICK, SoundSource.PLAYERS, 0.6f, on ? 1.2f : 0.9f);
		if (level instanceof ServerLevel serverLevel) {
			NoiseSystem.emit(serverLevel, player.getEyePosition(), 5.0f, player);
			if (!on && stack.getDamageValue() >= stack.getMaxDamage() - 1) {
				player.displayClientMessage(Component.translatable("message.lasttrain.battery_dead").withStyle(ChatFormatting.RED), true);
			}
		}
		return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
	}

	@Override
	public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
		if (level.isClientSide || !isOn(stack) || !(entity instanceof Player player)) {
			return;
		}
		if (!selected && player.getOffhandItem() != stack) {
			stack.getOrCreateTag().putBoolean(ON, false); // put away = switched off
			return;
		}
		// drain in 10 s steps: every change re-syncs the stack and replays the "equip" animation
		if (player.getAbilities().instabuild || player.tickCount % DRAIN_STEP != 0) {
			return;
		}
		int damage = stack.getDamageValue() + DRAIN_STEP;
		if (damage >= stack.getMaxDamage() - 1) {
			stack.setDamageValue(stack.getMaxDamage() - 1);
			stack.getOrCreateTag().putBoolean(ON, false);
			player.displayClientMessage(Component.translatable("message.lasttrain.battery_dead").withStyle(ChatFormatting.RED), true);
		} else {
			stack.setDamageValue(damage);
		}
	}

	@Override
	public boolean isFoil(ItemStack stack) {
		return isOn(stack);
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(Component.translatable("item.lasttrain.flashlight.charge", Math.round(charge(stack) * 100)).withStyle(ChatFormatting.GRAY));
	}
}
