package com.lasttrain.client;

import com.lasttrain.entity.TrainEntity;
import com.lasttrain.network.ModNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * The train's entity hitbox only covers the middle of the wagon (hitboxes must be square), so
 * right-clicks are ray-tested against the whole train here and turned into a boarding request.
 */
public final class TrainBoarding {
	private static final double REACH = 5.0;

	private TrainBoarding() {
	}

	/** @return true if the click was used to board a train */
	public static boolean tryBoard(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || player.getVehicle() instanceof TrainEntity || player.isSpectator()) {
			return false;
		}
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1.0f).scale(REACH));
		double blockDistance = Double.MAX_VALUE;
		if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) {
			blockDistance = mc.hitResult.getLocation().distanceTo(eye);
		}

		TrainEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (TrainEntity train : mc.level.getEntitiesOfClass(TrainEntity.class, player.getBoundingBox().inflate(30.0))) {
			Optional<Vec3> hit = train.getTrainBox().clip(eye, end);
			if (hit.isPresent()) {
				double d = hit.get().distanceTo(eye);
				if (d < bestDistance && d <= blockDistance + 0.01) {
					best = train;
					bestDistance = d;
				}
			}
		}
		if (best == null || !ClientPlayNetworking.canSend(ModNetwork.BOARD_TRAIN)) {
			return false;
		}
		FriendlyByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(best.getId());
		ClientPlayNetworking.send(ModNetwork.BOARD_TRAIN, buf);
		player.swing(InteractionHand.MAIN_HAND);
		return true;
	}
}
