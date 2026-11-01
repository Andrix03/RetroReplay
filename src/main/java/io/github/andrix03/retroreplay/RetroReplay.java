package io.github.andrix03.retroreplay;

import net.ornithemc.osl.entrypoints.api.ModInitializer;

public class RetroReplay implements ModInitializer {

	public static final Log LOGGER = new Log("RetroReplay");

	@Override
	public void init() {
		LOGGER.info("RetroReplay loaded");
	}
}
