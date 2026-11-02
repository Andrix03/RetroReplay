package io.github.andrix03.retroreplay.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;

/**
 * A text field for the shared screens, with the old-style calls they make: on 1.13 a pressed key
 * and a typed character are separate events, and drawing takes the mouse position.
 */
public final class TextField {

	private final TextFieldWidget widget;

	private TextField(TextFieldWidget widget) {
		this.widget = widget;
	}

	public static TextField create(Screen screen, int x, int y, int width, int height) {
		return new TextField(new TextFieldWidget(0, Minecraft.getInstance().textRenderer, x, y, width, height));
	}

	/** RetroScreen sends keys with no character and characters with no key. */
	public void keyPressed(char chr, int key) {
		if (key != 0) {
			widget.keyPressed(key, 0, RetroScreen.isControlDown() ? 2 : 0);
		}
		if (chr != 0) {
			widget.charTyped(chr, 0);
		}
	}

	public void mouseClicked(int mouseX, int mouseY, int button) {
		widget.mouseClicked(mouseX, mouseY, button);
	}

	public void render() {
		widget.render(0, 0, 0);
	}

	public void tick() {
		widget.tick();
	}

	public String getText() {
		return widget.getText();
	}

	public void setText(String text) {
		widget.setText(text);
	}

	public void setFocused(boolean focused) {
		widget.setFocused(focused);
	}

	public void setMaxLength(int length) {
		widget.setMaxLength(length);
	}
}
