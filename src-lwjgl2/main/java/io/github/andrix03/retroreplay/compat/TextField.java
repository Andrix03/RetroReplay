package io.github.andrix03.retroreplay.compat;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;

/**
 * A text field for the shared screens, with the old-style calls they make (1.13 splits keys from
 * typed characters and draws with the mouse position). Here it hands everything straight on.
 */
public final class TextField {

	private final TextFieldWidget widget;

	private TextField(TextFieldWidget widget) {
		this.widget = widget;
	}

	public static TextField create(Screen screen, int x, int y, int width, int height) {
		return new TextField(Mc.textField(screen, x, y, width, height));
	}

	public void keyPressed(char chr, int key) {
		widget.keyPressed(chr, key);
	}

	public void mouseClicked(int mouseX, int mouseY, int button) {
		widget.mouseClicked(mouseX, mouseY, button);
	}

	public void render() {
		widget.render();
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
