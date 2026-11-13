package io.github.andrix03.retroreplay.replay;

import io.github.andrix03.retroreplay.RetroReplay;
import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.recording.Recorder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.menu.GameMenuScreen;
import net.minecraft.client.gui.screen.menu.TitleScreen;

/**
 * Development self-test, only when RETROREPLAY_AUTORECORD is set: opens the singleplayer
 * world "rr_selftest", walks the player in a circle (jumping, swinging) for 12 seconds while
 * it is recorded, then returns to the title screen (where DevAutoplay can take over).
 */
public final class DevAutorecord {

	public static final boolean ENABLED = System.getenv("RETROREPLAY_AUTORECORD") != null;
	/** host:port to record a multiplayer session instead of the singleplayer test world. */
	private static final String SERVER = System.getenv("RETROREPLAY_AUTORECORD_SERVER");
	/** Singleplayer test world, created with seed 1234 if missing. */
	private static final String WORLD = System.getenv().getOrDefault("RETROREPLAY_AUTORECORD_WORLD", "rr_selftest");
	/** Die (/kill) and respawn during the recording. */
	private static final boolean DIE = System.getenv("RETROREPLAY_AUTORECORD_DIE") != null;
	private static int deathTicks;
	/** "quit" or "crash": end the game in the middle of the world instead of leaving it. */
	private static final String EXIT = System.getenv("RETROREPLAY_AUTORECORD_EXIT");
	/** RETROREPLAY_AUTORECORD_MENU: open the world the way the world screens do (they close right after). */
	/** RETROREPLAY_AUTORECORD_BREAK: break the block under the player at the spawn (use with GAMEMODE=1). */
	private static final boolean BREAK = System.getenv("RETROREPLAY_AUTORECORD_BREAK") != null;
	private static int[] breakAt;
	/** RETROREPLAY_AUTORECORD_MINE: in survival, stands still looking down for 9 s while the left button is held from outside (block cracks). */
	private static final boolean MINE = System.getenv("RETROREPLAY_AUTORECORD_MINE") != null;
	private static int[] mineAt;
	/** RETROREPLAY_AUTORECORD_FAR (with GAMEMODE=1): flies straight east, so the chunks left behind unload. */
	private static final boolean FAR = System.getenv("RETROREPLAY_AUTORECORD_FAR") != null;
	/** RETROREPLAY_AUTORECORD_TNT (1.0-1.2.5): primed TNT 6 blocks east of the player at tick 30. */
	private static final boolean TNT = System.getenv("RETROREPLAY_AUTORECORD_TNT") != null;
	/**
	 * RETROREPLAY_AUTORECORD_REDSTONE (1.0-1.2.5): a redstone bench in the air next to the player,
	 * switched on and off at fixed ticks, every block of it logged each tick ("Redstone t=...").
	 */
	private static final boolean REDSTONE = System.getenv("RETROREPLAY_AUTORECORD_REDSTONE") != null;
	/**
	 * RETROREPLAY_AUTORECORD_ENTITIES (1.0-1.2.5): mobs, items, an orb, a minecart, a boat and a
	 * snowball on a platform in the air at tick 20; every 2 ticks the position of every entity
	 * near it is logged ("Entities t=..."), to compare with the replay.
	 */
	private static final boolean ENTITIES = System.getenv("RETROREPLAY_AUTORECORD_ENTITIES") != null;
	private static int[] pen;
	private static int[] bench;
	private static int benchStart = -1;
	/** What the bench logs, from its corner: the circuit along x, water along z = 4, sand at z = 2. */
	private static final int[][] BENCH_LOG = { { 0, 0, 0 }, { 1, 0, 0 }, { 5, 0, 0 }, { 6, 0, 0 }, { 7, 0, 0 }, { 8, 0, 0 }, { 9, 0, 0 },
		{ 10, 0, 0 }, { 11, 0, 0 }, { 12, 0, 0 }, { 13, 0, 0 }, { 0, 0, 4 }, { 1, 0, 4 }, { 3, 0, 4 }, { 5, 0, 4 }, { 7, 0, 4 },
		{ 1, 0, 6 }, { 3, 0, 2 }, { 3, 2, 2 }, { 3, 4, 2 } };
	/** RETROREPLAY_AUTORECORD_NETHER (1.0-1.2.5 singleplayer): into the Nether at tick 100, like a portal. */
	private static final boolean NETHER = System.getenv("RETROREPLAY_AUTORECORD_NETHER") != null;
	private static final boolean VIA_MENU = System.getenv("RETROREPLAY_AUTORECORD_MENU") != null;
	/** How long the walk lasts, in ticks (default 240 = 12 s). */
	private static final int LENGTH_TICKS = Integer.parseInt(System.getenv().getOrDefault("RETROREPLAY_AUTORECORD_TICKS", "240"));
	/** Seconds spent in the pause menu (default 3; long pauses test connection timeouts). */
	private static final long PAUSE_NANOS = Long.parseLong(System.getenv().getOrDefault("RETROREPLAY_AUTORECORD_PAUSE", "3")) * 1_000_000_000L;

	private static boolean started;
	private static boolean finished;
	private static int ticks;
	private static int titleTicks;
	private static long pauseStart;

	private DevAutorecord() {
	}

	/** Returns true while the self-test owns the title screen. */
	public static boolean onTitleScreen() {
		return ENABLED && !finished;
	}

	public static void tick() {
		if (!ENABLED || finished) {
			return;
		}
		Minecraft minecraft = Mc.minecraft();
		if (!started) {
			// the first title screen opens before the game has finished starting up: wait a second
			if (minecraft.screen instanceof TitleScreen && ++titleTicks > 20) {
				started = true;
				if (SERVER != null) {
					RetroReplay.LOGGER.info("Autorecord: connecting to " + SERVER);
					String[] hostPort = SERVER.split(":");
					minecraft.openScreen(Mc.connectScreen(minecraft.screen, hostPort[0], Integer.parseInt(hostPort[1])));
				} else {
					RetroReplay.LOGGER.info("Autorecord: opening " + WORLD);
					Mc.startSelftestWorld(WORLD);
					if (VIA_MENU) {
						minecraft.openScreen(null); // what Create New World and Select World do right after
					}
				}
			}
			return;
		}
		if (minecraft.screen instanceof GameMenuScreen && System.nanoTime() - pauseStart > PAUSE_NANOS) {
			Mc.screenshot("autorecord_paused.png");
			RetroReplay.LOGGER.info("Autorecord: closing the pause menu");
			minecraft.openScreen(null);
		}
		if (DIE && minecraft.screen != null && minecraft.screen.getClass().getSimpleName().equals("DeathScreen") && ++deathTicks == 40) {
			Mc.screenshot("autorecord_death.png");
			RetroReplay.LOGGER.info("Autorecord: respawning");
			callPlayer(minecraft, "respawn");
			minecraft.openScreen(null);
		}
		if (minecraft.player == null || minecraft.world == null || minecraft.screen != null) {
			return;
		}
		ticks++;
		if (REDSTONE) {
			redstone(minecraft);
		}
		if (ENTITIES) {
			entities(minecraft);
		}
		if (BREAK && ticks == 20) {
			// the block under the feet, at the spawn (protected on old servers for non-operators)
			breakAt = new int[] { (int) Math.floor(minecraft.player.x), (int) Math.floor(minecraft.player.y - Mc.feetOffset()) - 1, (int) Math.floor(minecraft.player.z) };
			int before = Mc.blockState(breakAt[0], breakAt[1], breakAt[2]) >> 4;
			RetroReplay.LOGGER.info("Autorecord: breaking block " + before + " at " + breakAt[0] + "," + breakAt[1] + "," + breakAt[2]);
			call(minecraft.interactionManager, "startMiningBlock", breakAt[0], breakAt[1], breakAt[2], 1);
			holdStone(minecraft);
		}
		if (BREAK && ticks == 50) {
			int after = Mc.blockState(breakAt[0], breakAt[1], breakAt[2]) >> 4;
			RetroReplay.LOGGER.info("Autorecord: block at the spawn " + (after == 0 ? "broken" : "still there (" + after + ")"));
		}
		if (MINE && ticks >= 20 && ticks < 200) {
			// looking straight down, standing still: the left mouse button is held from outside (a real dig)
			if (ticks == 20) {
				mineAt = new int[] { (int) Math.floor(minecraft.player.x), (int) Math.floor(minecraft.player.y - Mc.feetOffset()) - 1, (int) Math.floor(minecraft.player.z) };
				RetroReplay.LOGGER.info("Autorecord: hold the left mouse button now, block " + call(minecraft.world, "getBlock", mineAt[0], mineAt[1], mineAt[2]) + " at " + mineAt[0] + "," + mineAt[1] + "," + mineAt[2]);
			}
			minecraft.player.pitch = 90.0F;
			minecraft.player.velocityX = 0;
			minecraft.player.velocityZ = 0;
			return;
		}
		if (MINE && ticks == 200) {
			RetroReplay.LOGGER.info("Autorecord: mined block now " + call(minecraft.world, "getBlock", mineAt[0], mineAt[1], mineAt[2]));
		}
		if (ticks == 40 && System.getenv("RETROREPLAY_AUTORECORD_DIFFICULTY") != null) {
			// what the options screen's difficulty button does (by name: the field is not on every version)
			try {
				java.lang.reflect.Field field = minecraft.options.getClass().getField("difficulty");
				field.setInt(minecraft.options, Integer.parseInt(System.getenv("RETROREPLAY_AUTORECORD_DIFFICULTY")));
				RetroReplay.LOGGER.info("Autorecord: options difficulty set to " + field.getInt(minecraft.options));
			} catch (ReflectiveOperationException e) {
				RetroReplay.LOGGER.error("Autorecord: cannot set the difficulty", e);
			}
		}
		if (ticks == 60) {
			Mc.screenshot("autorecord_recording.png");
			Recorder.addMarker(); // what the M key does
		}
		if (ticks == 80 && DIE) {
			RetroReplay.LOGGER.info("Autorecord: /kill");
			if (!Mc.selftestKill()) {
				callPlayer(minecraft, "sendChat", "/kill");
			}
		}
		if (ticks == 100 && EXIT != null) {
			// leaving the game from inside a world: "quit" like closing the window, "crash" without any cleanup
			RetroReplay.LOGGER.info("Autorecord: " + EXIT + " while in the world");
			if (EXIT.equals("crash")) {
				Runtime.getRuntime().halt(1);
			}
			minecraft.stop(); // what closing the window does: the game loop ends and shuts down
		}
		if (ticks == 120 && pauseStart == 0) {
			// 3 s in the pause menu: the recording clock must not count them
			RetroReplay.LOGGER.info("Autorecord: opening the pause menu");
			pauseStart = System.nanoTime();
			minecraft.openScreen(new GameMenuScreen());
			return;
		}
		if (TNT && ticks == 30) {
			// by name: the class and its constructor exist only before 1.3 in this form (dev self-test)
			try {
				Object tnt = Class.forName("net.minecraft.entity.PrimedTntEntity").getConstructor(Class.forName("net.minecraft.world.World"), double.class, double.class, double.class)
					.newInstance(minecraft.world, minecraft.player.x + 6, minecraft.player.y - Mc.feetOffset(), minecraft.player.z);
				minecraft.world.getClass().getMethod("addEntity", Class.forName("net.minecraft.entity.Entity")).invoke(minecraft.world, tnt);
				RetroReplay.LOGGER.info("Autorecord: primed TNT at " + (int) (minecraft.player.x + 6) + "," + (int) minecraft.player.y + "," + (int) minecraft.player.z);
			} catch (ReflectiveOperationException e) {
				RetroReplay.LOGGER.error("Autorecord: cannot prime TNT", e);
			}
		}
		if (NETHER && ticks == 100) {
			RetroReplay.LOGGER.info("Autorecord: into the Nether");
			call(minecraft, "changeDimension", -1);
		}
		if (FAR && ticks > 40) {
			minecraft.player.abilities.flying = true;
			// straight up first (nothing in the way up there), then east
			boolean high = minecraft.player.y >= 100;
			minecraft.player.noClip = !high; // through the trees it often spawns under
			if (ticks % 40 == 0) {
				RetroReplay.LOGGER.info("Autorecord far: tick " + ticks + " at " + (int) minecraft.player.x + "," + (int) minecraft.player.y + "," + (int) minecraft.player.z
					+ " flying=" + minecraft.player.abilities.flying + " canFly=" + minecraft.player.abilities.canFly);
			}
			minecraft.player.velocityX = high ? 1.5 : 0;
			minecraft.player.velocityY = high ? 0 : 0.8;
			minecraft.player.velocityZ = 0;
			if (ticks >= LENGTH_TICKS) {
				finished = true;
				RetroReplay.LOGGER.info("Autorecord: done at " + (int) minecraft.player.x + "," + (int) minecraft.player.y + "," + (int) minecraft.player.z);
				minecraft.world.disconnect();
				minecraft.setWorld(null);
				minecraft.openScreen(new TitleScreen());
			}
			return;
		}
		minecraft.player.yaw += LENGTH_TICKS > 240 ? 0.05F : 3.0F; // a long session explores: new chunks all the time
		double rad = Math.toRadians(minecraft.player.yaw);
		minecraft.player.velocityX = -Math.sin(rad) * 0.2;
		minecraft.player.velocityZ = Math.cos(rad) * 0.2;
		if (ticks % 50 == 0 && minecraft.player.onGround) {
			minecraft.player.velocityY = 0.42;
		}
		if (ticks % 30 == 0) {
			Mc.swingArm();
		}
		if (ticks >= LENGTH_TICKS) {
			finished = true;
			RetroReplay.LOGGER.info("Autorecord: done at " + (int) minecraft.player.x + "," + (int) minecraft.player.y + "," + (int) minecraft.player.z);
			minecraft.world.disconnect();
			minecraft.setWorld(null);
			minecraft.openScreen(new TitleScreen());
		}
	}

	/** By name (dev mappings only): the method sits on a player class whose package differs between versions. */
	/** Dev only: 8 stone in the first hotbar slot (the hand, the hotbar and items must render normally). */
	private static void holdStone(Minecraft minecraft) {
		try {
			Object inventory = minecraft.player.getClass().getField("inventory").get(minecraft.player);
			Class<?> stackClass = Class.forName("net.minecraft.item.ItemStack");
			Object stack = stackClass.getConstructor(int.class, int.class, int.class).newInstance(1, 8, 0);
			for (java.lang.reflect.Field field : inventory.getClass().getFields()) {
				if (field.getType().isArray() && field.getType().getComponentType() == stackClass) {
					java.lang.reflect.Array.set(field.get(inventory), 0, stack);
					return;
				}
			}
		} catch (ReflectiveOperationException e) {
			RetroReplay.LOGGER.error("Autorecord: cannot give stone", e);
		}
	}

	/** Dev mappings only: an int-argument method by name (its owner's class differs between versions). */
	private static void redstone(Minecraft minecraft) {
		if (benchStart < 0) {
			// a new world first works through the falls its generation scheduled (100 000s on 1.0):
			// the bench waits for that, as it would in a world played for a while
			int queued = scheduledTicks(minecraft.world);
			if (ticks % 100 == 0) {
				RetroReplay.LOGGER.info("Autorecord: redstone bench waits, " + queued + " scheduled ticks");
			}
			if (ticks < 20 || queued > 50) {
				return;
			}
			benchStart = ticks;
			RetroReplay.LOGGER.info("Autorecord: redstone bench starts with " + queued + " scheduled ticks queued");
		}
		int b = ticks - benchStart;
		if (b == 0) {
			bench = new int[] { (int) Math.floor(minecraft.player.x) + 3, 100, (int) Math.floor(minecraft.player.z) - 2 };
			int x = bench[0], y = bench[1], z = bench[2];
			for (int dx = -1; dx <= 15; dx++) {
				for (int dz = -1; dz <= 7; dz++) {
					set(minecraft, x + dx, y - 1, z + dz, 1, 0); // stone platform
				}
			}
			set(minecraft, x + 1, y, z, 55, 0); // redstone dust
			set(minecraft, x + 2, y, z, 55, 0);
			set(minecraft, x + 3, y, z, 55, 0);
			set(minecraft, x + 4, y, z, 55, 0);
			set(minecraft, x + 5, y, z, 55, 0);
			set(minecraft, x + 6, y, z, 93, 1); // repeater, input west, 1 tick
			set(minecraft, x + 7, y, z, 55, 0);
			set(minecraft, x + 8, y, z, 93, 1 | 3 << 2); // repeater, 4 ticks
			set(minecraft, x + 9, y, z, 55, 0);
			set(minecraft, x + 10, y, z, 55, 0);
			set(minecraft, x + 11, y, z, 29, 5); // sticky piston facing east
			set(minecraft, x + 12, y, z, 4, 0); // the block it pushes
			RetroReplay.LOGGER.info("Autorecord: redstone bench at " + x + "," + y + "," + z + " from tick " + ticks);
		}
		int x = bench[0], y = bench[1], z = bench[2];
		if (b == 20) {
			set(minecraft, x, y, z, 76, 5); // redstone torch on the floor: on
			set(minecraft, x, y, z + 4, 8, 0); // water source
			set(minecraft, x + 3, y + 4, z + 2, 12, 0); // sand in the air
		}
		if (b == 60 || b == 140) {
			set(minecraft, x, y, z, 0, 0); // torch off
		}
		if (b == 90) {
			set(minecraft, x, y, z, 76, 5); // and on again
		}
		if (b <= 210) {
			StringBuilder line = new StringBuilder();
			for (int[] at : BENCH_LOG) {
				line.append(line.length() == 0 ? "" : ",").append(Mc.blockState(x + at[0], y + at[1], z + at[2]));
			}
			RetroReplay.LOGGER.info("Redstone t=" + b + " ms=" + io.github.andrix03.retroreplay.recording.Recorder.elapsed() + " time=" + call(minecraft.world, "getTime") + " q=" + scheduledTicks(minecraft.world) + " " + line);
		}
	}

	private static void entities(Minecraft minecraft) {
		if (ticks == 20) {
			pen = new int[] { (int) Math.floor(minecraft.player.x) - 8, 100, (int) Math.floor(minecraft.player.z) + 6 };
			int x = pen[0], y = pen[1], z = pen[2];
			for (int dx = -1; dx <= 17; dx++) {
				for (int dz = -1; dz <= 9; dz++) {
					set(minecraft, x + dx, y - 1, z + dz, 1, 0);
				}
			}
			String[][] mobs = { { "net.minecraft.entity.mob.animal.PigEntity", "net.minecraft.entity.mob.passive.animal.PigEntity" },
				{ "net.minecraft.entity.mob.animal.CowEntity", "net.minecraft.entity.mob.passive.animal.CowEntity" },
				{ "net.minecraft.entity.mob.animal.ChickenEntity", "net.minecraft.entity.mob.passive.animal.ChickenEntity" },
				{ "net.minecraft.entity.mob.monster.ZombieEntity", "net.minecraft.entity.mob.hostile.ZombieEntity" },
				{ "net.minecraft.entity.mob.monster.SkeletonEntity", "net.minecraft.entity.mob.hostile.SkeletonEntity" } };
			int spawned = 0;
			for (int i = 0; i < mobs.length; i++) {
				for (int copy = 0; copy < 2; copy++) {
					spawned += spawn(minecraft, mobs[i], x + 2 + i * 3, y, z + 2 + copy * 4) ? 1 : 0;
				}
			}
			try {
				Class<?> world = Class.forName("net.minecraft.world.World");
				Class<?> stack = Class.forName("net.minecraft.item.ItemStack");
				for (int i = 0; i < 5; i++) {
					Object item = Class.forName("net.minecraft.entity.ItemEntity").getConstructor(world, double.class, double.class, double.class, stack)
						.newInstance(minecraft.world, x + 4.5 + i, y + 3.0, z + 8.5, stack.getConstructor(int.class, int.class, int.class).newInstance(4, 1 + i, 0));
					velocity(item, 0.1 * (i - 2), 0.3, -0.1);
					add(minecraft, item);
					spawned++;
				}
				add(minecraft, Class.forName("net.minecraft.entity.ExperienceOrbEntity").getConstructor(world, double.class, double.class, double.class, int.class)
					.newInstance(minecraft.world, x + 12.5, y + 1.0, z + 8.5, 7));
				Object cart = Class.forName("net.minecraft.entity.vehicle.MinecartEntity").getConstructor(world, double.class, double.class, double.class, int.class)
					.newInstance(minecraft.world, x + 1.5, y + 0.5, z + 0.5, 0);
				velocity(cart, 0.6, 0, 0);
				add(minecraft, cart);
				add(minecraft, Class.forName("net.minecraft.entity.vehicle.BoatEntity").getConstructor(world, double.class, double.class, double.class)
					.newInstance(minecraft.world, x + 15.5, y + 0.5, z + 4.5));
				Object snowball = Class.forName("net.minecraft.entity.projectile.SnowballEntity").getConstructor(world, double.class, double.class, double.class)
					.newInstance(minecraft.world, x + 0.5, y + 2.0, z + 4.5);
				velocity(snowball, 1.2, 0.4, 0);
				add(minecraft, snowball);
				spawned += 4;
			} catch (ReflectiveOperationException e) {
				RetroReplay.LOGGER.error("Autorecord: cannot add the entities", e);
			}
			RetroReplay.LOGGER.info("Autorecord: entity pen at " + x + "," + y + "," + z + " with " + spawned + " entities");
		}
		if (pen != null && ticks % 2 == 0 && ticks <= 420) {
			StringBuilder line = new StringBuilder();
			try {
				java.lang.reflect.Field list = Class.forName("net.minecraft.world.World").getField("entities");
				Class<?> entity = Class.forName("net.minecraft.entity.Entity");
				for (Object e : new java.util.ArrayList<Object>((java.util.List<?>) list.get(minecraft.world))) {
					if (e == minecraft.player) {
						continue;
					}
					double ex = entity.getField("x").getDouble(e), ey = entity.getField("y").getDouble(e), ez = entity.getField("z").getDouble(e);
					if (Math.abs(ex - pen[0] - 8) > 40 || Math.abs(ez - pen[2] - 4) > 40) {
						continue;
					}
					line.append(entity.getField("networkId").getInt(e)).append(':').append(e.getClass().getSimpleName()).append(':')
						.append(String.format(java.util.Locale.ROOT, "%.3f:%.3f:%.3f", ex, ey, ez)).append(';');
				}
			} catch (ReflectiveOperationException e) {
				RetroReplay.LOGGER.error("Autorecord: cannot list the entities", e);
			}
			RetroReplay.LOGGER.info("Entities t=" + ticks + " ms=" + io.github.andrix03.retroreplay.recording.Recorder.elapsed() + " " + line);
		}
	}

	private static boolean spawn(Minecraft minecraft, String[] names, double x, double y, double z) {
		for (String name : names) {
			try {
				Object mob = Class.forName(name).getConstructor(Class.forName("net.minecraft.world.World")).newInstance(minecraft.world);
				Class.forName("net.minecraft.entity.Entity").getMethod("setPositionAndAngles", double.class, double.class, double.class, float.class, float.class)
					.invoke(mob, x + 0.5, y, z + 0.5, 0.0F, 0.0F);
				add(minecraft, mob);
				return true;
			} catch (ClassNotFoundException e) {
				// another version's package
			} catch (ReflectiveOperationException e) {
				RetroReplay.LOGGER.error("Autorecord: cannot add " + name, e);
				return false;
			}
		}
		RetroReplay.LOGGER.error("Autorecord: no class among " + java.util.Arrays.toString(names));
		return false;
	}

	private static void add(Minecraft minecraft, Object entity) throws ReflectiveOperationException {
		minecraft.world.getClass().getMethod("addEntity", Class.forName("net.minecraft.entity.Entity")).invoke(minecraft.world, entity);
	}

	private static void velocity(Object entity, double x, double y, double z) throws ReflectiveOperationException {
		Class<?> type = Class.forName("net.minecraft.entity.Entity");
		type.getField("velocityX").setDouble(entity, x);
		type.getField("velocityY").setDouble(entity, y);
		type.getField("velocityZ").setDouble(entity, z);
	}

	/** The world's queue of scheduled block ticks (dev only, by name); 0 for a multiplayer world's copy. */
	private static int scheduledTicks(Object world) {
		try {
			java.lang.reflect.Field field = Class.forName("net.minecraft.world.World").getDeclaredField("scheduledTicksInOrder");
			field.setAccessible(true);
			Object queue = field.get(world);
			return queue == null ? 0 : ((java.util.Collection<?>) queue).size();
		} catch (ReflectiveOperationException | RuntimeException e) {
			return 0;
		}
	}

	private static void set(Minecraft minecraft, int x, int y, int z, int block, int metadata) {
		call(minecraft.world, "setBlockWithMetadata", x, y, z, block, metadata);
	}

	private static Object call(Object target, String method, int... args) {
		try {
			Class<?>[] types = new Class<?>[args.length];
			Object[] values = new Object[args.length];
			for (int i = 0; i < args.length; i++) {
				types[i] = int.class;
				values[i] = args[i];
			}
			for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
				try {
					return type.getMethod(method, types).invoke(target, values);
				} catch (NoSuchMethodException e) {
					// up the hierarchy
				}
			}
			throw new NoSuchMethodException(method);
		} catch (ReflectiveOperationException e) {
			RetroReplay.LOGGER.error("Autorecord: cannot call " + method, e);
			return -1;
		}
	}

	private static void callPlayer(Minecraft minecraft, String method, Object... args) {
		try {
			Class<?>[] types = new Class<?>[args.length];
			for (int i = 0; i < args.length; i++) {
				types[i] = args[i].getClass();
			}
			minecraft.player.getClass().getMethod(method, types).invoke(minecraft.player, args);
		} catch (ReflectiveOperationException e) {
			RetroReplay.LOGGER.error("Autorecord: cannot call " + method, e);
		}
	}
}
