# TODO

Baseline: `./gradlew build` green, working tree clean, 42 modules registered,
15/15 mixins registered. Everything below is work found by reading the code,
not guesswork.

Status legend: `[ ]` open, `[~]` in progress, `[x]` done.

---

## P0 — controls wired to nothing

- [x] **`autoTerminalNoBreak` / `autoTerminalBreakThresholdMs` — rows dropped.**
      Both rows are gone from `gui/AsthoonLiteScreen.kt`; the `Config.Data`
      fields and accessors stay (Gson-persisted, and nothing reads them so
      removing them buys nothing) but are marked unwired at the accessor.
      `dungeon/AutoTerminal.kt` no longer advertises break-threshold
      protection in its class comment. `Config.applyRsmAutoPreset()` still
      writes both values — harmless, but it is the one remaining reader.
- [ ] **`dungeonMapWindowX/Y` — the comment lies.** `Config.kt:892` says "the
      map window drag writes these". Nothing does. `overlay/MapOverlayWindow.kt:86`
      always repositions to `defaultLocation(...)`, and `:100-107` hardcodes
      `(20, 20)`. Pick one: add drag handling that persists the position, or
      delete the two fields and the comment.
- [ ] **Four dead `Config.Data` fields + accessors**: `dungeonMapWindowX`,
      `dungeonMapWindowY`, `espExternalOverlay`, `starMobOutlineOnly`.
      Repo-wide grep finds hits only inside `Config.kt`. `starMobOutlineOnly`
      defaults `true` and `StarMobESP.kt:190-196` reads 12 other `Config.*`
      values but not this one. Note: `Config.Data` is Gson-persisted — remove
      only if we accept the silent reset, otherwise leave the field and wire
      it up.

## P1 — docs out of step with the code

- [ ] **`PORTING_NOTES.md` needs a rewrite or retirement.** Specific stale
      claims:
      - references `dungeon/DungeonRoomScanner.kt` four times — that file is
        now `dungeon/map/DungeonMapScanner.kt`.
      - says 8 puzzle solvers "weren't ported". All 8 exist under
        `dungeon/solvers/` plus `LividSolver`, registered at
        `AsthoonLite.kt:95-102`, toggled at `AsthoonLiteScreen.kt:621-636`.
      - says floor-type detection was left out. `dungeon/DungeonContext.kt`
        does it and `DungeonRegressionCheck.kt:67-86` tests F7/M3 parsing.
      - says "every `Boolean` in `Config.Data` defaults `false`". At least 17
        default `true` (`dungeonMapAlwaysShow`, `dungeonMapFullGrid`, the
        `autoTerminal*` set, `starMobOutlineOnly`, …).
      - names identifiers that don't exist: `AsthoonLite.hitboxFixEnabled`,
        `Config.hitboxFixEnabled`, `EtherwarpOverlay.enabled`,
        `MixinLivingEntity`, `higherLowerReversed`.
      - ends with a leftover "run `.\gradlew build --info` and paste the
        errors" chat instruction.
- [ ] **`docs/ARCHITECTURE.md` corrections:**
      - `:28` and `:130-131` say all mixins are Java under
        `src/main/java/...`. Four are Kotlin under
        `src/main/kotlin/com/asthoonlite/mixin/` (`MixinScreen`,
        `MixinHandledScreen`, `MixinGameRenderer`,
        `MixinClientPacketListener`).
      - `:12-34` repo tree omits the `overlay/` package entirely, and omits
        `ArrowAlignSolver`, `TerminalClickOrder`, `TerminalCursor`,
        `SecretAura`, `RelicAura`, `SecretSounds`, `DungeonServerTick`.
      - `:17` says `AslCommand` handles toggles and diagnostics. It only
        opens the GUI (43 lines).
      - `:163-165` puts Arrow Align under `dungeon/solvers/`. It lives at
        `dungeon/ArrowAlignSolver.kt`.
      - `:183-186` harness coverage list is missing four blocks that exist:
        terminal identification/candidates/click order, map decoration
        binding, legit map base, terminal pointer motion and flight timing.
        The harness's own summary string at `:713` is the accurate one.
- [ ] **Version strings disagree.** GUI badge says `v1.2`
      (`gui/AsthoonLiteScreen.kt:974`), `PORTING_NOTES.md` heading says
      `v1.3`, `gradle.properties` says `mod_version=1.0.0`. Pick one source
      of truth and derive the others.

## P2 — regression harness coverage

Rule from `AGENTS.md` §3: add a check for any pure logic changed. Right now
one file (`DungeonRegressionCheck.kt`, 160 checks) covers roughly 10 of ~80
main-source files. Easiest wins first — all pure, no game needed:

- [ ] `dungeon/TerminalClickOrder.kt` — `pickNearest` (:41),
      `countNeighbors` (:65), `distanceSqr` (:75). Pure `Int` math, and it is
      the exact function both `AutoTerminal.kt:359` and `TerminalSolver.kt:213`
      delegate "which pane next" to. Highest value per line of test.
- [ ] `dungeon/solvers/PuzzleUtils.kt` — `rotate` (:8), `getRealCoord` (:18),
      `getRoomCenter` (:23).
- [ ] `utils/MathUtils.kt` — `linReg` (:11), `lerp` (:41), `rescale` (:43).
- [ ] `dungeon/api/Coordinates.kt` — `isValid`, `isValidRoom`, `toWorld`.
      `ARCHITECTURE.md:160-162` calls this "a good place to unit-test" and it
      has never been done.
- [ ] `funny/AutoClicker.kt` — the fractional click accumulator (:47-55),
      the thing that makes 500 CPS work and the thing `Config.kt:845-847`
      points at. Pure arithmetic.
- [ ] `dungeon/TerminalHelper.kt` — `isSelected` (:19), `matchesColor` (:35),
      `rubixColorIndex` (:141). The harness already bootstraps registries and
      builds `ItemStack`s (:50-56), so this is directly addable.
- [ ] `dungeon/solvers/IceFillSolver.kt` — `IceFillPuzzle.solve()` (:275) is a
      pure DFS/cost search; the outer `solve()` (:72) needs a level. Test the
      inner one.
- [ ] `dungeon/ArrowAlignSolver.kt` — `getClicks` (:83) is `(8 - current +
      target) % 8` and is `private`. Either widen visibility or go through
      reflection (harness already does reflection for `StarMobESP` at :241).
- [ ] `dungeon/solvers/IcePathSolver.kt` — `buildGrid` (:131), `slide` (:193),
      `solveBFS` (:161). All pure grid search, all `private`.
- [ ] `dungeon/solvers/TeleportMazeSolver.kt` — `calcPadAngles` (:130),
      `getAngleDiff` (:151).
- [ ] `dungeon/solvers/CreeperBeamSolver.kt` — validate the hardcoded
      `rawSolutions` table (:23-35) against
      `assets/asthoonlite/dungeons/puzzles/creeperBeamsSolutions.json`, which
      currently has zero references repo-wide. Either load the JSON like
      `WaterBoardSolver.kt:36` and `BoulderSolver.kt:28` do, or delete it.
- [ ] `funny/WeaponAutoClicker.kt` — `isQualifyingWeapon` (:129);
      `funny/InventoryAutoClicker.kt` — `isStashItem` (:237).
- [ ] `dungeon/api/mapEnums/*.kt` (5 files) — pure data, untested.
- [ ] `dungeon/SecretHitboxes.kt` geometry is covered; the lever-family path
      is not (see P3 below).

## P3 — dead code and small cleanups

- [ ] **Orphaned functions, zero call sites:**
  - `dungeon/SecretHitboxes.kt:165` — `isLeverHitboxEnabled`. Note the
    asymmetry: the button/skull/mushroom variants at `:182-184` and `:422-424`
    *are* called. The lever variant was orphaned.
  - `funny/InventoryAutoClicker.kt:258` `hasStackPickupLore`,
    `:271` `hasPickupLore`
  - `dungeon/SecretAura.kt:121` `tickI1Targets`
  - `dungeon/DungeonContext.kt:247-253` — `roomKey`, `currentRoomKey`,
    `sameRoom`. The whole cluster is unreachable.
  - `pet/PetTracker.kt:230` `isActivePet`
  - `overlay/MapOverlayWindow.kt:53` `enabled()`
- [ ] `dungeon/DungeonMap.kt:30` — `MARKER_ATLAS` declared, referenced once,
      never used. The atlas loads properly in `render/MapCanvas.kt:115`.
      Leftover from before the canvas refactor.
- [ ] `build.gradle` does `jar { from("LICENSE") }` — there is no `LICENSE`
      file. Gradle no-ops; the jar ships without it.
- [ ] `command/AslCommand.kt:18` comment says "use scheduleStop to open
      AFTER the current tick"; the code uses a raw daemon `Thread` and
      `Thread.sleep(50)` at `:20-37`. Make them agree.
- [ ] `asthoonlite.mixins.json:5` — `compatibilityLevel: "JAVA_21"` while
      `build.gradle` sets `options.release = 25` and `jvmToolchain(25)`.
      Re-validate against the shipped Mixin version before touching.
- [ ] Kotlin plugin `2.3.21` (`build.gradle`) vs
      `fabric_kotlin_version=1.13.13+kotlin.2.4.10` (`gradle.properties`).
      Confirm the skew is intentional.
- [ ] `gradle.properties` has `maven_group=com.asthoonLite` (capital L) vs
      package `com.asthoonlite`.

## P4 — GUI reachability

- [ ] **Six HUD position/scale groups have no UI and no editor screen:**
      `dungeonTimerX/Y`, `dragonHudX/Y`, `maskHudX/Y`,
      `pickaxeTimerX/Y/Scale`. `pet/PetHudEditorScreen.kt` is the only
      position editor in the mod. These are hand-edit-the-JSON only today.
      Either generalize `PetHudEditorScreen` into a shared drag editor, or
      add plain X/Y sliders per group.
- [ ] **Six star-mob colours have no UI**: `starMobColor`,
      `starMobChonkColor`, `starMobFelColor`, `starMobMinibossColor`,
      `starMobSmColor`, `starMobShadowAssassinColor` (Config.kt:66-71), read
      at `StarMobESP.kt:190-196`. `PORTING_NOTES.md:24-25` already flags
      "flip the hex in asthoonLite.json for now, or ask for a colour-picker
      row". Worth deciding.
- [ ] **`autoTerminalMelodyMessage`** (Config.kt:123) has no text-input row.
- [ ] **Duplicate rows:** `roomClearAlertEnabled` appears at
      `AsthoonLiteScreen.kt:583` (Dungeon ▸ General) and `:688`
      (Dungeon ▸ Secrets). Same field, two places.
- [ ] **`SectionHeader` titles are keyed by string, not by tab**
      (`Config.kt:226`). `"Presets"` at `:391` (Map) and `:454` (Terminals)
      collide; `"Shortcuts"` at `:576` and `:681` collide. Folding one folds
      the other.
- [ ] **`instantSimonSaysEnabled` sits on the Funny tab**
      (`AsthoonLiteScreen.kt:771-773`) while its sibling Simon Says switches
      sit under Dungeon ▸ F7/M7 ▸ Devices & Terminals (`:671-676`).
- [ ] **Inconsistent `save()` discipline.** Accessors with no `save()`:
      `petDisplayX/Y/Scale`, `dungeonMapX/Y/Scale`, `dungeonTimerX/Y`,
      `dragonHudX/Y`, `maskHudX/Y`, `pickaxeTimerX/Y/Scale`. The GUI papers
      over some of it — `dungeonMapX/Y/Scale` sliders call `Config.save()`
      explicitly (`:412, 416, 420`) but `dungeonMapPlayerHeadScale`
      (`:425-427`) and `dungeonMapMarkerScale` (`:430-432`) write without it
      and rely on `onClose()` (`:1166`). A crash before Done loses them while
      neighbouring sliders persist immediately.

## P5 — log noise

- [ ] **38 `[AsthoonLite-Debug]` sites at info/warn**, several on hot paths.
      Candidates for demotion to debug level or removal:
      - `dungeon/StarMobESP.kt:251` — `joinToString`s every mob on every
        render frame while the highlight is on.
      - `dungeon/DungeonMap.kt:91` — logs rooms/doors/icons/position every
        60 ticks while the map renders.
      - `dungeon/map/DungeonMapScanner.kt:127` — logs every map packet.
      - `dungeon/DungeonContext.kt:171` — dumps the whole sidebar on every
        scoreboard update.
      - `mixin/MixinClientPacketListener.kt:73` — logs every
        `handleMapItemData`.

---

## Open questions

1. **`PORTING_NOTES.md` — rewrite or retire?** It documents the porting
   journey (v1.0 → v1.3) and most of it is now history rather than a
   reference. Options: fold the still-true parts into `ARCHITECTURE.md` and
   delete it, or rewrite it as an accurate current-state document.
2. **Dead `Config.Data` fields — delete or wire up?** Gson will silently
   drop them from users' existing files either way. My read: `espExternalOverlay`
   looks like a never-built external-overlay companion and should be built or
   dropped; `starMobOutlineOnly` should be wired (StarMobESP already draws
   outlines); `dungeonMapWindowX/Y` should only survive if we add real window
   dragging.
3. **Colour picker for the six star-mob colours** — build one, or leave them
   as JSON hex edits?
4. **HUD drag editor** — generalize `PetHudEditorScreen` so timers / dragon /
   mask / pickaxe positions are all draggable, or is the JSON hand-edit fine?
5. **`autoTerminalNoBreak` + break threshold** — implement the break logic in
   `AutoTerminal`, or remove both rows? This one is a real behaviour question,
   not just cleanup.
6. **Version source of truth** — should `gradle.properties` drive the GUI
   badge so they can't drift again?
7. **Harness scope** — how far do you want P2 taken? I listed ~13 pure-logic
   additions. Doing all of them is maybe one focused pass; say the word and
   I'll start at `TerminalClickOrder` and work down.

---

## Window-capture intent — what quiet mode is for

Written down so nobody has to re-derive the bar later, and so a future change
doesn't quietly move it.

**The bar.** A single-window capture of the game must look like an ordinary
Skyblock QoL mod. Not "unmodded" — ordinary. Every accepted mod draws
something; none of them draw these boxes.

**The scope.** Window capture only. One game window, one recording. Not a
desktop capture, not a clip of the whole screen, not a log-file review. That
single assumption is what makes everything below true, and it is the reason
nothing here touches disk, log scrubbing, or the external overlay. A window
capture never sees another window, so the overlay needs no gate. Extending the
bar to desktop capture would be a different design, not a bigger version of
this one.

**What quiet mode takes out of the frame:** world-space boxes, world-space
text, HUD elements, the selection outline override, the inventory slot
highlights. Solvers keep solving and the enlarged hitbox keeps working — only
the output stops reaching the frame.

**What stays in, deliberately:** the drawn terminal pointer, the solver chat
lines, sounds, star-mob glow and highlights. Every one of those matches what
an accepted mod already does — Starred Mob Glow, Score title and sound, Block
Incorrect Terminal Clicks, the Secret Chime. The pointer in particular is a
drawn cursor replacing the real one for a container's whole life, which reads
as a hand doing the work. Gating any of these would push the window *further*
from the target than leaving them alone.

**The settings screens are closed, not hidden.** A menu that renders nothing
while still eating input is a trap. They are closed on the rising edge of the
flag instead — once, on the way in. `/asl` is deliberately **not** blocked
while quiet mode is on: with no keybind bound, blocking it would strand the
settings behind a flag with no way to clear them, and there is no path that
opens a menu without a person asking for it. The toggle also logs to the
console rather than printing an overlay line — a message announcing quiet
mode would be the one line of the capture that gives it away.

**Recovery.** Quiet mode lives in Config, so a restart keeps it. Leave it on
is the safe direction to fail: worst case a session with no overlays, never a
session with overlays that should have been off. Turn it off with the keybind,
or `/asl` if the keybind is unbound.

**Known residuals, on purpose:**

- A menu opened on purpose while quiet mode is on stays open until it is
  closed. Deliberate is not accidental, and closing on every tick would make
  the menu unreachable.
- Chat lines from solvers are text in the frame. They are also what every
  other mod prints.
- Anything that reads disk — config file, screenshots, crash reports — is
  outside this bar by the scope above.

**Checks.** `TerminalCursor.shouldHideRealCursor` truth table,
`TerminalCursor.rawFromScaled` round-trip against `MouseHandler.getScaledXPos`,
and `TerminalCursor.lingerOnScreen` (the parked pointer clearing its window)
are covered in `DungeonRegressionCheck.kt`.

