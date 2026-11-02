package io.github.andrix03.retroreplay.compat;

import net.minecraft.client.gui.widget.ButtonWidget;

/** A plain button for the shared screens (1.13 made ButtonWidget abstract: clicks go to the button there). */
public class Button extends ButtonWidget {

	public Button(int id, int x, int y, int width, int height, String message) {
		super(id, x, y, width, height, message);
	}
}
