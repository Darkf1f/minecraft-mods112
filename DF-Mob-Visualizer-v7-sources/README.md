# DF Mob Visualizer v6

This is a complete Fabric Loom project containing the client-side visual layer
reconstructed around the attached
entity-transmitter class. It keeps the existing scanner idea, but adds:

- `F8` — toggle the in-game HUD;
- `F9` — toggle the chunk overlay;
- `F7` — toggle selected mob models through blocks (the original model is
  rendered in a second, depth-disabled pass; no tracer/ray/box overlay);
- `F10` — open the in-game settings screen;
- persistent chunk marks in `config/df-mob-visualizer-chunks.json`;
- persistent color and threshold settings in
  `config/df-mob-visualizer.json`;
- purple marking for IDs below 10,000;
- configurable dark-red, red, dark-orange, and orange percentage bands;
- gold `ALERT` marks in the HUD and chunk overlay;
- glowing entity outlines through blocks when the option is enabled.
- charged creepers are tracked and displayed with their own status and color.
- `F5` — clear the session immediately; `F6` — clear chunk history immediately.
- `HURT` highlights an entity while it is damaged; `HURT*` persists it in the
  session when the “Мобы после удара” option is enabled.
- HUD rows include the exact ID percentage with two decimal places. ID color
  rules are configured separately from percentage rules; ID rules always win.
- Each color-rule field accepts up to ten rules. Example:
  `id<=10001=#C855E8FF` and
  `percent<30=#FFFF2020;percent<50=#FFFFB000`.
- v3 adds a visual ten-row editor for both rule groups. Thresholds are
  controlled with sliders, and each row opens the normal color palette;
  rule syntax does not need to be typed manually.
- v4 adds a separate LOW_ID session toggle and an entity checklist. When the
  checklist is non-empty, only selected entity types are pinned by LOW_ID.
- v5 makes the selected ID/percentage rule color the actual HUD row color.
  ALERT and HURT stay as text tags instead of overwriting the configured rule
  color.
- v6 adds a separate entity checklist for ALERT mobs that are allowed to enter
  the session. An empty checklist keeps the previous behavior and allows all
  ALERT entity types.
- v7 adds colored block-level outlines and top-surface tinting inside marked
  chunks for grass, dirt, leaves, and water. The effect uses the stored
  ID/percentage rule color and is limited to the upper part of each column for
  render performance.
- translucent colored surfaces over discovered chunks, with configurable
  fill and border opacity in `config/df-mob-visualizer.json`.
- chunk history can be restricted to custom ID/percentage rules. For example:
  `id<50=#C855E8FF;id<10000=#FF7A0000;percent<5=#FFFF2020`.

Chunk display can be controlled without restarting the game: `F9` toggles
both the persistent chunk borders and their colored surface fill. The
`chunkOpacity` and `chunkBorderOpacity` settings accept values from `0.0`
(invisible) to `1.0` (fully opaque).

The F10 screen controls HUD scale, chunk opacity, scan interval, chunk render
distance, session threshold, session pinning rules, custom entity types for
pinning, and cleanup actions for session data and chunk history. Session data
is saved to `config/df-mob-visualizer-session.json` when persistence is enabled.

Entity types can be entered as a comma-separated list in the F10 field, for
example `minecraft:zombie, minecraft:creeper`. A discovered chunk keeps the
most significant color it has seen instead of being downgraded by an ordinary
entity found later.

The original uploaded class is bytecode-only, so this source is a clean
reconstruction rather than a line-for-line decompile. The large numeric
`info.txt.txt` resource is not used and is intentionally excluded.

## Chunk marking rules

When `markOnlyRuleChunks` is enabled, a chunk is added to the history only
when at least one entity matches `chunkColorRules`. Rules are checked from
left to right and the first match wins. Supported comparisons are `<`, `<=`,
`>`, `>=`, and `=`:

```text
id<50=#C855E8FF;id<10000=#FF7A0000;percent<5=#FFFF2020;percent<20=#FFFFB000
```

`id` compares the entity ID directly. `percent` compares the entity ID to the
current maximum entity ID. Colors may be written as RGB (`#RRGGBB`) or ARGB
(`0xAARRGGBB`/`#AARRGGBB`). This feature is entirely client-side and does not
send packets to the server.

The current alert rule mirrors the console's suspicious-gap rule:
`maxId - entityId > 100000`, excluding players and endermen. If the
transmitter later emits an explicit alert flag, `TrackedMob.alert` should use
that flag instead.

## Build

Use Java 21 and run:

```text
./gradlew build
```

The finished JAR is written to `build/libs/`.