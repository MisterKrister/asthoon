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

1. `MapOverlayWindow.bootstrapDesktop()` clears Minecraft Main's forced AWT
   headless flag on supported desktop environments before the first AWT query.
   It does not read feature config or create a window; the regression JVM does
   not invoke client initialization. Then `Config.load()` runs before every
   module registration that may read config.
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

The settings screen keeps its 540-pixel panel in a logical viewport of at
least 564×384, including margins. `gui/GuiViewport.kt` uniformly scales this
layout down to fit smaller Minecraft GUI viewports, without enlarging it on
larger screens. Rendering, hover, clicks, release, scrolling and drag distances
share the same transform. Native content widgets use opaque header/footer
masks rather than transformed scissors for hover; Minecraft checks widget
hover against screen-space scissors. Resize rebuilds the layout and cancels
an unfinished drag. A settings-only footer identifies Quiet Mode suppression;
engaging Quiet Mode retains the existing screen-dismissal behavior.

### 3.3 Outside the game window: `render/MapCanvas.kt` + `overlay/MapOverlayWindow.kt`

A window capture records exactly one window, so anything drawn in the game
window is in the recording. The legit map always draws in the HUD. **Full
Map Overlay** (`dungeonMapFullGrid`) adds a second, always-on-top window
covering the HUD rectangle with the advanced map where desktop placement is
available. Native GLFW Wayland, headless AWT, or a failed desktop window keep
the advanced content in the HUD instead. The old `dungeonMapExternalWindow`
key remains serialized for compatibility but does not control this route.

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
- Minecraft Main sets `java.awt.headless=true`. The client bootstrap clears
  that flag for Windows, macOS and Linux with an X11 GLFW backend and `DISPLAY`,
  letting the JDK detect real desktop availability before it caches the result.
  Linux waits for the actual GLFW backend before its first AWT query. Native
  GLFW Wayland and an explicit JVM headless request retain their property and
  use the HUD route. A JVM where AWT was already initialized headless
  falls back and reports it rather than rewriting private JDK state.
- The window is non-focusable and always-on-top. Its bounds come from the
  GLFW content origin and GUI-to-desktop scale, converted to AWT units on
  the EDT. Native GLFW Wayland is unavailable for the desktop route because
  global game-window coordinates cannot be obtained; it uses the HUD fallback.
  On supported backends, a refused desktop position uses top-left placement.
  XWayland reports the X11 GLFW backend and follows the X11 coordinate path.
- Player skins cannot be read back from the GPU, so a face in the external
  window is a bordered box with the player's initial. Geometry and colours
  still match the in-game map exactly.
- Quiet mode, editor mode and all map early-return gates hide the external
  window. Editor mode uses `DungeonMapEditorScreen` and saves the clamped
  position on drop. One `dungeonMapStyle` selects Devonian or Noamm geometry
  for both layers; both use the existing scanner and room graph.

### 3.4 On-demand rendering diagnostics

`/asl diagnostics` (also `/asthoon diagnostics`) writes a snapshot to
`logs/latest.log`: runtime and native backend, shared visibility gates, map
data and route, AWT publications/paints, terminal grid gates, pointer mode and
ownership, mob detection, hitbox queueing, and world draw submissions. Counters
distinguish extraction from submission; neither proves visible pixels. The
snapshot is requested explicitly and does not produce per-frame log spam.

`/asl diagnostics fixture` installs real scoreboard, map-item, entity and block
data in a dedicated local singleplayer Overworld named `ASL` or
`ASL Rendering Test`. It modifies the fixture area near `-177,64,-181`, loads
the needed chunks, and replaces tagged fixture entities on repeated requests.
It does not enable features or force dungeon/client scanner state: normal
packets and the F7 sidebar activate the existing code. Use a dedicated test
world, clear Quiet Mode through settings, enable the feature being tested, and
take snapshots before and after the interaction. `/asl termsim` separately
provides the local terminal practice screen. See
[`RENDERING_INVESTIGATION.md`](RENDERING_INVESTIGATION.md) for evidence and the
cross-platform verification procedure.

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
- `dungeon/DungeonMap.kt` + `DungeonMapStyles.kt` — render the explored HUD
  layer and optional advanced external layer, using Devonian or Noamm style. Draw through
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
- `dungeon/TermGui.kt` — the Odin-style centered terminal grid, **on by
  default** (`termGuiEnabled` ships true, and the AutoTerm preset turns it on
  along with the pointer); size and gap are adjustable.
  Its pure layout supplies tile rendering, mouse hit tests, keyboard clicks
  and `TerminalCursor.targetFor`. SELECT and STARTS draw their actual item
  choices over the neutral/hint tile background in live and practice menus,
  including with solver hints off; hover names follow the same icon policy.
  A screen is covered when its menu holds the
  slots the *tiles* land on (`TermGui.covers` reads `requiredSlots`, counted
  from the layout, not `Kind.slotCount`), so the player's inventory below the
  terminal — and one row more than the grid draws — is never required; that is
  what lets a window holding only the panes, a p3-simulator screen or a
  shorter practice chest, draw at all, and the same requirement gates
  `AutoTerminal.tick` and `TerminalSolver.clickCandidates` so the grid and the
  clicker open on the same screen or not at all. A screen that still does not
  draw remains subject to the same title, slot-count and quiet-mode gates. Clicks leave
  through `dungeon/TerminalInput.kt`: the player's window sends packets, a
  screen holding a menu of its own is driven through `slotClicked`, the door
  a hand's click uses there. Manual eligibility follows the visible interactive
  controls, independently of the solver's next candidate. Numbered panes can
  receive consecutive clicks before the previous acknowledgement, and Melody
  buttons remain clickable between alignments. Live servers validate order and
  timing; the practice engine validates its own inputs. Empty tiles, filler,
  gaps and positions outside the interactive grid never become slot clicks.
  Manual Rubix left clicks still choose the shortest direction toward the
  fixed displayed target; explicit right clicks retain their requested
  direction, including on a pane already at that target. An immutable
  `RubixClickPlan` captures the effective button and next ring color before
  `TerminalInput.send`: vanilla `PICKUP` can empty the local slot during that
  call. After a successful send, prediction and presentation use the stored
  next color rather than reading the now-empty slot.
  `MixinScreen` owns the terminal frame at
  `extractRenderStateWithTooltipAndSubtitles`, before competing render
  cancellations. Fabric's GameRenderer wrapper retains the extraction events,
  input clock and pointer; the replacement dispatches only the background event.
  `MixinHandledScreen` routes input without
  replacing the live menu. Odin's BSD notice ships in `META-INF/licenses`.
  **Click Flash** (on by default) marks the pane a click was for and fades over
  220 ms — the mark that ties the pointer to the pane rather than leaving two
  unrelated things on screen.
- `dungeon/TerminalProgressHud.kt` — `In Terminal (Colors)` over `[7/15]`,
  centred a tenth of the screen down while a terminal is open. This is
  NoammAddons' Fake InvWalk readout: the name and the count, and never the
  solution, which is what makes an auto clicker look like something a person
  put on their screen. The denominator latches once when the terminal opens
  (`TerminalSolver.goalFor` — Numbers offers one candidate while wanting every
  numbered pane, Rubix wants clicks rather than panes, Melody's rows are its
  count) and the numerator counts the clicks the clicker sent.
- `dungeon/CursorMotion.kt` — pure retargetable pointer flight. Acceleration
  uses CSS cubic-bezier x inversion (Newton iteration and bisection), default
  `(0.2, 0, 0, 1)`. Percentage sliders tune speed, arc, tremor and the curve's
  four control points; **Humanize** scales how much all of them *vary* —
  timing spread, arc and easing jitter, the pause before moving, and the
  overshoot that carries past a pane before settling back. A flight has three
  stretches (dwell, travel, settle), all computed from its inputs, so the same
  inputs always draw the same line. AutoTerminal owns click timing
  and revalidates candidates each tick. A trip is *fitted* into the time until
  the next click — hesitation first, travel with what is left, floored so
  motion stays visible — so the Click Delay is the cadence of every terminal
  and Pointer Speed only changes how the trip looks. One thing runs the other
  way: **the packet waits for the hand**. `AutoTerminal.pointerBlocksClick`
  holds a click while the drawn pointer is still travelling to the pane it is
  for, so a click reads as the pointer landing and pressing rather than as a
  packet that left mid-crossing. It can hold a click for a few frames; it can
  never choose the beat, and with Glide Pointer off there is nothing on screen
  to wait for. The per-slot guard follows the same rule: every terminal
  gets the 350 ms round-trip ceiling, the number terminal gets the Click
  Delay instead — on a chain of numbered panes that ceiling was not a safety
  margin, it was the whole cadence. **Random Delay ships on**, over a
  150–240 ms window around a 180 ms mean — the shape NoammAddons ships — because
  a fixed interval is a metronome. **Glide On Melody ships on** too: melody's
  clicks ride this same clock, and the flat 40 ms they used to take on their
  own was 25 clicks a second. **Click Order** is NoammAddons' dropdown in his
  numbering — None (slot order), Random, Human (nearest, the default), Skizo
  (furthest) — and one setting feeds both `AutoTerminal.choose` and the
  next-click marker, so the ring and the packets cannot pick differently.
  Detection, skip queues
  and layout share adaptive Melody rows: button evidence detects content rows
  1–3 or 1–4, row 0 is the marker strip and the row immediately below the
  detected content is the indicator. Inventory and strips without buttons
  never grow the grid. A later observed moving note confirms earlier rows
  completed even on live boards that restore their buttons to red terracotta;
  sent clicks alone do not confirm completion. Number practice ships ten panes
  (`TerminalSolver.NUMBER_TERM_COUNT`); live number grids follow the actual
  numbered slots so wider boards share the same drawing and cursor targets.
  Disabling solver hints retains actual red/green pane states and the five
  current color-terminal states. Item-selection tiles keep their neutral
  background and item icons; hint counts and directions remain conditional.
  An unacknowledged melody
  click waits `MELODY_ROW_RETRY_MS` (250 ms) plus `MELODY_UPDATE_GRACE_MS`
  (three ticks, 150 ms) — the row is given time to arrive before the aim or
  the click answers for it, which is what keeps the pointer from walking back
  to the row it just left while the server is still catching up. Melody's aim
  and its click read the same candidate and the same deadline (the click
  beat), so there is one aim per tick, the hand drops to the next row once,
  and it stays there. Live packet-updated menus and local practice use the
  same Melody decision. A valid queued skip stays pending until a sent
  input commits it, and the acknowledgement grace also covers an earlier
  row still visible after a later queued input. Packet sensing owns menu
  open/close and connection resets; restarting the click driver retains
  contents that arrived before its first tick.

## 7. The regression harness

`dungeon/simulator/TerminalSimulation.kt` owns seeded local practice boards and
click rules. `TerminalSimulator.kt` presents separate local menus, delayed input,
and the one-second random restart. `TerminalInput` routes practice to this model
before the real menu path. `TerminalCapture.kt` records terminal-scoped callbacks
and state through three input/menu mixins; `TerminalCaptureStream.kt` hands
ordered JSONL records to an asynchronous file writer. See
[`TERMINAL_SIMULATOR.md`](TERMINAL_SIMULATOR.md) for settings and the data format.

`src/test/kotlin/com/asthoonlite/dungeon/DungeonRegressionCheck.kt`.

A `main()` of `check(...)` blocks. No JUnit, no assertions framework. It runs
offline, needs no game and no server, and is wired into `./gradlew build`
via the `regressionCheck` task.

It covers: sidebar/floor detection, terminal click timing, map dimension
scanning, mob-category naming, Tic-Tac-Toe solver, secret-hitbox expansion
geometry, and the map overlay canvas (record/replay round-trip, transform
balance, Java2D fill convention). `MapRenderingRegressionCheck.kt` also checks
gameplay visibility gates, desktop/HUD routing and delayed map calibration.
`TerminalPresentationRegressionCheck.kt` checks GUI gates, item visibility,
backend cursor policy, restoration ownership and viewport intersection.
`TerminalSensingLifecycleRegressionCheck.kt` and
`TerminalLiveMelodyRegressionCheck.kt` exercise real packet objects through
the production sensing and decision paths. `TerminalManualInputRegressionCheck.kt`
checks consecutive number clicks before acknowledgement, manual choices and
Melody buttons outside solver candidates, explicit right clicks at the Rubix
target, and preservation of the pre-send next color after `PICKUP` empties a
slot. `TerminalManualRubixRegressionCheck.kt` checks that manual left-click
direction retains the fixed displayed color target when a partial input changes
the board's optimal color, while completed controls remain interactive.
`gui/GuiViewportRegressionCheck.kt` checks settings fitting and inverse input
transforms without requiring a game window. These are logic checks, not
native-window or GPU presentation tests.

`TerminalRegressionCheck.kt`, called by that harness, also checks CSS easing
reference values and degenerate curves, retarget continuity, clicks during
flight, premove/debounce, three/four-row Melody and skip queues (including
indicator/inventory exclusion and a captured live fourth-row fixture), the four Click Order modes over the same
ready set, the pointer gate that holds a packet until the hand lands, grid
centers/hit testing across scales, and old-config defaults — plus the pieces
that made the terminal settings stop answering: the click clock taking Click
Delay as its mean with one Delay Spread either side of it, the per-slot guard
each terminal runs on, the humanize bounds (timing, hesitation, overshoot,
settle), a flight that hesitates and settles back onto its pane, the split
that keeps a whole trip inside one beat, which menus the drawn grid covers
(counted from the tiles, hand-checked against each layout's shape), the melody
grace that keeps a row's ticks from being answered too early, the shipped
random-delay window, and `goalFor` — what each of the six kinds counts as its
total clicks.

**Rule: if you change pure logic, add a check for it in the same commit.**
