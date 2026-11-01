# RetroReplay

Replay recording, playback and (soon) camera paths and rendering for old Minecraft Java
versions that Replay Mod does not support. Built on Ornithe (Fabric Loader + Mixin for old
versions).

## Versions

1.0.0, 1.1, 1.2.5, 1.3.2, 1.4.7, 1.5.2, 1.6.4 (one jar per version).

## Status

- Records singleplayer and multiplayer sessions to `.minecraft/replay_recordings/*.mcpr`,
  including your own player, with a Replay Mod style "RECORDING" indicator. The recording
  clock stops while a singleplayer game is paused.
- Replay Viewer from the main menu (logo button): load, open folder, rename, delete.
- In a replay: free flying camera, play/pause, speed slider, timeline (click to jump),
  "Exit Replay" in the pause menu. Keys: P pause, [ ] speed, Left/Right jump 10 s,
  T (chat key) frees the mouse for the replay bar.

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

Code shared by every version is in `src/`, by 1.3+ in `src-integrated/`, by the versions
before 1.3 in `src-split/`; `versions/<version>/src/` holds only what differs.

## License and credits

GPL-3.0-or-later (see LICENSE).

- `textures/replay_gui.png` and `textures/logo_button.png` come from
  [Replay Mod](https://github.com/ReplayMod/ReplayMod) (GPL-3.0); see
  `textures/replay_gui.license` for the parts based on ModMenu (MIT).
- `textures/jgui.png` comes from [jGui](https://github.com/ReplayMod/jGui) (MIT).
- The GUI layout follows Replay Mod's so the series looks the same on every version.
