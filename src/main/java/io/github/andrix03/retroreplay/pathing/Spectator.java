package io.github.andrix03.retroreplay.pathing;

import java.util.ArrayList;
import java.util.List;

import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.replay.ReplayHandler;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;

/**
 * Replay Mod's spectating: the camera looks through an entity's eyes. The entity is kept by its
 * network id and found again every tick, since a seek rebuilds the world with new entity objects.
 * The free camera waits where the entity is, so leaving puts it there.
 */
public final class Spectator {

	private static int target = CameraPath.Position.NO_ENTITY;

	private Spectator() {
	}

	public static boolean isSpectating() {
		return target != CameraPath.Position.NO_ENTITY;
	}

	public static int target() {
		return target;
	}

	public static void spectate(int entityId) {
		target = entityId;
		follow();
	}

	/** Back to the free camera, at the spectated entity's place. */
	public static void stop() {
		if (target == CameraPath.Position.NO_ENTITY) {
			return;
		}
		Entity entity = find(target);
		target = CameraPath.Position.NO_ENTITY;
		Mc.spectate(null);
		if (entity != null) {
			PathPlayer.placeCamera(positionOf(entity, CameraPath.Position.NO_ENTITY));
		}
	}

	/** Stops spectating without moving the free camera (a path places it next). */
	public static void clear() {
		target = CameraPath.Position.NO_ENTITY;
		Mc.spectate(null);
	}

	/** Every client tick in a replay (and every path frame): keeps the camera on the target. */
	public static void follow() {
		Minecraft minecraft = Mc.minecraft();
		if (ReplayHandler.get() == null || minecraft.world == null) {
			return;
		}
		if (target == CameraPath.Position.NO_ENTITY) {
			if (Mc.cameraEntity() != minecraft.player) {
				Mc.spectate(null);
			}
			return;
		}
		Entity entity = find(target);
		if (entity == null || !Mc.spectate(entity)) {
			Mc.spectate(null); // not in the world (yet): the free camera stands in
			return;
		}
		// the free camera rides along, so chunks load and sound plays around the entity
		minecraft.player.setPositionAndAngles(entity.x, entity.y - minecraft.player.eyeHeight, entity.z, entity.yaw, entity.pitch);
	}

	public static Entity find(int entityId) {
		Minecraft minecraft = Mc.minecraft();
		if (minecraft.world == null) {
			return null;
		}
		for (Object entity : minecraft.world.entities) {
			if (entity != minecraft.player && Mc.entityId(entity) == entityId) {
				return (Entity) entity;
			}
		}
		return null;
	}

	/** Players in the world except the camera, for the player overview. */
	public static List<Entity> players() {
		List<Entity> players = new ArrayList<>();
		Minecraft minecraft = Mc.minecraft();
		if (minecraft.world != null) {
			for (Object entity : minecraft.world.entities) {
				if (entity != minecraft.player && Mc.isPlayer(entity)) {
					players.add((Entity) entity);
				}
			}
		}
		return players;
	}

	/** An entity's place as a keyframe position (feet y like the free camera's keyframes). */
	public static CameraPath.Position positionOf(Entity entity, int spectated) {
		double feet = entity.y - Mc.minecraft().player.eyeHeight;
		return new CameraPath.Position(entity.x, feet, entity.z, entity.yaw, entity.pitch, 0, spectated);
	}
}
