# AsthoonLite architecture

Read `AGENTS.md` first. This file is the map; that file is the rulebook.

Target: Minecraft `26.1.2`, Fabric loader `0.19.3`, fabric-api
`0.155.2+26.1.2`, fabric-language-kotlin `1.13.13`, JDK 25.

---

## 1. Shape of the repo

```
src/main/
  kotlin/com/asthoonlite/
    AsthoonLite.kt            ClientModInitializer — the only wiring point
    config/Config.kt          Config.Data (Gson) + typed accessors + save()
    command/AslCommand.kt     /asl — open GUI, toggle features, diagnostics
    gui/                      the settings screen (tabs, rows, sliders)
    render/                   world-space drawing (one shared pipeline)
    hud/                      screen-space popups
    pet/                      pet HUD + pet menu slot highlight
    dungeon/                  everything dungeon-run related
      api/                    room/coordinate/floor model (pure data)
      map/                    map pixel scanning → room grid
      solvers/                per-puzzle geometry solvers (pure where possible)
    etherwarp/  fishing/  mining/  nucleus/  funny/   one feature family each
    utils/                    shared math
  java/com/asthoonlite/mixin/ mixins (Java — Mixin wants Java)
  resources/
    fabric.mod.json           entrypoint: com.asthoonlite.AsthoonLite
    asthoonlite.mixins.json   every mixin must be listed here
    assets/asthoonlite/       rooms.json, puzzle solutions, marker atlas
src/test/kotlin/.../DungeonRegressionCheck.kt    the harness (main(), not JUnit)
```

`build.gradle` also registers a `regressionCheck` JavaExec task and wires it
into `check`, so plain `./gradlew build` runs the harness.

## 2. Startup order

`AsthoonLite.onInitializeClient()` does everything, in this order:

1. `Config.load()` — must be first; every other `register()` may read config.
2. `AslCommand.register()`
3. `DungeonContext.register()` — scoreboard/sidebar-driven `inDungeon` flag.
   Almost every dungeon feature gates on `DungeonContext.inDungeon`.
4. `WorldBoxRenderer.register()` — **must precede anything that queues
   boxes**, because it installs the frame's queue-clearing handler first.
5. Then each module's own `register()`.

If you add a module, append its `register()` here and nowhere else.

## 3. Three rendering paths — do not cross them

### 3.1 World space: `render/WorldBoxRenderer.kt`

One `RenderPipeline`, one vertex buffer, one draw pass per frame for
**every** box drawn in the world (etherwarp target, star-mob highlights,
higher/lower order, secret-hitbox outlines).

Contract:

- Queue during `LevelRenderEvents.END_EXTRACTION` via
  `queueFilled(...)` / `queueOutline(...)` / `queueLine(...)`.
- `WorldBoxRenderer` draws them in `AFTER_TRANSLUCENT_TERRAIN`.
- `throughWalls = true` uses a second pipeline with the depth-stencil state
  removed — that is the only difference between "visible" and "x-ray".
- GPU buffers are released from `mixin/MixinGameRenderer.kt#close`, required
  by Fabric's 26.1.2 world-rendering docs.

Never give a feature its own pipeline. If you need a new primitive (text,
sprites), extend this object.

`render/WorldTextRenderer.kt` is the sibling for world-space text and
follows the same lifecycle.

### 3.2 Screen space: `hud/`, `gui/`, `pet/`

Hud elements go through Fabric's `HudElementRegistry`. Menus are
`Screen#extractRenderState(...)` based in 26.1.2 (`GuiGraphics` was renamed
to `GuiGraphicsExtractor`; `drawString`/`drawCenteredString` are now
`text`/`centeredText`). See `gui/AsthoonLiteScreen.kt` for the current shape.

### 3.3 Outside the game window: `render/MapCanvas.kt` + `overlay/MapOverlayWindow.kt`

A window capture records exactly one window, so anything drawn in the game
window is in the recording. The dungeon map can be moved into a second,
always-on-top window that the capture does not see — toggle **External
Overlay Window** on the Map tab (`dungeonMapExternalWindow`).

The map's layout is written once against `MapCanvas` and reaches one of two
painters:

- `HudMapCanvas` — passthrough to the game's own extractor (default).
- `RecordMapCanvas` → `replay(ops, J2dMapCanvas)` — the frame is recorded as
  a list of `MapOp`s and drawn by Java2D on the Swing thread.

Contract:

- `DungeonMap` must not call `GuiGraphicsExtractor` directly. Every drawing
  call goes through the canvas, or the two destinations drift apart.
- The render thread only ever publishes a finished op list as a single
  reference write. All AWT state is touched on the EDT (`EventQueue.invokeLater`).
- The window is non-focusable and always-on-top; it sits at the top-left
  because it is not click-through.
- Player skins cannot be read back from the GPU, so a face in the external
  window is a bordered box with the player's initial. Geometry and colours
  still match the in-game map exactly.
- When the external window is on, quiet mode does not suppress the map —
  that is the point. When it is off, quiet mode suppresses the map like
  everything else in the game window.

## 4. State and lifecycle

- **Config**: `config/Config.kt`. `Config.Data` is one Gson object written to
  `config/asthoonLite.json`. Feature modules read `Config.x` live at the
  point of use — there are no mirrored `var enabled` booleans anywhere
  (that pattern caused the historic "have to toggle it off and on" bug).
  Adding a field: append with a default. Renaming: never.
- **Per-run state**: `dungeon/DungeonContext.kt` owns `inDungeon`, `floor`,
  `isBoss`. It resets on scoreboard loss and on
  `ClientPlayConnectionEvents.DISCONNECT`. Modules with their own per-run
  state reset there too (see `RoomAlerts.resetRun()`).
- **GUI ↔ Config sync**: `gui/AsthoonLiteScreen.kt` declares rows per tab in
  one `listOf(...)`. Adding a config field without a row means it is
  unreachable; adding a row without a field will not compile.

## 5. Mixins

All under `src/main/java/com/asthoonlite/mixin/`, all registered in
`asthoonlite.mixins.json` under `"client"`.

| Mixin | Hooks | Why |
|---|---|---|
| `MixinBlockStateShape` | `BlockBehaviour$BlockStateBase#getShape` | single choke point for interaction-shape overrides — see `AGENTS.md` §6 |
| `MixinLevelRenderer` | `LevelRenderer#extractBlockOutline` wrap | draws the selection outline |
| `MixinMinecraft` | `startUseItem`, `startAttack`, `shouldEntityAppearGlowing`, `hasControlDown` | click routing, glow override, held-key simulation |
| `MixinScreen` / `MixinHandledScreen` | key + slot draw/click | terminal automation |
| `MixinGameRenderer` | `close` | release custom-pipeline GPU buffers |
| `MixinInputConstants` / `MixinMouseButtonEvent` | input | keybind routing |
| `MixinMultiPlayerGameMode` | send-packet point | input timing tests |
| `MixinClientPacketListener` | chat/screen packets | feature triggers |
| `MixinEntity` | entity tick | ESP support |
| `IMapState`, `MinecraftAccessor`, `AbstractContainerScreenAccessor`, `ClientboundMoveEntityPacketAccessor` | accessors | field access |

`injectors.defaultRequire = 1`. A mistyped target is a hard crash on world
load, not a warning. Always confirm the descriptor with `javap` first.

## 6. Dungeon subsystems

- `dungeon/DungeonContext.kt` — presence + floor detection from the sidebar.
- `dungeon/map/DungeonMapScanner.kt` — reads the vanilla map item's
  128×128 colour buffer and derives the room grid (dimensions, origin,
  room types) using the documented map-colour byte IDs.
- `dungeon/map/DungeonScanner.kt` — scan orchestration used by the above.
- `dungeon/DungeonMap.kt` — renders the map: either the vanilla pixel blit
  or the full 6×6 grid, depending on `dungeonMapFullGrid`. Draws through
  `render/MapCanvas.kt` so the same layout feeds the in-game HUD and the
  external overlay window — see §3.3.
- `dungeon/api/` — room model (`DungeonRoom`, `Coordinates`, `FloorType`,
  `LegacyRegistry`, `DungeonDoor`, `mapEnums/*`). Pure data, no client deps
  where avoidable — good place to unit-test.
- `dungeon/solvers/` — one file per puzzle: Boulder, Water Board, Creeper
  Beam, Ice Fill, Ice Path, Teleport Maze, Tic-Tac-Toe, Livid, Arrow Align,
  plus `PuzzleUtils`/`CampHelper`. Each exposes pure functions that take a
  parsed state and return a result; the drawing lives in the caller. **Keep
  it that way** — it is what makes the regression harness possible.
- `dungeon/SecretHitboxes.kt` — interaction-shape overrides. Invariants in
  `AGENTS.md` §6.
- `dungeon/StarMobESP.kt`, `HigherLowerSolver.kt`, `RoomAlerts.kt`,
  `QuizSolver.kt`, `WeirdosSolver.kt`, `BloodRoomSolver.kt`, `DragonPhase.kt`,
  `AutoTerminal.kt`, `TerminalHelper.kt`, `TerminalSolver.kt`, `F7Devices.kt`,
  `MaskDisplay.kt`, `SecretSounds.kt`, `DungeonTimers.kt` — feature modules.
- `dungeon/TermGui.kt` — optional Odin-style centered terminal grids. Enable
  **Custom Terminal GUI** on the Terminal tab; size and gap are adjustable.
  Its pure layout supplies tile rendering, mouse hit tests, keyboard clicks
  and `TerminalCursor.targetFor`. A screen is covered when its menu holds the
  slots the *tiles* land on (`TermGui.covers` reads `requiredSlots`, counted
  from the layout, not `Kind.slotCount`), so the player's inventory below the
  terminal — and one row more than the grid draws — is never required; that is
  what lets a window holding only the panes, a p3-simulator screen or a
  shorter practice chest, draw at all, and the same requirement gates
  `AutoTerminal.tick` and `TerminalSolver.clickCandidates` so the grid and the
  clicker open on the same screen or not at all. A screen that still does not
  draw prints one line per screen type through `TermGui.register()` naming
  which gate refused. Clicks leave
  through `dungeon/TerminalInput.kt`: the player's window sends packets, a
  screen holding a menu of its own is driven through `slotClicked`, the door
  a hand's click uses there. `MixinContainerScreen` replaces the chest
  background, while `MixinHandledScreen` replaces contents and input without
  replacing the live menu. Odin's BSD notice ships in `META-INF/licenses`.
- `dungeon/CursorMotion.kt` — pure retargetable pointer flight. Acceleration
  uses CSS cubic-bezier x inversion (Newton iteration and bisection), default
  `(0.2, 0, 0, 1)`. Percentage sliders tune speed, arc, tremor and the curve's
  four control points; **Humanize** scales how much all of them *vary* —
  timing spread, arc and easing jitter, the pause before moving, and the
  overshoot that carries past a pane before settling back. A flight has three
  stretches (dwell, travel, settle), all computed from its inputs, so the same
  inputs always draw the same line. AutoTerminal owns click timing
  and revalidates candidates each tick. The pointer never sets that timing: a
  trip is *fitted* into the time until the next click — hesitation first,
  travel with what is left, floored so motion stays visible — so the Click
  Delay is the cadence of every terminal and Pointer Speed only changes how
  the trip looks. The per-slot guard follows the same rule: every terminal
  gets the 350 ms round-trip ceiling, the number terminal gets the Click
  Delay instead — on a chain of numbered panes that ceiling was not a safety
  margin, it was the whole cadence. Enable **Glide On Melody** to premove to
  the next detected button immediately after clicking. Detection, skip queues
  and layout share the same three/four-row model. An unacknowledged melody
  click waits `MELODY_ROW_RETRY_MS` (250 ms) plus `MELODY_UPDATE_GRACE_MS`
  (three ticks, 150 ms) — the row is given time to arrive before the aim or
  the click answers for it, which is what keeps the pointer from walking back
  to the row it just left while the server is still catching up. Melody's aim
  and its click read the same candidate and the same total window, so the hand
  drops to the next row once and stays there.

## 7. The regression harness

`src/test/kotlin/com/asthoonlite/dungeon/DungeonRegressionCheck.kt`.

A `main()` of `check(...)` blocks. No JUnit, no assertions framework. It runs
offline, needs no game and no server, and is wired into `./gradlew build`
via the `regressionCheck` task.

It covers: sidebar/floor detection, terminal click timing, map dimension
scanning, mob-category naming, Tic-Tac-Toe solver, secret-hitbox expansion
geometry, and the map overlay canvas (record/replay round-trip, transform
balance, Java2D fill convention).

`TerminalRegressionCheck.kt`, called by that harness, also checks CSS easing
reference values and degenerate curves, retarget continuity, clicks during
flight, premove/debounce, three/four-row melody and skip queues, grid
centers/hit testing across scales, and old-config defaults — plus the pieces
that made the terminal settings stop answering: the click clock taking Click
Delay as its mean with one Delay Spread either side of it, the per-slot guard
each terminal runs on, the humanize bounds (timing, hesitation, overshoot,
settle), a flight that hesitates and settles back onto its pane, the split
that keeps a whole trip inside one beat, which menus the drawn grid covers
(counted from the tiles, hand-checked against each layout's shape), and the
melody grace that keeps a row's ticks from being answered too early.

**Rule: if you change pure logic, add a check for it in the same commit.**
