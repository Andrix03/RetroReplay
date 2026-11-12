package io.github.andrix03.retroreplay.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.ButtonWidget;

/** 1.13 made ButtonWidget abstract and clicks go to the button: they are passed on to the screen's buttonClicked. */
public class Button extends ButtonWidget {

	public Button(int id, int x, int y, int width, int height, String message) {
		super(id, x, y, width, height, message);
	}

	@Override
	public void click(double mouseX, double mouseY) {
		if (Minecraft.getInstance().screen instanceof RetroScreen) {
			((RetroScreen) Minecraft.getInstance().screen).buttonClicked(this);
		}
	}
}
