package io.github.andrix03.retroreplay.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

class FrameComposerTest {

	private static int face(int lookup) {
		return lookup >>> 24;
	}

	@Test
	void directionsFallOnTheirFaces() {
		int size = 64;
		int center = 32 * size + 32;
		assertEquals(0 << 24 | center, FrameComposer.lookup(0, 0, 1, size)); // forward: the front's middle
		assertEquals(1, face(FrameComposer.lookup(1, 0, 0, size))); // right
		assertEquals(2, face(FrameComposer.lookup(0, 0, -1, size))); // back
		assertEquals(3, face(FrameComposer.lookup(-1, 0, 0, size))); // left
		assertEquals(4, face(FrameComposer.lookup(0, 1, 0, size))); // up
		assertEquals(5, face(FrameComposer.lookup(0, -1, 0, size))); // down
		// a bit right of forward lands right of the front's middle, a bit up above it
		int rightUp = FrameComposer.lookup(0.2, 0.1, 1, size) & 0xFFFFFF;
		assertEquals(true, rightUp % size > 32);
		assertEquals(true, rightUp / size > 32);
		// slightly up on the up face is toward its top: the picture's top points back
		int upBack = FrameComposer.lookup(0, 1, -0.5, size) & 0xFFFFFF;
		assertEquals(true, upBack / size > 32);
	}

	@Test
	void theMiddleOfA360LooksForwardAndItsEdgesBack() {
		int[] table = FrameComposer.equirectangularTable(64, 32, 16);
		assertEquals(0, face(table[16 * 64 + 32])); // middle
		assertEquals(2, face(table[16 * 64])); // left edge: behind
		assertEquals(2, face(table[16 * 64 + 63])); // right edge: behind
		assertEquals(1, face(table[16 * 64 + 48])); // three quarters: right
		assertEquals(4, face(table[31 * 64 + 10])); // top row (the last, bottom first): up
		assertEquals(5, face(table[10])); // bottom row: down
	}

	@Test
	void stereoAndCrossPutPiecesInPlace() {
		ByteBuffer eye = ByteBuffer.allocateDirect(2 * 2 * 4);
		for (int i = 0; i < 4; i++) {
			eye.putInt(i * 4, 100 + i);
		}
		ByteBuffer frame = ByteBuffer.allocateDirect(4 * 2 * 4);
		FrameComposer.stereo(eye, 1, 2, 2, frame);
		assertEquals(100, frame.getInt((0 * 4 + 2) * 4)); // row 0, right half
		assertEquals(103, frame.getInt((1 * 4 + 3) * 4));
		ByteBuffer face = ByteBuffer.allocateDirect(4);
		face.putInt(0, 7);
		ByteBuffer cross = ByteBuffer.allocateDirect(4 * 3 * 4);
		FrameComposer.cross(face, 4, 1, cross); // up: column 1, top row
		assertEquals(7, cross.getInt((2 * 4 + 1) * 4));
	}

	@Test
	void exrHeaderAndScanlines() throws Exception {
		ByteBuffer bgra = ByteBuffer.allocateDirect(2 * 2 * 4);
		for (int i = 0; i < 16; i++) {
			bgra.put(i, (byte) 255);
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		FrameWriter.Exr.write(out, bgra, new float[] { 1, 2, 3, 4 }, 2, 2);
		ByteBuffer exr = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(20000630, exr.getInt(0));
		assertEquals(2, exr.getInt(4));
		// the offset table follows the header: the last line ends the file
		int bytes = out.size();
		int line = 8 + 2 * 4 * 4;
		long last = exr.getLong(bytes - 2 * line - 16 + 8);
		assertEquals(bytes - line, last);
		// first stored line (y 0) is GL's top row: depth 3, 4; white is 1.0 in linear light
		int first = bytes - 2 * line;
		assertEquals(0, exr.getInt(first));
		assertEquals(1.0F, exr.getFloat(first + 8), 1e-6);
		assertEquals(3.0F, exr.getFloat(first + 8 + 3 * 2 * 4), 0);
	}
}
