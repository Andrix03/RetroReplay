package io.github.andrix03.retroreplay.compat;

import net.minecraft.client.gui.screen.Screen;

/**
 * Base of the shared screens. Up to 1.12 Minecraft's Screen already has the methods they use
 * (init, buttonClicked, keyPressed(char, int), mouseClicked, handleMouse...); 1.13's has other
 * ones, and its RetroScreen turns them into these.
 */
public abstract class RetroScreen extends Screen {
}
