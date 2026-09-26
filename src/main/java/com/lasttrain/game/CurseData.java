package com.lasttrain.game;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

/** Per-world state of the three-day curse, stored with the overworld. */
public class CurseData extends SavedData {
	public static final String NAME = "lasttrain_curse";

	/** Overworld day time when the countdown started, -1 before anyone joined. */
	public long startTime = -1;
	public boolean triggered;
	public boolean finished;
	/** Inventories and respawn points of players taken into the nightmare. */
	public final Map<UUID, CompoundTag> stash = new HashMap<>();
	/** Players who already got the red diary. */
	public final Set<UUID> diaryGiven = new HashSet<>();
	/** Bitmask per player of the diary's daily tasks done (bit 0 = day one). */
	public final Map<UUID, Integer> tasks = new HashMap<>();
	/** The curse was lifted with the family photo: it never comes back in this world. */
	public boolean lifted;

	public static CurseData load(CompoundTag tag) {
		CurseData data = new CurseData();
		data.startTime = tag.getLong("StartTime");
		data.triggered = tag.getBoolean("Triggered");
		data.finished = tag.getBoolean("Finished");
		CompoundTag stash = tag.getCompound("Stash");
		for (String key : stash.getAllKeys()) {
			data.stash.put(UUID.fromString(key), stash.getCompound(key));
		}
		CompoundTag tasks = tag.getCompound("Tasks");
		for (String key : tasks.getAllKeys()) {
			data.tasks.put(UUID.fromString(key), tasks.getInt(key));
		}
		data.lifted = tag.getBoolean("Lifted");
		for (Tag uuid : tag.getList("Diary", Tag.TAG_INT_ARRAY)) {
			data.diaryGiven.add(NbtUtils.loadUUID(uuid));
		}
		return data;
	}

	@Override
	public CompoundTag save(CompoundTag tag) {
		tag.putLong("StartTime", this.startTime);
		tag.putBoolean("Triggered", this.triggered);
		tag.putBoolean("Finished", this.finished);
		CompoundTag stash = new CompoundTag();
		this.stash.forEach((uuid, playerTag) -> stash.put(uuid.toString(), playerTag));
		tag.put("Stash", stash);
		ListTag diary = new ListTag();
		this.diaryGiven.forEach(uuid -> diary.add(NbtUtils.createUUID(uuid)));
		tag.put("Diary", diary);
		CompoundTag tasks = new CompoundTag();
		this.tasks.forEach((uuid, bits) -> tasks.putInt(uuid.toString(), bits));
		tag.put("Tasks", tasks);
		tag.putBoolean("Lifted", this.lifted);
		return tag;
	}
}
