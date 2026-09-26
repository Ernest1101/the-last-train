package com.lasttrain.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;

import java.util.ArrayList;
import java.util.List;

/** The red diary: "You have 3 days". A new entry appears each day. */
public final class DiaryScreen {
	private DiaryScreen() {
	}

	/** A diary page followed by that day's task and whether it's done. */
	private static Component page(String key, int task) {
		boolean done = (CurseClient.tasks & (1 << task)) != 0;
		return Component.translatable(key).append("\n\n")
				.append(Component.translatable("diary.lasttrain.task" + (task + 1)))
				.append("\n")
				.append(Component.translatable(done ? "diary.lasttrain.task_done" : "diary.lasttrain.task_open"));
	}

	/** One of the family's notes. */
	public static void openNote(int number) {
		Component text = Component.translatable("note.lasttrain." + number);
		Minecraft.getInstance().setScreen(new BookViewScreen(new BookViewScreen.BookAccess() {
			@Override
			public int getPageCount() {
				return 1;
			}

			@Override
			public FormattedText getPageRaw(int index) {
				return text;
			}
		}));
	}

	public static void open() {
		int day = Math.max(1, CurseClient.day);
		List<Component> pages = new ArrayList<>();
		pages.add(page("diary.lasttrain.page1", 0));
		if (day >= 2) {
			pages.add(page("diary.lasttrain.page2", 1));
		}
		if (day >= Math.max(3, CurseClient.totalDays)) {
			pages.add(page("diary.lasttrain.page3", 2));
		}
		Minecraft.getInstance().setScreen(new BookViewScreen(new BookViewScreen.BookAccess() {
			@Override
			public int getPageCount() {
				return pages.size();
			}

			@Override
			public FormattedText getPageRaw(int index) {
				return pages.get(index);
			}
		}));
	}
}
