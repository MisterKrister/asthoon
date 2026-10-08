# Porting status → Minecraft 26.1.2

## 2026-10-08 — map styles, aligned overlay, terminal rendering

This update builds on the current scanner, room graph, categories and canvas;
it does not introduce another map engine. The older notes below describe
earlier releases, including APIs and scope that have since changed.

Reference checkouts (ignored under `reference/`):

- Devonian `26.2`, commit `d740958bdddbcbf8817fa86ec7e63821f5d94a94`.
  Adapted `DungeonMap` / `DungeonMapBaseRenderer` component transforms,
  rectangular and L-room joins, state colours, text alignment and checkmark art.
  Existing `BoxStarMob` category detection remains; Box mode uses the entity's
  complete AABB through the shared renderer. GPL-3.0 notice and license ship
  under `META-INF/licenses/Devonian.txt`.
- NoammAddons `26.1.2`, commit `6d0c95a5e994e9acc1d043c7eb2cdac916809a31`.
  Adapted `MapRenderer`'s 11x11 tile/connector/intersection pass, palette,
  checkmarks and marker art; `UniqueRoom` label placement; `DungeonListener`
  tab-row class detection and skin lookup; `MapUpdater` living-party order
  and icon yaw. CC0 license ships under `META-INF/licenses/NoammAddons.txt`.
  The exact earlier source drops were not present locally; the available
  `Examples/devonian` checkout was version 1.32.9, so the requested branches
  were used with the revisions above.

One style setting controls both the explored HUD and the advanced external
map. `dungeonMapFullGrid` now enables the external layer; legacy destination
fields stay serialized. The editor saves the entire clamped map rectangle.
GLFW returns the content-area origin already, so decoration insets must not
be added twice. Placement converts screen coordinates and DPI on the EDT;
native Wayland or a refused position logs once and falls back to top-left.

Terminal rendering now runs at the outer Screen extraction entry point,
before container-specific render cancellations. In the reference sources,
Noamm's invwalk cancels this outer method while Devonian owns an earlier
container hook; the former could prevent the old AsthoonLite draw hook from
running at all. Title, slot-count and quiet-mode gates are unchanged. Fabric's
GameRenderer wrapper still calls extraction events once, including the existing
input clock and pointer; only the background callback is dispatched by the
replacement. The Normal cursor draws an arrow instead of repeatedly warping the
OS pointer; solver resets keep it hidden until the terminal closes. Osu
motion, timing, art and trail paths are retained.

`javap` verified the 26.1.2 screen entry point, mouse event methods and the
`icon-N` map decoration keys. Regression checks cover config compatibility,
both style layouts/palettes, L-room centres, screen bounds, desktop/DPI
conversion, displayed player classes, full AABBs, cursor lifetime and sprite
record/replay. A live Hypixel/Wayland client was not available in this Windows
workspace; those interaction checks still need an in-game run.

## v1.3 — Star Mob ESP mob-type colors + Dungeon Map full-grid mode

Built from real, working source: NoammAddons-1.2.4, devonian-1.28.9-26.2,
and devoniandoogan-1.2.0-26.2 (all provided by the user, not guessed
from training data). Same "not compiled against your jar" caveat as
everything else here — the detection logic is ported faithfully from
confirmed-working code, but the surrounding 26.1.2 API calls (armor
stand name access, entity iteration, MapItemSavedData field names) are
still best-effort against this fork's mappings.

### Star Mob ESP — mob-type-aware coloring (`dungeon/StarMobESP.kt`)
Ported the mob-category detection straight from devonian's
`BoxStarMob.getMobDataFromArmorStand`/`getMobDataFromName` (confirmed
production code, not a guess): Withermancers/Lords/Commander/Super Archer
count as "chonk", Fels get their own color, Shadow Assassin and the
fake-player minibosses (Lost Adventurer/Diamond Guy/King Midas) get their
own colors, everything else with a star uses the flat star color.

New `Config.starMobEspByType` toggle: OFF keeps the old v1.2 behavior
(one flat color for everything), ON switches to the per-category colors
above. Colors are stored as raw ARGB ints in config so they're easy to
recolor later without a GUI color picker (not added yet — flip the hex
in `asthoonLite.json` for now, or ask for a color-picker row).

Detection itself is unchanged in technique from v1.2 (per-tick scan
instead of a packet-event mixin) — this pass only changed *what color*
gets assigned once a star mob is found, not *how* it's found.

### Dungeon Map — full-grid mode (`dungeon/DungeonMap.kt`, new
`dungeon/DungeonRoomScanner.kt`)
New `Config.dungeonMapFullGrid` toggle:
  - OFF (default, unchanged from v1.2): blits your own explored vanilla
    map pixels, bigger, optionally always-visible.
  - ON: renders the full 6x6 room grid as colored cells (entrance/normal/
    puzzle/trap/fairy/blood/boss/rare), with unexplored cells dimmed
    rather than hidden, plus a green/red checkmark strip on rooms with a
    known clear/fail state.

`DungeonRoomScanner` decodes this straight from the vanilla map's own
128x128 color buffer using devonian's confirmed map-color byte IDs
(`ROOM_ENTRANCE=30`, `ROOM_NORMAL=63`, `ROOM_PUZZLE=66`, etc., taken
verbatim from `DungeonMapScanner`) — no bundled per-floor layout dataset
needed, same reasoning as v1.2's original DungeonMap scope note.

Deliberately NOT ported from devonian: floor-type detection (needs
scoreboard parsing — a separate fragile dependency, left out to avoid
compounding risk), door/secret/mimic tracking, and the ~100-setting
custom vector-graphics room renderer devonian's real `DungeonMap.kt`
ships (1300+ lines, tightly coupled to its own HUD/config framework —
not something to port sight-unseen). "Full grid" here means "show the
whole 6x6 shape with real per-room types once explored", not the full
noamm/devonian map-engine experience with pre-revealed rooms.

### Also fixed
- `RoomAlerts.resetRun()` was defined but never called by anything —
  added `ClientPlayConnectionEvents.JOIN`/`DISCONNECT` hooks to actually
  call it, and made it also reset `HigherLowerSolver` and the new
  `DungeonMap`/`DungeonRoomScanner` state (previously only StarMobESP
  was reset). Without this, star mob tracking, blaze ordering, and the
  room grid would all silently carry over stale state from your last
  dungeon run into your next one.

### Spots to check first if build fails
- `StarMobESP.kt` — `Minecraft.getInstance().connection?.getPlayerInfo(uuid)`
  for reading a fake-player miniboss's tab-list name; same accessor the
  v1.2 base already used elsewhere, should be stable.
- `DungeonRoomScanner.kt` — `MapItemSavedData.colors` as a raw `ByteArray`
  (used as-is in v1.2's own `DungeonMap.kt` already, so if that compiled,
  this should too).
- `RoomAlerts.kt` — `ClientPlayConnectionEvents.JOIN`/`DISCONNECT` method
  signatures (arg count/order) weren't hand-verified against this Fabric
  API version's real interface, only pattern-matched against how
  `ClientCommandRegistrationCallback` etc. are used elsewhere in this repo.


## v1.2 — playtest fixes + new features (this pass, not compiled either)

Same caveat as v1.1 below: written against Mojang-mapped naming and
Fabric's documented APIs, not verified against your jar (no Maven access
in this sandbox). Budget time for compile fixes, especially DungeonMap.kt's
MapColor unpacking, which is the shakiest new API guess in this batch.

### Bugs fixed
- **"Have to toggle it off and back on" bug** — root cause was
  `Config.load()` returning early on a fresh install (before the config
  file exists) *before* it synced two mirrored runtime booleans
  (`AsthoonLite.hitboxFixEnabled`, `EtherwarpOverlay.enabled`) from the
  loaded data. Fixed by deleting those mirrors entirely — `MixinLivingEntity`
  and `EtherwarpOverlay` now read `Config.hitboxFixEnabled` /
  `Config.etherwarpEnabled` directly, same as every other feature already
  did. Nothing left to fall out of sync. See Config.kt's class doc.
- **Pet HUD not updating on pet-switch** — `PetTracker`'s per-tick "scan the
  Pets menu for the active pet" loop was disabled by a sticky
  `suppressScan = true` flag the moment you clicked a pet, meant to stop it
  from stomping the optimistic click-based update. If that optimistic
  update didn't match what the server actually did, `current` went stale
  and nothing re-scanned until you closed/reopened the Pets menu (which
  resets the flag). Changed to a short tick-based timeout
  (`suppressScanTicks`) instead of a sticky flag — scanning always resumes
  on its own ~0.5s later. See PetTracker.kt.
- **Room Cleared / Secret Done popups "not showing"** — the alerts
  themselves were already wired to AlertHud's center-screen popup; "Cleared"
  was just being driven off a guessed sidebar scoreboard line
  (`"Completed Rooms: N"`) that's a whole-run counter and whose exact text
  was never confirmed against a live server. Replaced with StarMobESP
  reporting when a room's tracked star mobs have all died — see below.

### New features
- **Etherwarp highlight recolor** — two-tone box instead of one flat color:
  a darker navy fill across the whole target block, brighter accent-blue
  outline on top. Outline is 12 thin filled "slab" quads along the box
  edges rather than a real line-mode pipeline (see WorldBoxRenderer.kt).
- **WorldBoxRenderer.kt** — pulled EtherwarpOverlay's custom-RenderPipeline
  box-drawing code out into one shared renderer so etherwarp, star mob ESP,
  and the higher/lower solver all queue into one draw call per frame
  instead of each standing up their own GPU buffer.
- **StarMobESP.kt** — highlights starred dungeon mobs (armor-stand-name +
  entity-id-offset technique, same as noamm's StarMobESP / devonian's
  BoxStarMob, but via a per-tick scan instead of a packet-event mixin).
  Also reports "all currently-tracked star mobs died" to RoomAlerts for the
  "Cleared" popup — a real per-room signal instead of the old whole-run
  sidebar counter guess.
- **HigherLowerSolver.kt** — Blaze/Higher-Lower puzzle. Same HP-armor-stand
  detection as noamm's BlazeSolver, but renders nearly-solid filled hitboxes
  (green = click now, white = next, red = third) instead of a glow outline,
  per what was asked for — deliberately covers the blaze rather than
  outlining it. Can't auto-detect the reversed "Lower Blaze" room variant
  without the map engine (see below), so there's a manual
  `higherLowerReversed` toggle instead.
- **DungeonMap.kt** — renders the actual Hypixel dungeon map item's pixel
  data scaled up on your HUD, with an "always show" toggle so it stays up
  even when the map isn't your held item. This is NOT a port of
  noamm/devonian's real map engines (DungeonMap/MapRenderer +
  utils/dungeons/map — a whole per-room state graph built from a bundled
  dungeon-layout dataset, tracking doors/secrets/room types and revealing
  unexplored rooms). That's thousands of interlocking lines keyed to data
  files not included in what you gave me, and isn't something that can be
  safely ported sight-unseen. What you get here is your own explored map,
  just bigger and optionally always visible — it won't show rooms you
  haven't walked into, because the vanilla map item itself doesn't have
  that data.
- **Autoclicker up to 500 CPS** — slider max raised from 20 to 500 and the
  tick loop switched from "click once every N ticks" (which hard-caps at
  20/sec, one per client tick) to a fractional accumulator that can fire
  several clicks in a single tick. See AutoClicker.kt.
- **All toggles default OFF** — every `Boolean` in `Config.Data` is now
  `false` by default, including the new features above. A fresh install
  does nothing until you turn things on via `/asl`.

### Puzzle-solver scope (unchanged from v1.1, still true)
Only Quiz, Three Weirdos, and now Higher/Lower are implemented. The other
puzzle types (Boulder, Water Board, Creeper Beam, Ice Fill/Path, Tic-Tac-Toe,
Teleport Maze) all rely on room-relative block/tile geometry from the same
map engine discussed above and weren't ported — same reasoning, not a
change of plan. `dungeon/HigherLowerSolver.kt` and `dungeon/WeirdosSolver.kt`
are decent templates if you want to tackle one of those yourself later
(entity/armor-stand detection + WorldBoxRenderer for highlighting).

## v1.1 — new features added on top of the confirmed v1.0 base

This pass adds a tabbed menu (QOL / Dungeon / Mining / Fishing / Nucleus /
Funny) and several new features ported/adapted from NoammAddons, rsm,
Scatha-Pro, and SkyMyce. **None of this new code has been compiled** — my
sandbox can't reach the Fabric/Mojang Maven repos, so unlike the v1.0 base
(which was checked against your real jar via `javap`), everything below is
written against Mojang-mapped naming conventions and Fabric's documented
26.1.2 APIs but not verified. Treat every file below as "probably right,
budget time to fix compile errors."

### Scope decisions worth knowing about
- **Dungeon room-clear/secret alerts** don't use noamm's full per-room map
  engine (HotbarMapScanner/RoomTile/MapConfig — thousands of lines, reads
  pixel colors off the dungeon map item). Instead `RoomAlerts.kt` tracks two
  counters Hypixel exposes directly: the actionbar "X/Y Secrets" text and
  the sidebar scoreboard's "Completed Rooms: N" line. You get the same two
  popups, just tied to total counts rather than a specific room.
- **Puzzle solvers**: only Quiz and Three Weirdos were ported — both are
  chat-driven and don't need room-relative coordinates. The other 6 puzzle
  types in noamm (Boulder, Blaze, Creeper Beam, Ice Fill/Path, Tic-Tac-Toe,
  Teleport Maze) rely on block/room geometry from that same map engine and
  weren't ported. `dungeon/WeirdosSolver.kt` is a decent template for
  "chat pattern → highlight something" if you want to tackle more later.
- **Three Weirdos** highlights the correct NPC (entity glow) instead of the
  chest next to it, since the chest position in the original is computed
  from room-relative coordinates we don't have without the map engine.
- **Quiz answers**: ships a near-empty starter `quizSolutions.json` (in
  `.minecraft/config/asthoonlite/`) since the actual answer bank wasn't in
  the source you gave me — it's downloaded at runtime by noamm's own mod.
  Fill it in yourself, format is `{"question snippet": ["answer text"]}`.
- **Fishing**: bite alert only (sound + on-screen ping when the bobber
  dips). Deliberately no auto-reel/recast.
- **Nucleus autowarp**: sends `/warp nuc` after a randomized short delay
  once "✦ CRYSTAL FOUND" appears in chat, same as SkyMyce.
- **Autoclicker**: plain fixed-interval right-click, CPS 1–20 via slider.
  No randomized/"humanized" timing — deliberately left out.

### Specific spots flagged as best-effort (check first if build fails)
- `RoomAlerts.kt` — `Scoreboard#getDisplayObjective(DisplaySlot)` and the
  per-entry team prefix/suffix read. Standard vanilla way to read a
  sidebar, but exact accessor names weren't confirmed against your jar.
- `WeirdosSolver.kt` — `Entity#isGlowing` as a settable property. Might
  actually be `setGlowingTag(Boolean)` — same idea, different call shape.
- `AutoNucleusWarp.kt` — `ClientPacketListener#sendCommand(String)` for
  firing a client-side command programmatically.
- `FishBiteAlert.kt` — `Player#fishing` as the accessor for your active
  `FishingHook` entity.
- `PickaxeAbilityTimer.kt` — lore parsing assumes the "Ability: X" / "N
  lines later" / "Cooldown: Ns" layout Scatha-Pro's SkyBlockItemUtil uses;
  should be stable since it's cosmetic item lore, not an API.

## Porting status → v1.0 (original, confirmed base — unchanged)

This has now been rewritten against the **real, confirmed** 26.1.2 API —
pulled directly from your local Mojang-mapped Minecraft jar (via `javap`)
and Fabric's official docs (fetched live), not guesses.

## Confirmed real API changes applied in this rewrite
- `GuiGraphics` → `GuiGraphicsExtractor`, with `drawString`/`drawCenteredString`
  replaced by `text(...)`/`centeredText(...)`. `Screen#render(...)` was
  replaced by `Screen#extractRenderState(...)`.
- `ClickType` → `ContainerInput` (confirmed via `javap` on `AbstractContainerScreen`).
- Mouse input: `GuiEventListener` now takes a `MouseButtonEvent` record
  (`x()`, `y()`, `button()`), not the earlier-guessed `Click` record.
- `HudRenderCallback` → `HudElementRegistry` + `HudElement` interface
  (confirmed via the real `fabric-rendering-v1` jar contents).
- `WorldRenderEvents` → `LevelRenderEvents`, and world rendering now requires
  a custom `RenderPipeline` plus a manual extraction/drawing split — rewritten
  following Fabric's official "Rendering in the World" doc for 26.1.2
  (https://docs.fabricmc.net/develop/rendering/world), including the required
  `GameRenderer#close` cleanup mixin.
- `net.minecraft.util.FormattedText` → `net.minecraft.network.chat.FormattedText`.
- Yarn-only `ContainerScreen` type replaced with real `AbstractContainerScreen<*>`.

## Next step
Run `.\gradlew build --info`. Paste any errors and I'll fix them precisely —
given how much of v1.0 was directly verified against your real jar, most
remaining issues should be confined to the v1.1 spots listed above.
