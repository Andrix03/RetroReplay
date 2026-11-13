package io.github.andrix03.retroreplay.recording;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldEventListener;

/**
 * Listens to a singleplayer world for NativeRecorder: only the world events a server sends
 * (doors, block breaking, records, smoke); block changes come from the chunk itself
 * (NativeChunkMixin) and entities from the world's list. No @Override: 1.2.5 has one more method.
 */
public final class NativeWorldListener implements WorldEventListener {

	private final World world;

	NativeWorldListener(World world) {
		this.world = world;
	}

	public void doEvent(PlayerEntity source, int type, int x, int y, int z, int data) {
		NativeRecorder.worldEvent(world, type, x, y, z, data);
	}

	public void notifyBlockChanged(int x, int y, int z) {
	}

	public void notifyLightChanged(int x, int y, int z) {
	}

	public void notifyRegionChanged(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
	}

	public void playSound(String name, double x, double y, double z, float volume, float pitch) {
	}

	public void addParticle(String type, double x, double y, double z, double velocityX, double velocityY, double velocityZ) {
	}

	public void notifyEntityAdded(Entity entity) {
	}

	public void notifyEntityRemoved(Entity entity) {
	}

	public void playRecordMusic(String record, int x, int y, int z) {
	}

	public void notifyBlockEntityChanged(int x, int y, int z, BlockEntity blockEntity) {
	}
}
