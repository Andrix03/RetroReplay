# RetroReplay

Replay recording, playback and (soon) camera paths and rendering for old Minecraft Java
versions that Replay Mod does not support, starting with 1.6.4. Built on Ornithe (Fabric
Loader + Mixin for old versions).

## Status (1.6.4)

- Records singleplayer and multiplayer sessions to `.minecraft/replay_recordings/*.mcpr`,
  including your own player, with a Replay Mod style "RECORDING" indicator.
- Replay Viewer from the main menu (logo button): load, open folder, rename, delete.
- In a replay: free flying camera, play/pause, speed slider, timeline (click to jump),
  "Exit Replay" in the pause menu. Keys: P pause, [ ] speed, Left/Right jump 10 s,
  T (chat key) frees the mouse for the replay bar.

## Development

`./gradlew runClient` starts the game. Self-tests: `RETROREPLAY_AUTORECORD=1` records a short
session in the world `rr_selftest`, `RETROREPLAY_AUTOPLAY=1` opens the newest recording and
takes screenshots into `run/screenshots`.

## License and credits

GPL-3.0-or-later (see LICENSE).

- `textures/replay_gui.png` and `textures/logo_button.png` come from
  [Replay Mod](https://github.com/ReplayMod/ReplayMod) (GPL-3.0); see
  `textures/replay_gui.license` for the parts based on ModMenu (MIT).
- `textures/jgui.png` comes from [jGui](https://github.com/ReplayMod/jGui) (MIT).
- The GUI layout follows Replay Mod's so the series looks the same on every version.
