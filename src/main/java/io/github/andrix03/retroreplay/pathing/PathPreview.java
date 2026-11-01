package io.github.andrix03.retroreplay.pathing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.lwjgl.opengl.GL11;

import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.gui.ReplayGui;
import io.github.andrix03.retroreplay.render.VideoRenderer;
import io.github.andrix03.retroreplay.replay.ReplayHandler;

import net.minecraft.client.Minecraft;

/**
 * Replay Mod's PathPreviewRenderer in fixed-function GL: the camera path as a line, light red
 * where the camera moves slowly and dark red where fast, the position keyframes as icons facing
 * the viewer, and a camera head with its green view line at the timeline cursor. H toggles it.
 */
public final class PathPreview {

	private static final int SLOW_PATH_COLOR = 0xffcccc;
	private static final int FAST_PATH_COLOR = 0x660000;
	/** Blocks per millisecond at which the line reaches the "fast" color. */
	private static final double FASTEST_PATH_SPEED = 0.01;
	private static final int STEPS = 100;

	private static boolean enabled = true;

	private PathPreview() {
	}

	public static void toggle() {
		enabled = !enabled;
	}

	/** Inside the world pass (camera rotation set, origin at the camera), after weather. */
	public static void render(float tickDelta) {
		ReplayHandler replay = ReplayHandler.get();
		Minecraft minecraft = Mc.minecraft();
		if (!enabled || replay == null || PathPlayer.isActive() || VideoRenderer.isRendering() || minecraft.player == null) {
			return;
		}
		PathingState state = PathingState.of(replay.getFile());
		CameraPath path = state.path;
		if (path.positions().isEmpty()) {
			return;
		}
		double eye = minecraft.player.eyeHeight;
		double viewX = minecraft.player.prevX + (minecraft.player.x - minecraft.player.prevX) * tickDelta;
		double viewY = minecraft.player.prevY + (minecraft.player.y - minecraft.player.prevY) * tickDelta;
		double viewZ = minecraft.player.prevZ + (minecraft.player.z - minecraft.player.prevZ) * tickDelta;

		GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
		GL11.glPushMatrix();
		GL11.glDisable(GL11.GL_LIGHTING);
		GL11.glDisable(GL11.GL_FOG);
		GL11.glDisable(GL11.GL_CULL_FACE);
		GL11.glDisable(GL11.GL_ALPHA_TEST);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

		// the path, segment by segment
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glLineWidth(3);
		GL11.glBegin(GL11.GL_LINES);
		Long previousKey = null;
		for (long key : path.positions().keySet()) {
			if (previousKey != null) {
				long diff = key - previousKey;
				CameraPath.Position prev = null;
				for (int i = 0; i <= STEPS; i++) {
					CameraPath.Position pos = path.positionAt(previousKey + diff * i / STEPS);
					if (prev != null) {
						double distance = Math.sqrt(sq(pos.x - prev.x) + sq(pos.y - prev.y) + sq(pos.z - prev.z));
						double speed = Math.min(distance / (diff / (double) STEPS), FASTEST_PATH_SPEED);
						color(interpolateColor(SLOW_PATH_COLOR, FAST_PATH_COLOR, speed / FASTEST_PATH_SPEED), 255);
						GL11.glVertex3d(prev.x - viewX, prev.y + eye - viewY, prev.z - viewZ);
						GL11.glVertex3d(pos.x - viewX, pos.y + eye - viewY, pos.z - viewZ);
					}
					prev = pos;
				}
			}
			previousKey = key;
		}
		GL11.glEnd();

		// keyframe icons, the furthest first, drawn over everything like Replay Mod
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		// transparent texels must be dropped: with this blending they would darken what is behind
		GL11.glEnable(GL11.GL_ALPHA_TEST);
		GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
		GL11.glBlendFunc(GL11.GL_DST_COLOR, GL11.GL_SRC_COLOR);
		ReplayGui.bind(ReplayGui.REPLAY_GUI);
		List<Map.Entry<Long, CameraPath.Position>> keyframes = new ArrayList<>(path.positions().entrySet());
		keyframes.sort((a, b) -> Double.compare(distanceSq(b.getValue(), viewX, viewY - eye, viewZ), distanceSq(a.getValue(), viewX, viewY - eye, viewZ)));
		float cameraYaw = minecraft.player.lastYaw + (minecraft.player.yaw - minecraft.player.lastYaw) * tickDelta;
		float cameraPitch = minecraft.player.lastPitch + (minecraft.player.pitch - minecraft.player.lastPitch) * tickDelta;
		for (Map.Entry<Long, CameraPath.Position> keyframe : keyframes) {
			CameraPath.Position p = keyframe.getValue();
			if (nearCamera(p, viewX, viewY - eye, viewZ)) {
				continue; // a keyframe where the camera stands would cover the whole screen
			}
			boolean selected = state.isSelected(PathingState.Kind.POSITION, keyframe.getKey());
			drawPoint(p.x - viewX, p.y + eye - viewY, p.z - viewZ, cameraYaw, cameraPitch, selected);
		}
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GL11.glEnable(GL11.GL_DEPTH_TEST);

		// the camera at the cursor
		CameraPath.Position at = path.positionAt(state.cursor);
		if (!nearCamera(at, viewX, viewY - eye, viewZ)) {
			drawCamera(at.x - viewX, at.y + eye - viewY, at.z - viewZ, at.yaw, at.pitch, at.roll);
		}

		GL11.glPopMatrix();
		GL11.glPopAttrib();
		GL11.glColor4f(1, 1, 1, 1);
	}

	private static void drawPoint(double x, double y, double z, float cameraYaw, float cameraPitch, boolean selected) {
		float size = 10f / 256;
		float u = 80f / 256;
		float v = selected ? size : 0;
		GL11.glPushMatrix();
		GL11.glTranslated(x, y, z);
		GL11.glRotatef(-cameraYaw, 0, 1, 0);
		GL11.glRotatef(cameraPitch, 1, 0, 0);
		GL11.glColor4f(1, 1, 1, 1);
		GL11.glBegin(GL11.GL_QUADS);
		GL11.glTexCoord2f(u + size, v + size);
		GL11.glVertex3f(-0.5f, -0.5f, 0);
		GL11.glTexCoord2f(u + size, v);
		GL11.glVertex3f(-0.5f, 0.5f, 0);
		GL11.glTexCoord2f(u, v);
		GL11.glVertex3f(0.5f, 0.5f, 0);
		GL11.glTexCoord2f(u, v + size);
		GL11.glVertex3f(0.5f, -0.5f, 0);
		GL11.glEnd();
		GL11.glPopMatrix();
	}

	/** A 0.5 block cube with the camera_head skin and a 2 block green line where it looks. */
	private static void drawCamera(double x, double y, double z, float yaw, float pitch, float roll) {
		GL11.glPushMatrix();
		GL11.glTranslated(x, y, z);
		GL11.glRotatef(-yaw, 0, 1, 0);
		GL11.glRotatef(pitch, 1, 0, 0);
		GL11.glRotatef(roll, 0, 0, 1);

		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glBegin(GL11.GL_LINES);
		GL11.glColor4f(0, 1, 0, 0xaa / 255f);
		GL11.glVertex3f(0, 0, 0);
		GL11.glVertex3f(0, 0, 2);
		GL11.glEnd();
		GL11.glEnable(GL11.GL_TEXTURE_2D);

		ReplayGui.bind(ReplayGui.CAMERA_HEAD);
		float s = 0.5f;
		float r = -s / 2;
		float t = 8 / 64f;
		GL11.glColor4f(1, 1, 1, 200 / 255f);
		GL11.glBegin(GL11.GL_QUADS);
		// the six faces of Replay Mod's camera cube, same texture coordinates
		quad(r, r + s, r, 3 * t, t, r + s, r + s, r, 4 * t, t, r + s, r, r, 4 * t, 2 * t, r, r, r, 3 * t, 2 * t);
		quad(r + s, r, r + s, 2 * t, 2 * t, r + s, r + s, r + s, 2 * t, t, r, r + s, r + s, t, t, r, r, r + s, t, 2 * t);
		quad(r + s, r + s, r, 0, t, r + s, r + s, r + s, t, t, r + s, r, r + s, t, 2 * t, r + s, r, r, 0, 2 * t);
		quad(r, r + s, r + s, 2 * t, t, r, r + s, r, 3 * t, t, r, r, r, 3 * t, 2 * t, r, r, r + s, 2 * t, 2 * t);
		quad(r + s, r, r, 3 * t, 0, r + s, r, r + s, 3 * t, t, r, r, r + s, 2 * t, t, r, r, r, 2 * t, 0);
		quad(r, r + s, r, t, 0, r, r + s, r + s, t, t, r + s, r + s, r + s, 2 * t, t, r + s, r + s, r, 2 * t, 0);
		GL11.glEnd();
		GL11.glPopMatrix();
	}

	private static void quad(float... v) {
		for (int i = 0; i < 20; i += 5) {
			GL11.glTexCoord2f(v[i + 3], v[i + 4]);
			GL11.glVertex3f(v[i], v[i + 1], v[i + 2]);
		}
	}

	private static void color(int rgb, int alpha) {
		GL11.glColor4f((rgb >> 16 & 0xff) / 255f, (rgb >> 8 & 0xff) / 255f, (rgb & 0xff) / 255f, alpha / 255f);
	}

	private static int interpolateColor(int c1, int c2, double weight) {
		return component(c1 >> 16 & 0xff, c2 >> 16 & 0xff, weight) << 16
			| component(c1 >> 8 & 0xff, c2 >> 8 & 0xff, weight) << 8
			| component(c1 & 0xff, c2 & 0xff, weight);
	}

	private static int component(int c1, int c2, double weight) {
		return (int) (c1 + (1 - Math.pow(Math.E, -4 * weight)) * (c2 - c1)) & 0xff;
	}

	/** Within a block of the camera: drawing it would fill the view (a new keyframe is made right there). */
	private static boolean nearCamera(CameraPath.Position p, double x, double feetY, double z) {
		return distanceSq(p, x, feetY, z) < 1;
	}

	private static double distanceSq(CameraPath.Position p, double x, double y, double z) {
		return sq(p.x - x) + sq(p.y - y) + sq(p.z - z);
	}

	private static double sq(double v) {
		return v * v;
	}
}
