package io.github.andrix03.retroreplay.compat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;

import net.minecraft.block.Block;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.entity.EnderCrystalEntity;
import net.minecraft.entity.EnderEyeEntity;
import net.minecraft.entity.Entities;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.FishingBobberEntity;
import net.minecraft.entity.PrimedTntEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.EggEntity;
import net.minecraft.entity.projectile.EnderPearlEntity;
import net.minecraft.entity.projectile.ExperienceBottleEntity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.entity.projectile.PotionEntity;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.entity.projectile.SnowballEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.entity.vehicle.MinecartEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkNibbleStorage;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.WorldChunkSection;

/**
 * The packets of protocol 29 (1.2.5) that differ between the versions, for the native
 * singleplayer recording (NativeRecorder): each as id byte + payload, read the way this
 * version's client reads them.
 */
public final class NativeProtocol {

	private NativeProtocol() {
	}

	/** Login 0x01: entity id, username, generator type, game mode, dimension, difficulty, height, max players. */
	public static byte[] login(int entityId, World world, int gameMode, int difficulty) throws IOException {
		Bytes out = new Bytes(0x01);
		out.writeInt(entityId);
		out.string("");
		out.string(world.getData().getGeneratorType().getKey());
		out.writeInt(gameMode);
		out.writeInt(world.dimension.id);
		out.writeByte(difficulty);
		out.writeByte(256); // the height is fixed from 1.2: the client ignores it
		out.writeByte(1);
		return out.bytes();
	}

	/** Respawn 0x09: dimension, difficulty, game mode, height, generator type. */
	public static byte[] respawn(World world, int gameMode, int difficulty) throws IOException {
		Bytes out = new Bytes(0x09);
		out.writeInt(world.dimension.id);
		out.writeByte(difficulty);
		out.writeByte(gameMode);
		out.writeShort(256);
		out.string(world.getData().getGeneratorType().getKey());
		return out.bytes();
	}

	/**
	 * The whole chunk 0x33 in sections of 16 (WorldChunk.update reads it): the block ids of
	 * every section sent, then their metadata, block light, sky light, the upper block ids of
	 * the sections that have them, and the biomes.
	 */
	public static byte[] chunk(WorldChunk chunk) throws IOException {
		WorldChunkSection[] sections = chunk.getSections();
		int withData = 0;
		int withUpper = 0;
		for (int i = 0; i < sections.length; i++) {
			if (sections[i] != null && !sections[i].isEmpty()) {
				withData |= 1 << i;
				if (sections[i].getBlockIdsUpperStorage() != null) {
					withUpper |= 1 << i;
				}
			}
		}
		ByteArrayOutputStream raw = new ByteArrayOutputStream(81920);
		for (int i = 0; i < sections.length; i++) {
			if ((withData & 1 << i) != 0) {
				raw.write(sections[i].getBlockIdsLower());
			}
		}
		for (int part = 0; part < 3; part++) {
			for (int i = 0; i < sections.length; i++) {
				if ((withData & 1 << i) != 0) {
					ChunkNibbleStorage nibbles = part == 0 ? sections[i].getBlockMetadataStorage()
						: part == 1 ? sections[i].getBlockLightStorage() : sections[i].getSkyLightStorage();
					raw.write(nibbles.data);
				}
			}
		}
		for (int i = 0; i < sections.length; i++) {
			if ((withUpper & 1 << i) != 0) {
				raw.write(sections[i].getBlockIdsUpperStorage().data);
			}
		}
		raw.write(chunk.getBiomes());
		byte[] packed = deflate(raw.toByteArray());
		Bytes out = new Bytes(0x33);
		out.writeInt(chunk.chunkX);
		out.writeInt(chunk.chunkZ);
		out.writeBoolean(true);
		out.writeShort(withData);
		out.writeShort(withUpper);
		out.writeInt(packed.length);
		out.writeInt(0); // unused
		out.write(packed);
		return out.bytes();
	}

	/** Block entity 0x84: what a mob spawner spawns (type 1), as the server tells it from 1.2. */
	public static byte[] blockEntity(Object blockEntity) throws IOException {
		if (!(blockEntity instanceof MobSpawnerBlockEntity)) {
			return null;
		}
		MobSpawnerBlockEntity spawner = (MobSpawnerBlockEntity) blockEntity;
		Entity sample = Entities.createSilently(spawner.getType(), spawner.world);
		if (sample == null) {
			return null;
		}
		Bytes out = new Bytes(0x84);
		out.writeInt(spawner.x);
		out.writeShort(spawner.y);
		out.writeInt(spawner.z);
		out.writeByte(1);
		out.writeInt(Entities.getId(sample));
		out.writeInt(0);
		out.writeInt(0);
		return out.bytes();
	}

	/** Head turn 0x23, separate from the body from 1.2. */
	public static byte[] headYaw(Entity entity) throws IOException {
		if (!(entity instanceof MobEntity)) {
			return null;
		}
		Bytes out = new Bytes(0x23);
		out.writeInt(entity.networkId);
		out.writeByte((byte) (((MobEntity) entity).headYaw * 256.0F / 360.0F));
		return out.bytes();
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
		} else if (entity instanceof ExperienceBottleEntity) {
			type = 75;
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
