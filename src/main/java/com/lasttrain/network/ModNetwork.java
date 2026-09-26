package com.lasttrain.network;

import com.lasttrain.LastTrain;
import com.lasttrain.entity.TrainEntity;
import com.lasttrain.game.BreathManager;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public final class ModNetwork {
	/** C2S: boolean - hold-breath key state. */
	public static final ResourceLocation HOLD_BREATH = LastTrain.id("hold_breath");
	/** C2S: varint train entity id - player clicked somewhere on the train. */
	public static final ResourceLocation BOARD_TRAIN = LastTrain.id("board_train");
	/** S2C: float breath 0..1, boolean holding, boolean exhausted. */
	public static final ResourceLocation BREATH = LastTrain.id("breath");
	/** S2C: nothing - the monster struck the player. */
	public static final ResourceLocation SCARE = LastTrain.id("scare");
	/** S2C: int ending id (see {@link #ENDING_ESCAPED}). */
	public static final ResourceLocation ENDING = LastTrain.id("ending");

	/** S2C: resource location sound, 3 floats offset, float volume, float pitch - a sound behind the player. */
	public static final ResourceLocation AMBIENT = LastTrain.id("ambient");
	/** S2C: varint face - full-screen jumpscare. */
	public static final ResourceLocation SCREAMER = LastTrain.id("screamer");
	/** S2C: nothing - the third night has come, play the death sequence. */
	public static final ResourceLocation CURSE_DEATH = LastTrain.id("curse_death");
	/** S2C: varint day, varint total days, boolean night, varint diary tasks done (bitmask). */
	public static final ResourceLocation CURSE_DAY = LastTrain.id("curse_day");

	/** S2C: nothing - the player looked at a Watcher. */
	public static final ResourceLocation WATCHER_SCARE = LastTrain.id("watcher_scare");

	/** S2C: boolean - the player is hiding in a wardrobe. */
	public static final ResourceLocation HIDDEN = LastTrain.id("hidden");
	/** S2C: boolean - the bodycam lens is cracked (after the first death in the house). */
	public static final ResourceLocation LENS_CRACK = LastTrain.id("lens_crack");

	/** S2C: the Double shrieked in your face (payload: whose skin it wore). */
	public static final ResourceLocation DOPPEL_SCARE = LastTrain.id("doppel_scare");
	/** S2C: you died in the house; rewind the tape after the respawn. */
	public static final ResourceLocation REWIND = LastTrain.id("rewind");
	public static final int ENDING_ESCAPED = 0;
	public static final int ENDING_MISSED = 1;
	/** The family photo burned: the curse is lifted. */
	public static final int ENDING_FREED = 2;

	private ModNetwork() {
	}

	public static void initServer() {
		ServerPlayNetworking.registerGlobalReceiver(HOLD_BREATH, (server, player, handler, buf, responseSender) -> {
			boolean hold = buf.readBoolean();
			server.execute(() -> BreathManager.setWantsToHold(player, hold));
		});
		ServerPlayNetworking.registerGlobalReceiver(BOARD_TRAIN, (server, player, handler, buf, responseSender) -> {
			int id = buf.readVarInt();
			server.execute(() -> {
				// the client did the ray test; just check the player is actually next to that train
				if (player.level().getEntity(id) instanceof TrainEntity train
						&& train.getTrainBox().inflate(6.0).contains(player.getEyePosition())) {
					train.board(player);
				}
			});
		});
	}

	public static void sendBreath(ServerPlayer player, float breath, boolean holding, boolean exhausted) {
		FriendlyByteBuf buf = PacketByteBufs.create();
		buf.writeFloat(breath);
		buf.writeBoolean(holding);
		buf.writeBoolean(exhausted);
		ServerPlayNetworking.send(player, BREATH, buf);
	}

	public static void sendScare(ServerPlayer player) {
		ServerPlayNetworking.send(player, SCARE, PacketByteBufs.empty());
	}

	public static void sendEnding(ServerPlayer player, int ending) {
		FriendlyByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(ending);
		ServerPlayNetworking.send(player, ENDING, buf);
	}

	public static void sendAmbient(ServerPlayer player, ResourceLocation sound, net.minecraft.world.phys.Vec3 offset, float volume, float pitch) {
		FriendlyByteBuf buf = PacketByteBufs.create();
		buf.writeResourceLocation(sound);
		buf.writeFloat((float) offset.x);
		buf.writeFloat((float) offset.y);
		buf.writeFloat((float) offset.z);
		buf.writeFloat(volume);
		buf.writeFloat(pitch);
		ServerPlayNetworking.send(player, AMBIENT, buf);
	}

	public static void sendScreamer(ServerPlayer player, int face) {
		FriendlyByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(face);
		ServerPlayNetworking.send(player, SCREAMER, buf);
	}

	public static void sendDoppelScare(ServerPlayer player, java.util.UUID skin) {
		FriendlyByteBuf buf = PacketByteBufs.create();
		buf.writeUUID(skin);
		ServerPlayNetworking.send(player, DOPPEL_SCARE, buf);
	}

	public static void sendRewind(ServerPlayer player) {
		ServerPlayNetworking.send(player, REWIND, PacketByteBufs.empty());
	}

	public static void sendWatcherScare(ServerPlayer player) {
		ServerPlayNetworking.send(player, WATCHER_SCARE, PacketByteBufs.empty());
	}

	public static void sendHidden(ServerPlayer player, boolean hidden) {
		FriendlyByteBuf buf = PacketByteBufs.create();
		buf.writeBoolean(hidden);
		ServerPlayNetworking.send(player, HIDDEN, buf);
	}

	public static void sendLensCrack(ServerPlayer player, boolean cracked) {
		FriendlyByteBuf buf = PacketByteBufs.create();
		buf.writeBoolean(cracked);
		ServerPlayNetworking.send(player, LENS_CRACK, buf);
	}

	public static void sendCurseDeath(ServerPlayer player) {
		ServerPlayNetworking.send(player, CURSE_DEATH, PacketByteBufs.empty());
	}

	public static void sendCurseDay(ServerPlayer player, int day, int total, boolean night, int tasks) {
		FriendlyByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(day);
		buf.writeVarInt(total);
		buf.writeBoolean(night);
		buf.writeVarInt(tasks);
		ServerPlayNetworking.send(player, CURSE_DAY, buf);
	}
}
