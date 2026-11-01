package io.github.andrix03.retroreplay.replay;

import net.minecraft.network.LocalConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.util.Logger;

/** The "server" end of a replay: whatever the client sends is dropped. */
final class SinkConnection extends LocalConnection {

	SinkConnection(Logger logger) {
		super(logger, null);
	}

	@Override
	public void accept(Packet packet) {
	}
}
