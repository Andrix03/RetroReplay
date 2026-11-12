# Changelog

## 0.3.0

- Block cracks of the recording player on every version: breaking a block now shows the
  cracks in the replay and in videos. The server never sends them to the player breaking, so
  they were missing; from 1.3.2 they are recorded as the packet other players get, before
  1.3 (no such packet) the recording writes its own. Recordings made before this have none.
- Quick Mode on Minecraft 1.0.0 (Q, on by default): the replay bar can be dragged and the
  world follows live, going back too, with no reload. Markers, Left/Right and keyframe jumps
  use it as well. Videos still start from an exact world.
- Minecraft 1.13.2: recording (singleplayer and multiplayer), replay, camera paths, spectator
  keyframes, video rendering and every 0.2.0 tool, like the other versions.
- Render screen like Replay Mod's on every version: while a video renders, the window shows each
  frame with a progress bar (frames, percentage, elapsed and remaining time). The video itself is
  unchanged.

## 0.2.0

More of Replay Mod's camera and render tools, on every supported version.

- Camera speed: the mouse wheel makes the free camera faster or slower (1/8x to 8x).
- Ambient lighting: Z lights caves and nights fully, in the replay and in videos.
- Thumbnails: N in a replay saves the current view (without the interface) into the replay
  file the way Replay Mod does; the Replay Viewer shows it.
- Render settings: "Render Nametags" (on by default, as in Replay Mod; videos had none
  before) and editable command line arguments for ffmpeg.

## 0.1.0

First public pre-release. Replay Mod's features on the Minecraft Java versions it does not
support: 1.0.0, 1.1, 1.2.5, 1.3.2, 1.4.7, 1.5.2, 1.6.4 and 1.7.10. Download the jar of your
exact Minecraft version and put it in `mods/` of an Ornithe (Fabric Loader) instance; the OSL
modules it needs are inside it. See the README for the install steps.

- Recording of singleplayer and multiplayer sessions to `.mcpr` files, your own player
  included, with Replay Mod's "RECORDING" indicator and event markers (M).
- Singleplayer on 1.0.0 - 1.2.5 runs on the matching vanilla server in the background (those
  versions have no packets to record otherwise); pausing pauses the world.
- Replay Viewer: load, rename, delete, open folder; interrupted recordings are recovered.
- Playback: free camera, pause, speed, timeline, jumps; spectating players; player overview.
- Camera paths as in Replay Mod: position, time and spectator keyframes, interpolation
  choices, keyframe editor, undo / redo, path preview, saved presets, copy and paste, roll.
- Video rendering at any resolution (1080p by default) through ffmpeg (Replay Mod's presets:
  MP4, WebM, MKV and more) or as PNG images, with a render queue.
- Paths, markers and presets are saved inside the replay file.

Known limits:

- Not tested yet with Prism Launcher / MultiMC instances (tested with launcher-style starts).
- On 1.0.0 - 1.2.5 changing the difficulty from the in-game options does not reach the
  background server.
- Jumping back far in a long replay takes a few seconds on the oldest versions (the world is
  rebuilt from the start).
