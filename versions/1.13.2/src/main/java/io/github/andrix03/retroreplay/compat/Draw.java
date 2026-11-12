package io.github.andrix03.retroreplay.compat;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.render.vertex.BufferBuilder;
import net.minecraft.client.render.vertex.DefaultVertexFormat;
import net.minecraft.client.render.vertex.Tesselator;

/** Low-level drawing for the shared GUI code: 1.13's buffer builder. */
public final class Draw {

	private Draw() {
	}

	/** The whole bound texture over a rectangle. */
	public static void texturedQuad(int x, int y, int width, int height) {
		Tesselator tesselator = Tesselator.getInstance();
		BufferBuilder buffer = tesselator.getBuffer();
		buffer.begin(GL11.GL_QUADS, DefaultVertexFormat.POSITION_TEX);
		buffer.vertex(x, y + height, 0).texture(0.0, 1.0).nextVertex();
		buffer.vertex(x + width, y + height, 0).texture(1.0, 1.0).nextVertex();
		buffer.vertex(x + width, y, 0).texture(1.0, 0.0).nextVertex();
		buffer.vertex(x, y, 0).texture(0.0, 0.0).nextVertex();
		tesselator.end();
	}
}
