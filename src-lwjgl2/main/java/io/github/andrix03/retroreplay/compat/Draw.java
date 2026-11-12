package io.github.andrix03.retroreplay.compat;

import net.minecraft.client.render.vertex.Tesselator;

/** Low-level drawing for the shared GUI code (1.13 rewrote the tessellator). */
public final class Draw {

	private Draw() {
	}

	/** The whole bound texture over a rectangle. */
	public static void texturedQuad(int x, int y, int width, int height) {
		Tesselator t = Tesselator.INSTANCE;
		t.begin();
		t.vertex(x, y + height, 0, 0, 1);
		t.vertex(x + width, y + height, 0, 1, 1);
		t.vertex(x + width, y, 0, 1, 0);
		t.vertex(x, y, 0, 0, 0);
		t.end();
	}
}
