package com.lasttrain.game;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;

public final class LastTrainCommand {
	private LastTrainCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("lasttrain")
				// allowed in singleplayer even without cheats - starting the game is the point of the mod
				.requires(source -> source.hasPermission(2) || source.getServer().isSingleplayer())
				.then(Commands.literal("start").executes(ctx -> {
					GameSession.start(ctx.getSource().getPlayerOrException());
					return 1;
				}))
				.then(Commands.literal("stop").executes(ctx -> {
					GameSession.stop(ctx.getSource().getServer());
					ctx.getSource().sendSuccess(() -> Component.translatable("message.lasttrain.stopped"), false);
					return 1;
				}))
				.then(Commands.literal("debug").requires(source -> source.hasPermission(2))
						.then(Commands.literal("build").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> {
							// builds only the mansion and the station, without starting a game
							long start = System.currentTimeMillis();
							HouseBuilder.Layout layout = HouseBuilder.build(ctx.getSource().getLevel(),
									BlockPosArgument.getLoadedBlockPos(ctx, "pos"), 1);
							long took = System.currentTimeMillis() - start;
							ctx.getSource().sendSuccess(() -> Component.literal("Built in " + took + " ms, bedrooms: "
									+ layout.safeRooms().size() + ", monster at " + layout.monsterSpawn().toShortString()), false);
							return 1;
						}))))
				.then(Commands.literal("curse")
						.then(Commands.literal("status").executes(ctx -> {
							CurseData data = CurseManager.data(ctx.getSource().getServer());
							int day = CurseManager.currentDay(ctx.getSource().getServer());
							ctx.getSource().sendSuccess(() -> Component.translatable("message.lasttrain.curse_status",
									day, CurseManager.days(), data.triggered, data.finished), false);
							return 1;
						}))
						.then(Commands.literal("trigger").executes(ctx -> {
							CurseManager.trigger(ctx.getSource().getServer());
							return 1;
						}))
						.then(Commands.literal("reset").executes(ctx -> {
							CurseManager.reset(ctx.getSource().getServer());
							ctx.getSource().sendSuccess(() -> Component.translatable("message.lasttrain.curse_reset"), false);
							return 1;
						}))));
	}
}
