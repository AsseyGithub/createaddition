package com.mrh0.createaddition.blocks.portable_energy_interface;

import com.mrh0.createaddition.config.Config;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import io.github.fabricators_of_create.porting_lib.transfer.callbacks.TransactionSuccessCallback;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;
import team.reborn.energy.api.EnergyStorage;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PortableEnergyManager {

	private static final Map<UUID, EnergyStorageHolder> CONTRAPTIONS = new ConcurrentHashMap<>();

	private static int tick = 0;
	public static void tick() {

		CONTRAPTIONS.keySet().iterator().forEachRemaining(contraption -> {
			// It's hard to find out when a contraption is removed...
			// It might be easier and cleaner to just use mixin, but it works.
			if (System.currentTimeMillis() - CONTRAPTIONS.get(contraption).heartbeat > 5_000)
				CONTRAPTIONS.remove(contraption);
		});
	}

	public static void track(MovementContext context) {
		Contraption contraption = context.contraption;
		EnergyStorageHolder holder = CONTRAPTIONS.get(contraption.entity.getUUID());
		if (holder == null) {
			holder = new EnergyStorageHolder();
			CONTRAPTIONS.put(contraption.entity.getUUID(), holder);
		}
		holder.addEnergySource(context.blockEntityData, context.localPos);
	}

	public static void untrack(MovementContext context) {
		EnergyStorageHolder holder = CONTRAPTIONS.remove(context.contraption.entity.getUUID());
		if (holder == null) return;
		holder.removed = true;
	}

	public static @Nullable EnergyStorage get(Contraption contraption) {
		if (contraption.entity == null) return null;
		return CONTRAPTIONS.get(contraption.entity.getUUID());
	}

	@SuppressWarnings("UnstableApiUsage")
	public static class EnergyStorageHolder extends SnapshotParticipant<Long> implements EnergyStorage {

		private long energy = 0;
		private long capacity = 0;
		private long heartbeat;
		private boolean removed = false;

		private final long maxReceive = Config.ACCUMULATOR_MAX_INPUT.get();
		private final long maxExtract = Config.ACCUMULATOR_MAX_OUTPUT.get();
		private final Map<BlockPos, EnergyData> energyHolders = new HashMap<>();

		public EnergyStorageHolder() {
			this.heartbeat = System.currentTimeMillis();
		}

		protected void addEnergySource(CompoundTag nbt, BlockPos pos) {
			// Heartbeat
			this.heartbeat = System.currentTimeMillis();

			// Make sure this is a controller.
			if (!nbt.contains("EnergyContent")) return;
			// Check for duplicates.
			if (this.energyHolders.containsKey(pos)) return;
			EnergyData data = new EnergyData(nbt);
			this.energy += data.energy;
			this.capacity += data.capacity;
			this.energyHolders.put(pos, data);
		}

		@Override
		protected Long createSnapshot() {
			return this.energy;
		}

		@Override
		protected void readSnapshot(Long snapshot) {
			this.energy = snapshot;
		}

		@Override
		public long insert(long maxReceive, TransactionContext ctx) {
			if (!this.supportsInsertion()) return 0;
			long energyReceived = Math.min(this.capacity - this.energy, Math.min(this.maxReceive, maxReceive));
			updateSnapshots(ctx);
			this.energy += energyReceived;
			// Store NBT
			TransactionSuccessCallback.onSuccess(ctx, () -> {
				long energyLeft = energyReceived;
				for (EnergyData data : energyHolders.values()) {
					energyLeft -= data.receiveEnergy(energyLeft);
					if (energyLeft <= 0) break; // It shouldn't be possible to go below 0, but just in case.
				}
				// In case we didn't store all the energy.
				if (energyLeft > 0) throw new IllegalStateException("Failed to store energy.");
			});
			return energyReceived;
		}

		@Override
		public long extract(long maxExtract, TransactionContext ctx) {
			if (!this.supportsExtraction()) return 0;
			long energyExtracted = Math.min(this.energy, Math.min(this.maxExtract, maxExtract));
			updateSnapshots(ctx);
			this.energy -= energyExtracted;
			// Store NBT
			TransactionSuccessCallback.onSuccess(ctx, () -> {
				long energyLeft = energyExtracted;
				for (EnergyData data : energyHolders.values()) {
					energyLeft -= data.extractEnergy(energyLeft);
					if (energyLeft <= 0) break; // It shouldn't be possible to go below 0, but just in case.
				}
				// In case we didn't store all the energy.
				if (energyLeft > 0) throw new IllegalStateException("Failed to store energy.");
			});
			return energyExtracted;
		}

		@Override
		public long getAmount() {
			return this.energy;
		}

		@Override
		public long getCapacity() {
			return this.capacity;
		}

		@Override
		public boolean supportsExtraction() {
			return !this.removed;
		}

		@Override
		public boolean supportsInsertion() {
			return !this.removed;
		}
	}

	public static class EnergyData {

		private final CompoundTag nbt;
		private final long capacity;
		private long energy;

		public EnergyData(CompoundTag nbt) {
			CompoundTag energyContent = (CompoundTag)nbt.get("EnergyContent");
			if (energyContent == null) throw new IllegalArgumentException("EnergyContent is null");
			this.nbt = nbt;
			this.capacity = nbt.getLong("EnergyCapacity");
			this.energy = energyContent.getLong("energy");
		}

		public long receiveEnergy(long energy) {
			long energyReceived = Math.min(this.capacity - this.energy, energy);
			if (energyReceived == 0) return 0; // No need to save if nothing changed.
			this.energy += energyReceived;

			// Save
			CompoundTag energyContent = (CompoundTag)nbt.get("EnergyContent");
			energyContent.putLong("energy", this.energy);

			return energyReceived;
		}

		public long extractEnergy(long energy) {
			long energyRemoved = Math.min(this.energy, energy);
			if (energyRemoved == 0) return 0; // No need to save if nothing changed.
			this.energy -= energyRemoved;

			// Save
			CompoundTag energyContent = (CompoundTag)nbt.get("EnergyContent");
			energyContent.putLong("energy", this.energy);

			return energyRemoved;
		}
	}

}
