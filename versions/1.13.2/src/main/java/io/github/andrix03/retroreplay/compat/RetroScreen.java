package io.github.andrix03.retroreplay.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * Base of the shared screens on 1.13: turns its input callbacks into the old ones the shared
 * screens override (they are written for the versions before 1.13):
 * keyPressed(char, int) for keys and typed characters, mouseClicked(int, int, int), handleMouse()
 * for the wheel, buttonClicked(ButtonWidget). The buttons list is the old one: it becomes the
 * clickable children before each click.
 */
public abstract class RetroScreen extends Screen {

	/** Old style: a typed character (key 0) or a pressed key (char 0). Escape closes, as it did. */
	protected void keyPressed(char chr, int key) {
		if (key == Platform.KEY_ESCAPE) {
			minecraft.openScreen(null);
		}
	}

	protected void mouseClicked(int mouseX, int mouseY, int button) {
	}

	protected void buttonClicked(ButtonWidget button) {
	}

	/** Old style: a mouse event, the wheel in Platform.eventWheel(). */
	public void handleMouse() {
	}

	@Override
	public void init(Minecraft minecraft, int width, int height) {
		super.init(minecraft, width, height);
		syncButtons();
	}

	private void syncButtons() {
		children.removeIf(child -> child instanceof ButtonWidget);
		children.addAll(buttons);
	}

	@Override
	public boolean keyPressed(int key, int scanCode, int modifiers) {
		keyPressed((char) 0, key);
		return true;
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		keyPressed(chr, 0);
		return true;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		syncButtons();
		super.mouseClicked(mouseX, mouseY, button);
		mouseClicked((int) mouseX, (int) mouseY, button);
		return true;
	}

	@Override
	public boolean mouseScrolled(double amount) {
		Platform.setEventWheel((int) Math.round(amount * 120));
		handleMouse();
		Platform.setEventWheel(0);
		return true;
	}
}
