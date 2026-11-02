package io.github.andrix03.retroreplay.compat;

import net.minecraft.client.gui.widget.ButtonWidget;

/** A button that runs an action when clicked, for vanilla screens (mixins cannot hold anonymous classes). */
public class ActionButton extends ButtonWidget {

	private final Runnable action;

	public ActionButton(int id, int x, int y, int width, int height, String message, Runnable action) {
		super(id, x, y, width, height, message);
		this.action = action;
	}

	@Override
	public void click(double mouseX, double mouseY) {
		action.run();
	}
}
