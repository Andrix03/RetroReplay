# RetroReplay

Replay Mod for the old Minecraft Java versions it does not support: recording, playback,
camera paths and video rendering, with Replay Mod's interface and .mcpr files. Built on
Ornithe (Fabric Loader + Mixin for old versions).

## Versions

1.0.0, 1.1, 1.2.5, 1.3.2, 1.4.7, 1.5.2, 1.6.4, 1.7.10 (one jar per version).

## Install

1. Install Ornithe with the [Ornithe installer](https://ornithemc.net/download): choose the
   Minecraft version and Fabric Loader. It can make a profile for the official launcher or an
   instance for Prism Launcher / MultiMC.
2. Download the RetroReplay jar of that exact version from the
   [releases](https://github.com/Andrix03/RetroReplay/releases) (`retroreplay-<version>+mc<minecraft>.jar`)
   and put it in the `mods/` folder of that profile or instance. Nothing else is needed: the two
   OSL modules it uses (`osl-core`, `osl-entrypoints`) are inside the jar.
3. Start the game. The Replay Viewer is the button with the Replay Mod logo next to
   "Multiplayer" (or "Minecraft Realms") in the main menu; recording starts by itself when
   you join a world.

On 1.0.0, 1.1 and 1.2.5 do not add `osl-networking` yourself: it sends a handshake right after
login that vanilla servers of those versions answer by dropping the connection, and
singleplayer runs on one (see below).

## Status

- Records singleplayer and multiplayer sessions to `.minecraft/replay_recordings/*.mcpr` (on 1.7.10
  packets are stored as VarInt id + payload, like Replay Mod),
  including your own player, with a Replay Mod style "RECORDING" indicator. The recording
  clock stops while a singleplayer game is paused.
- Replay Viewer from the main menu (logo button): load, open folder, rename, delete.
- In a replay: free flying camera, play/pause, speed slider, timeline (click to jump),
  "Exit Replay" in the pause menu. Keys: P pause, [ ] speed, Left/Right jump 10 s,
  T (chat key) frees the mouse for the replay bar.
- Jumping back replays the recording from the start up to there, leaving out the chunk data
  and block changes of chunks unloaded again before that moment (the world is the same).
  Near the end of a 3 minute 1.0 exploration: 0.9 s instead of 1.8 s; 1.7.10 needs ~0.2 s
- Camera paths like Replay Mod's: a keyframe row under the replay bar with a 10 minute
  timeline. I adds or removes a position keyframe at the camera, O a time keyframe at the
  replay's moment, Delete removes the selected one, C clears all, Ctrl+Z / Ctrl+Y undo and
  redo, H shows the path in the world. Click to select, drag to move, double click to edit,
  right click to go there. The play button plays the path (Ctrl: from the start, Shift:
  ignoring time keyframes). V moves the cursor to the replay's moment (sync timeline).
  The keyframe editor also picks the interpolation: Catmull-Rom, cubic or linear.
  J / L roll the camera, K levels it. X opens the keyframe repository: paths saved by name,
  copied and pasted between replays as text, or added to the render queue.
  Paths, presets and markers are saved inside the replay file.
- Spectating: B opens the player overview, a click views the world through that player's
  eyes, Shift leaves. I while spectating adds a spectator keyframe: between two of them the
  path follows the player (blue on the timeline).
- Event markers: M while recording or in a replay; on the replay bar a click jumps to one,
  a double click names or deletes it.
- Rendering: the render button renders the path frame by frame, at 1920x1080 by default
  (720p, 1440p, 4K or the window's size), with Replay Mod's presets (MP4, WebM, lossless
  MKV) through ffmpeg, or as PNG images without it. ffmpeg is found on the PATH or in
  `.minecraft/ffmpeg/bin`; videos go to `.minecraft/replay_videos`. Esc cancels; progress is
  shown in the window title. "Add to Queue" collects videos (each with a copy of its path),
  "Render Queue" renders them one after another.

### Singleplayer before 1.3

Before 1.3 singleplayer runs the world inside the client, without packets, so there is
nothing to record. On 1.0.0, 1.1 and 1.2.5 a singleplayer world therefore opens on the
matching vanilla dedicated server, started in the background on a local port, and the client
joins it. The world stays in `saves/` and keeps working without the mod.

- The server jar is downloaded once into `.minecraft/retroreplay/` and checked by SHA-1:
  1.2.5 from Mojang, 1.1 and 1.0.0 (which Mojang no longer lists) from the Omniarchive
  archive, the same files Ornithe uses.
- The pause menu pauses the server's world too, through a small Java agent the mod
  writes next to the server (it redirects the server's clock, nothing else).
- The game plays by that version's multiplayer rules (for example block updates come
  from the server).

## Development

`./gradlew :<version>:runClient` starts the game (versions <= 1.5.2 run on Java 8).
Self-tests: `RETROREPLAY_AUTORECORD=1` records a short session in the world `rr_selftest`
(`RETROREPLAY_AUTORECORD_SERVER=host:port` records a multiplayer one instead),
`RETROREPLAY_AUTOPLAY=1` opens the newest recording and takes screenshots into
`versions/<version>/run/screenshots`.

Code shared by every version is in `src/`, by 1.3 - 1.6.4 in `src-integrated/`, by the versions
before 1.3 in `src-split/`; `versions/<version>/src/` holds only what differs (1.7.10, on
Netty, has its own network layer there). Releases: pushing a `v<version>` tag builds every
jar and publishes them on GitHub (`.github/workflows/release.yml`, notes from `CHANGELOG.md`).

## License and credits

GPL-3.0-or-later (see LICENSE).

- `textures/replay_gui.png` and `textures/logo_button.png` come from
  [Replay Mod](https://github.com/ReplayMod/ReplayMod) (GPL-3.0); see
  `textures/replay_gui.license` for the parts based on ModMenu (MIT).
- `textures/jgui.png` comes from [jGui](https://github.com/ReplayMod/jGui) (MIT).
- The GUI layout follows Replay Mod's so the series looks the same on every version.
