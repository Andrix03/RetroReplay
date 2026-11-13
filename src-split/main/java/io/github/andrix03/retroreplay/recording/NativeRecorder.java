package io.github.andrix03.retroreplay.recording;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.andrix03.retroreplay.RetroReplay;
import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.compat.NativeProtocol;
import io.github.andrix03.retroreplay.mixin.WorldAccessor;
import io.github.andrix03.retroreplay.replay.ReplayHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.PaintingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.network.packet.AddExperienceOrbPacket;
import net.minecraft.network.packet.AddItemPacket;
import net.minecraft.network.packet.AddMobPacket;
import net.minecraft.network.packet.AddPaintingPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Records a vanilla singleplayer world without any server: before 1.3 singleplayer has no
 * packets at all, so every client tick this class writes the ones a server of this version
 * would have sent another client watching the player (its EntityTracker and PlayerManager):
 * chunks around the player, block changes, entities with their moves and data, and the world's
 * events. The replay side reads them like any multiplayer recording; LocalPlayerRecorder adds
 * the player itself, as it does for multiplayer.
 *
 * It only reads the world: chunks are taken only when the game has them loaded already, so
 * the recording never loads, generates or changes anything.
 */
public final class NativeRecorder {

	/** The old way, kept for comparisons: singleplayer on a hidden local server (RETROREPLAY_LOCAL_SERVER). */
	public static final boolean LOCAL_SERVER = System.getenv("RETROREPLAY_LOCAL_SERVER") != null
		|| Boolean.getBoolean("retroreplay.localServer");

	/** Dev comparisons: vanilla singleplayer with the mod, recording nothing (RETROREPLAY_NO_RECORD). */
	private static final boolean NO_RECORD = System.getenv("RETROREPLAY_NO_RECORD") != null;

	/** Chunks around the player, like a server's view distance (10, the default). */
	private static final int RADIUS = 10;
	/** Chunks sent per tick after the first one (the first sends all the loaded ones). */
	private static final int CHUNKS_PER_TICK = 16;
	/** More block changes than this in one chunk and tick: the whole chunk goes again. */
	private static final int CHANGES_PER_CHUNK = 64;
	/** A full position now and then, as the server does, so small rounding errors never pile up. */
	private static final int TELEPORT_EVERY = 400;

	private static boolean started;
	private static World world;
	private static Object player;
	private static int ticks;
	private static boolean raining;
	private static int playerVehicle = -1;
	private static final Set<Long> sentChunks = new HashSet<>();
	private static final Map<Long, Integer> changesPerChunk = new HashMap<>();
	private static final Set<Long> changedBlocks = new LinkedHashSet<>();
	private static final Map<Integer, Tracked> tracked = new LinkedHashMap<>();
	private static final Set<Integer> untracked = new HashSet<>();
	private static final Map<Long, String> signs = new HashMap<>();
	private static final Map<World, Boolean> listening = new WeakHashMap<>();

	private NativeRecorder() {
	}

	/** Recording singleplayer the native way: no local server. */
	public static boolean isActive() {
		return started;
	}

	/** Every client tick, before LocalPlayerRecorder (the login comes before the player). */
	public static void tick() {
		Minecraft minecraft = Mc.minecraft();
		boolean want = !LOCAL_SERVER && !NO_RECORD && minecraft.world != null && minecraft.player != null && !minecraft.isMultiplayer()
			&& !minecraft.world.isMultiplayer && !ReplayHandler.isActive();
		if (!want) {
			if (started) {
				reset();
			}
			return;
		}
		try {
			if (!started) {
				begin(minecraft);
			} else if (minecraft.world != world || minecraft.player != player) {
				respawn(minecraft);
			}
			if (!Recorder.isRecording()) {
				return;
			}
			update(minecraft, CHUNKS_PER_TICK);
		} catch (IOException | RuntimeException e) {
			RetroReplay.LOGGER.error("Native recording failed, stopping it", e);
			Recorder.stop();
			reset();
			started = true; // not again for this world
		}
	}

	/** The world is closing: what changed in its last tick still goes in, before Recorder.stop. */
	public static void finish() {
		if (started && world != null && Recorder.isRecording()) {
			try {
				sendBlockChanges();
			} catch (IOException | RuntimeException e) {
				RetroReplay.LOGGER.error("Native recording: last block changes lost", e);
			}
		}
	}

	/** The world closed: the recording is over (Recorder.stop is called with it). */
	public static void reset() {
		started = false;
		world = null;
		player = null;
		clearWorldState();
	}

	private static void clearWorldState() {
		sentChunks.clear();
		changesPerChunk.clear();
		changedBlocks.clear();
		tracked.clear();
		untracked.clear();
		signs.clear();
		playerVehicle = -1;
	}

	private static void begin(Minecraft minecraft) throws IOException {
		started = true;
		world = minecraft.world;
		player = minecraft.player;
		ticks = 0;
		clearWorldState();
		Recorder.start(true, "");
		listen(world);
		Recorder.recordRaw(NativeProtocol.login(minecraft.player.networkId, world, gameMode(minecraft), world.difficulty));
		Vec3i spawn = world.getSpawnPoint();
		Recorder.recordRaw(bytes(0x06, out -> {
			out.writeInt(spawn.x);
			out.writeInt(spawn.y);
			out.writeInt(spawn.z);
		}));
		sendTime();
		raining = world.isRaining();
		if (raining) {
			Recorder.recordRaw(new byte[] { 0x46, 1, 0 });
		}
		update(minecraft, Integer.MAX_VALUE);
		sendPosition(minecraft);
		RetroReplay.LOGGER.info("Native singleplayer recording: " + sentChunks.size() + " chunks, " + tracked.size() + " entities");
	}

	/** Another dimension, or a new player after death: the client starts over, like after a server's respawn. */
	private static void respawn(Minecraft minecraft) throws IOException {
		world = minecraft.world;
		player = minecraft.player;
		clearWorldState();
		listen(world);
		Recorder.recordRaw(NativeProtocol.respawn(world, gameMode(minecraft), world.difficulty));
		LocalPlayerRecorder.reset(); // the replay's world starts empty: the player is added again, even the same one
		raining = world.isRaining();
		Recorder.recordRaw(new byte[] { 0x46, (byte) (raining ? 1 : 2), 0 });
		update(minecraft, Integer.MAX_VALUE);
		sendPosition(minecraft);
	}

	private static int gameMode(Minecraft minecraft) {
		return minecraft.interactionManager != null && minecraft.interactionManager.hasCreativeInventory() ? 1 : 0;
	}

	private static void listen(World w) {
		if (listening.put(w, Boolean.TRUE) == null) {
			w.addEventListener(new NativeWorldListener(w));
		}
	}

	private static void update(Minecraft minecraft, int chunkBudget) throws IOException {
		ticks++;
		sendBlockChanges();
		sendChunks(minecraft, chunkBudget);
		sendEntities(minecraft);
		if (ticks % 20 == 0) {
			sendTime();
			sendChangedSigns();
		}
		boolean rain = world.isRaining();
		if (rain != raining) {
			raining = rain;
			Recorder.recordRaw(new byte[] { 0x46, (byte) (rain ? 1 : 2), 0 });
		}
	}

	// ---- chunks and blocks

	static long chunkKey(int chunkX, int chunkZ) {
		return (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
	}

	private static long blockKey(int x, int y, int z) {
		return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | y & 0xFFF;
	}

	private static void sendChunks(Minecraft minecraft, int budget) throws IOException {
		int centerX = floor(minecraft.player.x) >> 4;
		int centerZ = floor(minecraft.player.z) >> 4;
		// gone out of range (or unloaded by the game): the client forgets them
		Iterator<Long> sent = sentChunks.iterator();
		while (sent.hasNext()) {
			long key = sent.next();
			int chunkX = (int) (key >> 32);
			int chunkZ = (int) key;
			if (Math.abs(chunkX - centerX) > RADIUS + 1 || Math.abs(chunkZ - centerZ) > RADIUS + 1 || !isLoaded(chunkX, chunkZ)) {
				sent.remove();
				Recorder.recordRaw(preChunk(chunkX, chunkZ, false));
			}
		}
		// nearest first, like the server
		for (int ring = 0; ring <= RADIUS && budget > 0; ring++) {
			for (int dx = -ring; dx <= ring && budget > 0; dx++) {
				for (int dz = -ring; dz <= ring && budget > 0; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
						continue;
					}
					int chunkX = centerX + dx;
					int chunkZ = centerZ + dz;
					long key = chunkKey(chunkX, chunkZ);
					if (sentChunks.contains(key) || !isLoaded(chunkX, chunkZ)) {
						continue;
					}
					WorldChunk chunk = world.getChunkAt(chunkX, chunkZ);
					if (!chunk.terrainPopulated) {
						continue; // its features come with the neighbours: sent once they are there
					}
					sendChunk(chunk, true);
					sentChunks.add(key);
					budget--;
				}
			}
		}
	}

	/** Only what the game has loaded: asking the world for any other chunk would load or generate it. */
	private static boolean isLoaded(int chunkX, int chunkZ) {
		return ((WorldAccessor) world).retroreplay$getChunkSource().hasChunk(chunkX, chunkZ);
	}

	private static void sendChunk(WorldChunk chunk, boolean load) throws IOException {
		if (load) {
			Recorder.recordRaw(preChunk(chunk.chunkX, chunk.chunkZ, true));
		}
		Recorder.recordRaw(NativeProtocol.chunk(chunk));
		for (Object value : chunk.blockEntities.values()) {
			BlockEntity blockEntity = (BlockEntity) value;
			if (blockEntity instanceof SignBlockEntity) {
				sendSign((SignBlockEntity) blockEntity);
			} else {
				byte[] packet = NativeProtocol.blockEntity(blockEntity);
				if (packet != null) {
					Recorder.recordRaw(packet);
				}
			}
		}
	}

	private static byte[] preChunk(int chunkX, int chunkZ, boolean load) throws IOException {
		return bytes(0x32, out -> {
			out.writeInt(chunkX);
			out.writeInt(chunkZ);
			out.writeBoolean(load);
		});
	}

	/** From WorldChunk's block setters (NativeChunkMixin): the block or its metadata changed. */
	public static void blockChanged(WorldChunk chunk, int localX, int y, int localZ) {
		if (!started || chunk.world != world) {
			return;
		}
		long key = chunkKey(chunk.chunkX, chunk.chunkZ);
		if (!sentChunks.contains(key)) {
			return; // the chunk itself comes later, with the change in it
		}
		if (changedBlocks.add(blockKey(chunk.chunkX << 4 | localX, y, chunk.chunkZ << 4 | localZ))) {
			changesPerChunk.merge(key, 1, Integer::sum);
		}
	}

	private static void sendBlockChanges() throws IOException {
		if (changedBlocks.isEmpty()) {
			return;
		}
		Set<Long> resent = new HashSet<>();
		for (Map.Entry<Long, Integer> entry : changesPerChunk.entrySet()) {
			long key = entry.getKey();
			if (entry.getValue() > CHANGES_PER_CHUNK && sentChunks.contains(key)) {
				sendChunk(world.getChunkAt((int) (key >> 32), (int) (long) key), false);
				resent.add(key);
			}
		}
		for (long block : changedBlocks) {
			int x = (int) (block >> 38);
			int z = (int) (block << 26 >> 38);
			int y = (int) (block & 0xFFF);
			long chunk = chunkKey(x >> 4, z >> 4);
			if (resent.contains(chunk) || !sentChunks.contains(chunk)) {
				continue;
			}
			int id = world.getBlock(x, y, z);
			int metadata = world.getBlockMetadata(x, y, z);
			Recorder.recordRaw(bytes(0x35, out -> {
				out.writeInt(x);
				out.writeByte(y);
				out.writeInt(z);
				out.writeByte(id);
				out.writeByte(metadata);
			}));
		}
		changedBlocks.clear();
		changesPerChunk.clear();
	}

	private static void sendSign(SignBlockEntity sign) throws IOException {
		signs.put(blockKey(sign.x, sign.y, sign.z), String.join("\n", sign.lines));
		Recorder.recordRaw(bytes(0x82, out -> {
			out.writeInt(sign.x);
			out.writeShort(sign.y);
			out.writeInt(sign.z);
			for (int i = 0; i < 4; i++) {
				String line = sign.lines[i] == null ? "" : sign.lines[i];
				out.writeShort(line.length());
				out.writeChars(line);
			}
		}));
	}

	/** Sign text is set by its screen, with no block change: looked at once a second. */
	private static void sendChangedSigns() throws IOException {
		for (Object value : new ArrayList<Object>(world.blockEntities)) {
			if (value instanceof SignBlockEntity) {
				SignBlockEntity sign = (SignBlockEntity) value;
				if (!sentChunks.contains(chunkKey(sign.x >> 4, sign.z >> 4))) {
					continue;
				}
				String text = String.join("\n", sign.lines);
				if (!text.equals(signs.get(blockKey(sign.x, sign.y, sign.z)))) {
					sendSign(sign);
				}
			}
		}
	}

	private static void sendTime() throws IOException {
		long time = world.getTime();
		Recorder.recordRaw(bytes(0x04, out -> out.writeLong(time)));
	}

	private static void sendPosition(Minecraft minecraft) throws IOException {
		// position 0x0D: x, then the y the client sets (its eyes), the feet, z, angles, on ground
		double x = minecraft.player.x;
		double eyes = minecraft.player.y;
		double feet = minecraft.player.shape.minY;
		double z = minecraft.player.z;
		float yaw = minecraft.player.yaw;
		float pitch = minecraft.player.pitch;
		Recorder.recordRaw(bytes(0x0D, out -> {
			out.writeDouble(x);
			out.writeDouble(eyes);
			out.writeDouble(feet);
			out.writeDouble(z);
			out.writeFloat(yaw);
			out.writeFloat(pitch);
			out.writeBoolean(true);
		}));
	}

	// ---- entities

	/** What the replay was last told about one entity. */
	private static final class Tracked {
		final Entity entity;
		int x, y, z;
		byte yaw, pitch, headYaw;
		int vx, vy, vz;
		int vehicle = -1;
		int sinceTeleport;
		byte[] data;
		/** Ticks between moves, the server's for its kind (EntityTracker): the client moves it in between. */
		int interval = 3;
		int age;

		Tracked(Entity entity) {
			this.entity = entity;
		}
	}

	private static void sendEntities(Minecraft minecraft) throws IOException {
		Set<Integer> seen = new HashSet<>();
		double range = RADIUS * 16;
		for (Object value : new ArrayList<Object>(world.entities)) {
			Entity entity = (Entity) value;
			if (entity == minecraft.player || entity.removed || entity instanceof PlayerEntity) {
				continue;
			}
			if (Math.abs(entity.x - minecraft.player.x) > range || Math.abs(entity.z - minecraft.player.z) > range
				|| !sentChunks.contains(chunkKey(floor(entity.x) >> 4, floor(entity.z) >> 4))) {
				continue;
			}
			Tracked state = tracked.get(entity.networkId);
			if (state != null && state.entity != entity) {
				remove(entity.networkId); // another entity took the id
				state = null;
			}
			if (state == null) {
				if (untracked.contains(entity.networkId)) {
					continue;
				}
				state = spawn(entity);
				if (state == null) {
					untracked.add(entity.networkId);
					continue;
				}
				tracked.put(entity.networkId, state);
			} else {
				move(state);
			}
			seen.add(entity.networkId);
		}
		for (Integer id : new ArrayList<>(tracked.keySet())) {
			if (!seen.contains(id)) {
				remove(id);
			}
		}
		// the player riding something: its own spawn is LocalPlayerRecorder's, riding is ours
		Entity vehicle = minecraft.player.vehicle;
		int vehicleId = vehicle == null ? -1 : vehicle.networkId;
		if (vehicleId != playerVehicle && (vehicleId == -1 || tracked.containsKey(vehicleId))) {
			playerVehicle = vehicleId;
			ride(minecraft.player.networkId, vehicleId);
		}
	}

	private static Tracked spawn(Entity entity) throws IOException {
		Tracked state = new Tracked(entity);
		position(state);
		if (entity instanceof MobEntity) {
			state.interval = 3;
			Recorder.record(new AddMobPacket((MobEntity) entity));
			state.data = data(entity); // in the spawn packet
			byte[] head = NativeProtocol.headYaw(entity);
			if (head != null) {
				Recorder.recordRaw(head);
				state.headYaw = head[head.length - 1];
			}
		} else if (entity instanceof ItemEntity) {
			state.interval = 20;
			Recorder.record(new AddItemPacket((ItemEntity) entity));
		} else if (entity instanceof PaintingEntity) {
			Recorder.record(new AddPaintingPacket((PaintingEntity) entity));
			return state; // never moves
		} else if (entity instanceof ExperienceOrbEntity) {
			state.interval = 20;
			Recorder.record(new AddExperienceOrbPacket((ExperienceOrbEntity) entity));
		} else {
			int[] vehicle = NativeProtocol.vehicle(entity);
			if (vehicle == null) {
				return null;
			}
			state.interval = interval(vehicle[0]);
			Recorder.recordRaw(bytes(0x17, out -> {
				out.writeInt(entity.networkId);
				out.writeByte(vehicle[0]);
				out.writeInt(state.x);
				out.writeInt(state.y);
				out.writeInt(state.z);
				out.writeInt(vehicle[1]);
				if (vehicle[1] > 0) {
					out.writeShort(vehicle[2]);
					out.writeShort(vehicle[3]);
					out.writeShort(vehicle[4]);
				}
			}));
			state.vx = vehicle[2];
			state.vy = vehicle[3];
			state.vz = vehicle[4];
		}
		if (state.data == null) {
			state.data = data(entity);
			sendData(entity.networkId, state.data);
		}
		if (!(entity instanceof MobEntity) && !(entity instanceof PaintingEntity)) {
			// the angles the add packets leave out
			Recorder.recordRaw(teleport(entity.networkId, state));
		}
		return state;
	}

	private static void position(Tracked state) {
		Entity entity = state.entity;
		state.x = floor(entity.x * 32.0);
		state.y = floor(entity.y * 32.0);
		state.z = floor(entity.z * 32.0);
		state.yaw = (byte) (int) (entity.yaw * 256.0F / 360.0F);
		state.pitch = (byte) (int) (entity.pitch * 256.0F / 360.0F);
	}

	private static void move(Tracked state) throws IOException {
		Entity entity = state.entity;
		int id = entity.networkId;
		if (entity instanceof PaintingEntity) {
			return;
		}
		// riding: the client puts it on its vehicle, a server sends no moves for it
		int vehicle = entity.vehicle == null ? -1 : entity.vehicle.networkId;
		if (vehicle != state.vehicle && (vehicle == -1 || tracked.containsKey(vehicle))) {
			state.vehicle = vehicle;
			ride(id, vehicle);
		}
		state.age++;
		if (entity.vehicle == null && state.age % state.interval == 0) {
			int x = state.x, y = state.y, z = state.z;
			byte yaw = state.yaw, pitch = state.pitch;
			position(state);
			int dx = state.x - x, dy = state.y - y, dz = state.z - z;
			boolean moved = dx != 0 || dy != 0 || dz != 0;
			boolean turned = state.yaw != yaw || state.pitch != pitch;
			state.sinceTeleport += state.interval;
			if (moved || turned) {
				if (Math.abs(dx) >= 128 || Math.abs(dy) >= 128 || Math.abs(dz) >= 128 || state.sinceTeleport >= TELEPORT_EVERY) {
					state.sinceTeleport = 0;
					Recorder.recordRaw(teleport(id, state));
				} else if (moved && turned) {
					Recorder.recordRaw(bytes(0x21, out -> {
						out.writeInt(id);
						out.writeByte(dx);
						out.writeByte(dy);
						out.writeByte(dz);
						out.writeByte(state.yaw);
						out.writeByte(state.pitch);
					}));
				} else if (moved) {
					Recorder.recordRaw(bytes(0x1F, out -> {
						out.writeInt(id);
						out.writeByte(dx);
						out.writeByte(dy);
						out.writeByte(dz);
					}));
				} else {
					Recorder.recordRaw(bytes(0x20, out -> {
						out.writeInt(id);
						out.writeByte(state.yaw);
						out.writeByte(state.pitch);
					}));
				}
			}
			// velocity: knockback, throws and falls start the client's own motion the same way
			int vx = velocity(entity.velocityX), vy = velocity(entity.velocityY), vz = velocity(entity.velocityZ);
			int change = Math.abs(vx - state.vx) + Math.abs(vy - state.vy) + Math.abs(vz - state.vz);
			if (change > 160) { // 0.02 blocks a tick, the server's threshold
				state.vx = vx;
				state.vy = vy;
				state.vz = vz;
				Recorder.recordRaw(bytes(0x1C, out -> {
					out.writeInt(id);
					out.writeShort(vx);
					out.writeShort(vy);
					out.writeShort(vz);
				}));
			}
		}
		byte[] head = NativeProtocol.headYaw(entity);
		if (head != null && head[head.length - 1] != state.headYaw) {
			state.headYaw = head[head.length - 1];
			Recorder.recordRaw(head);
		}
		byte[] data = data(entity);
		if (!Arrays.equals(data, state.data)) {
			state.data = data;
			sendData(id, data);
		}
	}

	private static int velocity(double v) {
		return (int) (Math.max(-3.9, Math.min(3.9, v)) * 8000.0);
	}

	/** The server's update interval for each add-entity type (EntityTracker of these versions). */
	private static int interval(int type) {
		switch (type) {
			case 1: // boat
			case 10: // minecarts
			case 11:
			case 12:
				return 3;
			case 90: // fishing bobber
				return 5;
			case 50: // primed TNT
			case 61: // snowball, egg, fireballs, ender pearl and eye, potions, bottles
			case 62:
			case 63:
			case 64:
			case 65:
			case 72:
			case 73:
			case 75:
				return 10;
			case 51: // ender crystal: never moves
				return Integer.MAX_VALUE;
			default: // arrows, falling blocks
				return 20;
		}
	}

	private static byte[] teleport(int id, Tracked state) throws IOException {
		return bytes(0x22, out -> {
			out.writeInt(id);
			out.writeInt(state.x);
			out.writeInt(state.y);
			out.writeInt(state.z);
			out.writeByte(state.yaw);
			out.writeByte(state.pitch);
		});
	}

	private static void ride(int id, int vehicle) throws IOException {
		Recorder.recordRaw(bytes(0x27, out -> {
			out.writeInt(id);
			out.writeInt(vehicle);
		}));
	}

	private static void remove(int id) throws IOException {
		tracked.remove(id);
		Recorder.recordRaw(bytes(0x1D, out -> out.writeInt(id)));
	}

	/** The entity's synced data as the server writes it (entries, then 0x7F). */
	private static byte[] data(Entity entity) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(32);
		entity.getSyncedData().serialize(new DataOutputStream(bytes));
		return bytes.toByteArray();
	}

	private static void sendData(int id, byte[] data) throws IOException {
		Recorder.recordRaw(bytes(0x28, out -> {
			out.writeInt(id);
			out.write(data);
		}));
	}

	// ---- events from the world (NativeWorldMixin, NativeWorldListener, NativePickupMixin)

	/** Hurt, death, taming hearts, a wolf shaking: what a server's doEntityEvent sends. */
	public static void entityEvent(World from, Entity entity, byte event) {
		if (started && from == world && Recorder.isRecording() && entity != player && tracked.containsKey(entity.networkId)) {
			Recorder.recordEntityEvent(entity.networkId, event);
		}
	}

	/** Pistons, note blocks: block event 0x36. */
	public static void blockEvent(World from, int x, int y, int z, int type, int data) {
		if (started && from == world && Recorder.isRecording() && sentChunks.contains(chunkKey(x >> 4, z >> 4))) {
			write(bytes(0x36, out -> {
				out.writeInt(x);
				out.writeShort(y);
				out.writeInt(z);
				out.writeByte(type);
				out.writeByte(data);
			}));
		}
	}

	/** Explosion 0x3C: the client plays it and clears the blocks (the changes come as well). */
	public static void explosion(World from, double x, double y, double z, float power, Set<?> blocks) {
		if (!started || from != world || !Recorder.isRecording() || !sentChunks.contains(chunkKey(floor(x) >> 4, floor(z) >> 4))) {
			return;
		}
		int baseX = (int) x, baseY = (int) y, baseZ = (int) z;
		List<int[]> positions = new ArrayList<>();
		for (Object block : blocks) {
			BlockPos pos = (BlockPos) block;
			positions.add(new int[] { pos.x, pos.y, pos.z });
		}
		write(bytes(0x3C, out -> {
			out.writeDouble(x);
			out.writeDouble(y);
			out.writeDouble(z);
			out.writeFloat(power);
			out.writeInt(positions.size());
			for (int[] pos : positions) {
				out.writeByte(pos[0] - baseX);
				out.writeByte(pos[1] - baseY);
				out.writeByte(pos[2] - baseZ);
			}
		}));
	}

	/** Lightning 0x47. */
	public static void globalEntity(World from, Entity entity) {
		if (started && from == world && Recorder.isRecording()) {
			write(bytes(0x47, out -> {
				out.writeInt(entity.networkId);
				out.writeByte(1);
				out.writeInt(floor(entity.x * 32.0));
				out.writeInt(floor(entity.y * 32.0));
				out.writeInt(floor(entity.z * 32.0));
			}));
		}
	}

	/** The player picked something up: the item flies to it (0x16), then goes. */
	public static void pickUp(Entity collector, Entity item) {
		if (started && collector.world == world && Recorder.isRecording() && tracked.containsKey(item.networkId)) {
			write(bytes(0x16, out -> {
				out.writeInt(item.networkId);
				out.writeInt(collector.networkId);
			}));
		}
	}

	/** Doors, block breaking, records, smoke, fire sounds: world event 0x3D. */
	static void worldEvent(World from, int type, int x, int y, int z, int data) {
		if (started && from == world && Recorder.isRecording() && sentChunks.contains(chunkKey(x >> 4, z >> 4))) {
			write(bytes(0x3D, out -> {
				out.writeInt(type);
				out.writeInt(x);
				out.writeByte(y);
				out.writeInt(z);
				out.writeInt(data);
			}));
		}
	}

	// ---- writing

	private interface Writer {
		void write(DataOutputStream out) throws IOException;
	}

	private static byte[] bytes(int id, Writer writer) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(16);
		DataOutputStream out = new DataOutputStream(bytes);
		try {
			out.writeByte(id);
			writer.write(out);
		} catch (IOException e) {
			throw new IllegalStateException(e); // in memory: cannot happen
		}
		return bytes.toByteArray();
	}

	private static void write(byte[] packet) {
		Recorder.recordRaw(packet);
	}

	private static int floor(double value) {
		int i = (int) value;
		return value < i ? i - 1 : i;
	}
}
