package io.github.andrix03.retroreplay.render;

import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GLContext;

import io.github.andrix03.retroreplay.compat.Mc;

import net.minecraft.client.Minecraft;

/**
 * Renders a video at any size: before 1.7 the game draws straight to the window, so while a
 * frame is drawn a framebuffer of the video's size is bound and Minecraft's width and height
 * (which size its viewport and projection) are set to it. Color and depth live in renderbuffers.
 * From 1.7 the game binds its own framebuffer while drawing, so that one is resized instead.
 */
final class OffscreenTarget {

	private final int width;
	private final int height;
	private boolean gameTarget;
	private int framebuffer;
	private int color;
	private int depth;
	private int windowWidth;
	private int windowHeight;

	private OffscreenTarget(int width, int height) {
		this.width = width;
		this.height = height;
	}

	static boolean isSupported() {
		return GLContext.getCapabilities().GL_EXT_framebuffer_object;
	}

	/** Null when the driver cannot do it (then the window size is used). */
	static OffscreenTarget create(int width, int height) {
		if (!isSupported()) {
			return null;
		}
		int max = GL11.glGetInteger(EXTFramebufferObject.GL_MAX_RENDERBUFFER_SIZE_EXT);
		if (width > max || height > max) {
			return null;
		}
		OffscreenTarget target = new OffscreenTarget(width, height);
		target.windowWidth = Mc.minecraft().width;
		target.windowHeight = Mc.minecraft().height;
		if (Mc.resizeGameTarget(width, height)) {
			target.gameTarget = true;
			return target;
		}
		target.framebuffer = EXTFramebufferObject.glGenFramebuffersEXT();
		target.color = EXTFramebufferObject.glGenRenderbuffersEXT();
		target.depth = EXTFramebufferObject.glGenRenderbuffersEXT();
		EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, target.framebuffer);
		EXTFramebufferObject.glBindRenderbufferEXT(EXTFramebufferObject.GL_RENDERBUFFER_EXT, target.color);
		EXTFramebufferObject.glRenderbufferStorageEXT(EXTFramebufferObject.GL_RENDERBUFFER_EXT, GL11.GL_RGBA8, width, height);
		EXTFramebufferObject.glFramebufferRenderbufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT,
			EXTFramebufferObject.GL_RENDERBUFFER_EXT, target.color);
		EXTFramebufferObject.glBindRenderbufferEXT(EXTFramebufferObject.GL_RENDERBUFFER_EXT, target.depth);
		EXTFramebufferObject.glRenderbufferStorageEXT(EXTFramebufferObject.GL_RENDERBUFFER_EXT, GL14.GL_DEPTH_COMPONENT24, width, height);
		EXTFramebufferObject.glFramebufferRenderbufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, EXTFramebufferObject.GL_DEPTH_ATTACHMENT_EXT,
			EXTFramebufferObject.GL_RENDERBUFFER_EXT, target.depth);
		int status = EXTFramebufferObject.glCheckFramebufferStatusEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT);
		EXTFramebufferObject.glBindRenderbufferEXT(EXTFramebufferObject.GL_RENDERBUFFER_EXT, 0);
		EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, 0);
		if (status != EXTFramebufferObject.GL_FRAMEBUFFER_COMPLETE_EXT) {
			target.delete();
			return null;
		}
		return target;
	}

	/** Draw into the target from now on, at its size. */
	void begin() {
		Minecraft minecraft = Mc.minecraft();
		windowWidth = minecraft.width;
		windowHeight = minecraft.height;
		minecraft.width = width;
		minecraft.height = height;
		if (gameTarget) {
			Mc.bindGameTarget();
		} else {
			EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, framebuffer);
		}
		GL11.glViewport(0, 0, width, height);
	}

	/** Back to the window (after the frame was read). */
	void end() {
		EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, 0);
		Minecraft minecraft = Mc.minecraft();
		minecraft.width = windowWidth;
		minecraft.height = windowHeight;
		GL11.glViewport(0, 0, windowWidth, windowHeight);
	}

	void delete() {
		if (gameTarget) {
			Mc.resizeGameTarget(windowWidth, windowHeight);
			return;
		}
		EXTFramebufferObject.glDeleteFramebuffersEXT(framebuffer);
		EXTFramebufferObject.glDeleteRenderbuffersEXT(color);
		EXTFramebufferObject.glDeleteRenderbuffersEXT(depth);
	}
}
