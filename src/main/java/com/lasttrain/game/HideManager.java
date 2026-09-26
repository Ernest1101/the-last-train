package com.lasttrain.game;

import com.lasttrain.block.WardrobeBlock;
import com.lasttrain.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Players hiding in wardrobes. A hidden player makes no footsteps and breathes quieter;
 * the Blind One only finds them if it hears them breathing right next to the wardrobe.
 */
public final class HideManager {
	private record Spot(BlockPos pos, Direction facing) {
		/**
		 * Where the hidden player sits: inside the wardrobe, back against the rear wall, peering out
		 * between the slats of the louvred doors (the wardrobe model is hollow). Any closer and the
		 * eye is pressed to a single slit.
		 */
		Vec3 eyeSpot() {
			return new Vec3(this.pos.getX() + 0.5 - this.facing.getStepX() * 0.25, this.pos.getY(),
					this.pos.getZ() + 0.5 - this.facing.getStepZ() * 0.25);
		}
	}

	/** How far a hidden player may turn their head from looking out through the doors. */
	private static final float MAX_TURN = 60.0f;
	private static final float MAX_PITCH = 40.0f;

	private static final Map<UUID, Spot> HIDDEN = new HashMap<>();
	/** Players who were already sneaking when they got in: sneaking only lets them out after a new press. */
	private static final java.util.Set<UUID> SNEAK_HELD = new java.util.HashSet<>();

	private HideManager() {
	}

	public static boolean isHidden(Player player) {
		return HIDDEN.containsKey(player.getUUID());
	}

	@Nullable
	public static BlockPos spotOf(Player player) {
		Spot spot = HIDDEN.get(player.getUUID());
		return spot == null ? null : spot.pos();
	}

	public static void toggle(ServerPlayer player, BlockPos wardrobe, Direction facing) {
		if (isHidden(player)) {
			release(player);
			return;
		}
		for (Spot spot : HIDDEN.values()) {
			if (spot.pos().equals(wardrobe)) {
				return; // somebody is already in there
			}
		}
		HIDDEN.put(player.getUUID(), new Spot(wardrobe, facing));
		if (player.isShiftKeyDown()) {
			SNEAK_HELD.add(player.getUUID());
		}
		Vec3 eye = new Spot(wardrobe, facing).eyeSpot();
		// looking out through the gap; teleporting with a rotation is what actually turns the client's camera
		player.teleportTo(player.serverLevel(), eye.x, eye.y, eye.z, facing.toYRot(), 0.0f);
		player.setInvisible(true); // nobody sees the body through the slats
		player.level().playSound(null, wardrobe, SoundEvents.WOODEN_DOOR_CLOSE, SoundSource.BLOCKS, 0.5f, 0.8f);
		ModNetwork.sendHidden(player, true);
	}

	/** Out of the wardrobe, in front of its doors. */
	public static void release(ServerPlayer player) {
		Spot spot = HIDDEN.remove(player.getUUID());
		ModNetwork.sendHidden(player, false);
		player.setInvisible(false);
		if (spot == null) {
			return;
		}
		BlockPos out = spot.pos().relative(spot.facing());
		player.teleportTo(out.getX() + 0.5, out.getY(), out.getZ() + 0.5);
		player.level().playSound(null, spot.pos(), SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS, 0.6f, 0.8f);
	}

	public static void tick(MinecraftServer server) {
		Iterator<Map.Entry<UUID, Spot>> it = HIDDEN.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Spot> entry = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				it.remove();
				continue;
			}
			Spot spot = entry.getValue();
			if (!player.isShiftKeyDown()) {
				SNEAK_HELD.remove(player.getUUID());
			}
			boolean wantsOut = player.isShiftKeyDown() && !SNEAK_HELD.contains(player.getUUID());
			boolean wardrobeGone = !(player.level().getBlockState(spot.pos()).getBlock() instanceof WardrobeBlock);
			if (!player.isAlive() || wardrobeGone || wantsOut) {
				it.remove();
				ModNetwork.sendHidden(player, false);
				player.setInvisible(false);
				if (player.isAlive() && !wardrobeGone) {
					BlockPos out = spot.pos().relative(spot.facing());
					player.teleportTo(out.getX() + 0.5, out.getY(), out.getZ() + 0.5);
				}
				continue;
			}
			Vec3 inside = spot.eyeSpot();
			float facingYaw = spot.facing().toYRot();
			float turn = net.minecraft.util.Mth.wrapDegrees(player.getYRot() - facingYaw);
			boolean turnedAway = Math.abs(turn) > MAX_TURN + 1.0f || Math.abs(player.getXRot()) > MAX_PITCH + 1.0f;
			if (player.position().distanceToSqr(inside) > 0.04 || turnedAway) {
				// back to the gap, and no looking round into the dark behind you
				float yaw = facingYaw + net.minecraft.util.Mth.clamp(turn, -MAX_TURN, MAX_TURN);
				float pitch = net.minecraft.util.Mth.clamp(player.getXRot(), -MAX_PITCH, MAX_PITCH);
				player.teleportTo(player.serverLevel(), inside.x, inside.y, inside.z, yaw, pitch);
			}
			player.setDeltaMovement(Vec3.ZERO);
		}
	}

	public static void reset() {
		HIDDEN.clear();
		SNEAK_HELD.clear();
	}
}
