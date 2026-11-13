package io.github.andrix03.retroreplay.compat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;

import net.minecraft.block.Block;
import net.minecraft.entity.EnderCrystalEntity;
import net.minecraft.entity.EnderEyeEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.FishingBobberEntity;
import net.minecraft.entity.PrimedTntEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.EggEntity;
import net.minecraft.entity.projectile.EnderPearlEntity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.entity.projectile.PotionEntity;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.entity.projectile.SnowballEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.entity.vehicle.MinecartEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

/**
 * The packets of protocol 22 (1.0.0) that differ between the versions, for the native
 * singleplayer recording (NativeRecorder): each as id byte + payload, read the way this
 * version's client reads them.
 */
public final class NativeProtocol {

	private NativeProtocol() {
	}

	/** Login 0x01: entity id, username, seed, game mode, dimension, difficulty, height, max players. */
	public static byte[] login(int entityId, World world, int gameMode, int difficulty) throws IOException {
		Bytes out = new Bytes(0x01);
		out.writeInt(entityId);
		out.string("");
		out.writeLong(world.getSeed());
		out.writeInt(gameMode);
		out.writeByte(world.dimension.id);
		out.writeByte(difficulty);
		out.writeByte(world.height);
		out.writeByte(1);
		return out.bytes();
	}

	/** Respawn 0x09: dimension, difficulty, game mode, height, seed. */
	public static byte[] respawn(World world, int gameMode, int difficulty) throws IOException {
		Bytes out = new Bytes(0x09);
		out.writeByte(world.dimension.id);
		out.writeByte(difficulty);
		out.writeByte(gameMode);
		out.writeShort(world.height);
		out.writeLong(world.getSeed());
		return out.bytes();
	}

	/** The whole chunk 0x33: before 1.2 the client copies the chunk's arrays as they are. */
	public static byte[] chunk(WorldChunk chunk) throws IOException {
		int height = chunk.world.height;
		byte[] raw = new byte[chunk.blocks.length + chunk.blockMetadata.data.length + chunk.blockLight.data.length + chunk.skyLight.data.length];
		int at = 0;
		System.arraycopy(chunk.blocks, 0, raw, at, chunk.blocks.length);
		at += chunk.blocks.length;
		System.arraycopy(chunk.blockMetadata.data, 0, raw, at, chunk.blockMetadata.data.length);
		at += chunk.blockMetadata.data.length;
		System.arraycopy(chunk.blockLight.data, 0, raw, at, chunk.blockLight.data.length);
		at += chunk.blockLight.data.length;
		System.arraycopy(chunk.skyLight.data, 0, raw, at, chunk.skyLight.data.length);
		byte[] packed = deflate(raw);
		Bytes out = new Bytes(0x33);
		out.writeInt(chunk.chunkX << 4);
		out.writeShort(0);
		out.writeInt(chunk.chunkZ << 4);
		out.writeByte(15);
		out.writeByte(height - 1);
		out.writeByte(15);
		out.writeInt(packed.length);
		out.write(packed);
		return out.bytes();
	}

	/** No block entity packet before 1.2.5 (only signs, the same in every version). */
	public static byte[] blockEntity(Object blockEntity) {
		return null;
	}

	/** No separate head turn before 1.2. */
	public static byte[] headYaw(Entity entity) {
		return null;
	}

	/**
	 * What the add-entity packet 0x17 carries for this entity: type, data, and the velocity
	 * (x 8000) the client reads when data is above 0; null when it is no such entity.
	 */
	public static int[] vehicle(Entity entity) {
		int type;
		int data = 0;
		double vx = entity.velocityX;
		double vy = entity.velocityY;
		double vz = entity.velocityZ;
		if (entity instanceof MinecartEntity) {
			type = 10 + ((MinecartEntity) entity).type;
		} else if (entity instanceof BoatEntity) {
			type = 1;
		} else if (entity instanceof FishingBobberEntity) {
			type = 90;
			data = ((FishingBobberEntity) entity).thrower == null ? 1 : ((FishingBobberEntity) entity).thrower.networkId;
		} else if (entity instanceof ArrowEntity) {
			type = 60;
			data = ((ArrowEntity) entity).shooter == null ? 1 : ((ArrowEntity) entity).shooter.networkId;
		} else if (entity instanceof SnowballEntity) {
			type = 61;
		} else if (entity instanceof EnderPearlEntity) {
			type = 65;
		} else if (entity instanceof EnderEyeEntity) {
			type = 72;
		} else if (entity instanceof FireballEntity) {
			FireballEntity fireball = (FireballEntity) entity;
			type = entity instanceof SmallFireballEntity ? 64 : 63;
			data = fireball.shooter == null ? 1 : fireball.shooter.networkId;
			vx = fireball.accelerationX;
			vy = fireball.accelerationY;
			vz = fireball.accelerationZ;
		} else if (entity instanceof EggEntity) {
			type = 62;
		} else if (entity instanceof PotionEntity) {
			type = 73;
			data = ((PotionEntity) entity).getPotion();
		} else if (entity instanceof PrimedTntEntity) {
			type = 50;
		} else if (entity instanceof EnderCrystalEntity) {
			type = 51;
		} else if (entity instanceof FallingBlockEntity) {
			int block = ((FallingBlockEntity) entity).block;
			type = block == Block.GRAVEL.id ? 71 : block == Block.DRAGON_EGG.id ? 74 : 70;
		} else {
			return null;
		}
		return new int[] { type, data, velocity(vx), velocity(vy), velocity(vz) };
	}

	private static int velocity(double v) {
		return (int) (Math.max(-3.9, Math.min(3.9, v)) * 8000.0);
	}

	static byte[] deflate(byte[] raw) {
		Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
		try {
			deflater.setInput(raw);
			deflater.finish();
			ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 4);
			byte[] buffer = new byte[8192];
			while (!deflater.finished()) {
				out.write(buffer, 0, deflater.deflate(buffer));
			}
			return out.toByteArray();
		} finally {
			deflater.end();
		}
	}

	/** A packet being written: id byte first. */
	static final class Bytes extends DataOutputStream {
		Bytes(int id) throws IOException {
			super(new ByteArrayOutputStream());
			writeByte(id);
		}

		/** Packet.writeString: short length, UTF-16 chars. */
		void string(String s) throws IOException {
			writeShort(s.length());
			writeChars(s);
		}

		byte[] bytes() {
			return ((ByteArrayOutputStream) out).toByteArray();
		}
	}
}
