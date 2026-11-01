package io.github.andrix03.retroreplay.render;

import io.github.andrix03.retroreplay.pathing.CameraPath;
import io.github.andrix03.retroreplay.pathing.PathStore;

/** One video of Replay Mod's render queue: settings plus the camera path as it was when queued. */
public final class RenderJob {

	public final RenderSettings settings;
	/** The path's JSON (PathStore), or null for the replay's current path. */
	final String pathJson;
	public final String label;

	public RenderJob(RenderSettings settings, String pathJson, String label) {
		this.settings = settings;
		this.pathJson = pathJson;
		this.label = label;
	}

	/** The path to render: the queued copy, or else the current one. */
	CameraPath path(CameraPath current) {
		if (pathJson == null) {
			return current;
		}
		CameraPath path = new CameraPath();
		PathStore.fromJson(pathJson, path);
		return path;
	}
}
