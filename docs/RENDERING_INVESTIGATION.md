# Rendering investigation: Minecraft 26.1.2

Investigation date: 2026-10-11. Baseline: upstream commit `7166c9d`, on
`codex/linux-rendering-and-gui-fixes`. This report covers the two map layers,
custom terminal grid, terminal pointer, star-mob highlights, interaction
hitbox visuals, and settings sizing. It records source findings separately
from native-window or GPU presentation evidence.

## Evidence and scope

The supplied recording shows the QOL **Quiet Mode** row green/enabled at
approximately five seconds. `QuietMode.suppressing()` reads that saved flag.
Gameplay maps, the terminal grid, world boxes/text and slot highlights check
it before producing output. The editor preview deliberately remains usable.
That configuration explains the missing gameplay output in the supplied
footage; a working editor is not evidence that the gameplay visibility gates
passed. It does not establish a Linux renderer failure.

The terminal pointer is an explicit Quiet Mode exception in `QuietMode.kt`.
Its missing software image must be investigated independently. Settings now
show a live footer identifying the hidden map, terminal GUI and world
highlights. This feedback appears only inside settings; engaging Quiet Mode
still dismisses the mod's open settings screens on its rising edge.

| Finding | Evidence level | What it establishes |
| --- | --- | --- |
| Quiet Mode enabled in the recording | Supplied visual evidence plus source | Intentional suppression explains several simultaneous symptoms |
| Full Map Overlay depended on an unexposed legacy switch | Source/history | The visible option did not reliably select its documented desktop route |
| An incomplete initial map packet permanently installed guessed geometry | Source plus focused regression case | Later entrance pixels/floor information could not repair calibration |
| Devonian checkmark paths contained uppercase characters | Reproduced Windows fixture crash plus source/history | HUD texture Identifier creation failed after real map calibration |
| Minecraft Main forced AWT headless even on Windows | Live diagnostics and exact client/JDK bytecode | Normal startup prevented creation of the desktop map window |
| SELECT practice grid contained items but showed gray empty tiles | Windows baseline observation reported by the runtime operator, capture state and source | The tile icon predicate excluded the puzzle's visible information |
| Default pointer style used native cursor warping without backend qualification | Source/history plus GLFW contract | That path cannot move a normal GUI cursor on native Wayland |
| Repeated cursor aim reset the flight before target deduplication | Windows practice stalled at 0/8 for over 70 seconds, capture state and upstream source/history | The pointer's movement gate could never release an automatic click |
| Settings minimum exceeded small GUI viewports | Arithmetic, exact client bytecode and regression cases | Panel content/buttons could lie outside the window |
| Shared box renderer is universally broken on Linux | **Unconfirmed** | No Linux/KDE run or matching GPU failure has established this |

The project declares Minecraft `26.1.2`, Fabric Loader `0.19.3`, Fabric API
`0.155.2+26.1.2`, Loom `1.17.19`, Kotlin compiler `2.3.21`, Fabric Kotlin
`1.13.13+kotlin.2.4.10`, and JDK 25. Cached modules inspected include
`fabric-screen-api-v1 5.1.0+981dd9b24c`,
`fabric-rendering-v1 23.3.1+e9207d814c`, and LWJGL `3.4.1`. The inspected
Prism JDK installation is Microsoft OpenJDK `25.0.1+8-LTS`; the Windows
runtime diagnostics report Java `25.0.1`. LWJGL's wrapper
version does not establish the loaded native GLFW version. Diagnostics report
the latter and the active GLFW backend. The official
[LWJGL 3.4.1 release](https://github.com/LWJGL/lwjgl3/releases/tag/3.4.1)
was consulted without assuming its release alone explains the symptoms.

## 1. Dungeon map layers

**Expected path.** `AsthoonLite.onInitializeClient()` registers `DungeonContext`
and `DungeonMap`. A normal scoreboard identifies the dungeon; map-item packets
feed `DungeonMapScanner`; the registered HUD element checks its visibility
gates and draws through `MapCanvas`. Devonian and Noamm are styling choices
for the same data. The explored HUD layer and advanced Full Map Overlay are
the two destinations, independent of style.

**Confirmed defects and changes.** `DungeonMap.extractRenderState()` formerly
combined `dungeonMapFullGrid` with `dungeonMapExternalWindow`, although the
legacy field has no settings row and defaults false. The newer
`renderRoute(fullGrid, overlayAvailable)` makes the exposed Full Map Overlay
option own the route and retains the legacy field in serialized config.
Supported desktop output keeps the explored HUD underneath the advanced
window; unsupported desktop output draws the advanced content in the HUD.
The routing defect explains the missing expected desktop layer, rather than
proving both gameplay layers were blank.

`e8f6cc6` also installed a 16-pixel room/20-pixel gap fallback after a failed
first dimension scan. Once `roomSize` was set, later real entrance data no
longer triggered initialization. The replacement `ensureMapDimensions()`
keeps incomplete data pending, derives dimensions from a real square entrance,
and recalibrates when the detected floor changes. A focused test begins with
an empty buffer and then supplies a 12-pixel entrance, proving that the old
guessed geometry is not latched. This defect concerns room contents and
alignment; if visibility gates pass, an empty scanner alone does not explain
the absence of the map's background/border.

The dedicated singleplayer fixture then exposed a separate, platform-independent
crash. At `17:14:11`, normal sidebar data activated F7 and the actual saved-map
pixels calibrated to 16-pixel rooms, a 20-pixel gap and origin `(5,5)`. At
`17:14:12`, drawing a green checkmark threw `IdentifierException` for
`asthoonlite:textures/map/devonian/greenCheck.png`. The stack reaches
`HudMapCanvas.image`, `DungeonMapStyles.drawRoomInfo`, the registered Fabric HUD
element and deferred subtitles; it establishes that these callbacks executed
and the gameplay map gates passed. The preserved local log is
`build/rendering-evidence/fixture-map-resource-crash.log`.

Commit `c2dde2dffa4d2f1c3028eadc21adf3984f645d33` (`port map styles and fix
terminal rendering`, 2026-10-08) introduced all four camelCase Devonian PNG
names and their drawing references: `greenCheck`, `whiteCheck`, `failedRoom`
and `questionMark`. Java2D loaded those paths directly from the classpath,
but Minecraft texture Identifiers require lowercase `[a-z0-9/._-]` paths.
The assets and shared drawing references now use `green_check`, `white_check`,
`failed_room` and `question_mark`. Both HUD and AWT replay consume these same
paths; the fix does not suppress the exception or replace the map with a preview.
The existing sprite regression constructed paths by hand and only exercised
Java2D loading, so it missed the HUD validation failure. Its replacement records
the actual shared checkmark draw operation for every checkmark and both styles,
constructs a Minecraft Identifier, verifies the bundled texture exists, and
replays it into Java2D to check nonempty output. Marker resources are checked too.

**Native desktop behavior.** Native GLFW Wayland does not provide the global
game-window position required to align a separate AWT window over the HUD.
The desktop route now reports unavailable and keeps advanced content in the
HUD. XWayland reports the X11 GLFW backend. This distinction follows
[GLFW 3.4 window positioning](https://www.glfw.org/docs/3.4/group__window.html),
not the operating-system name or `XDG_SESSION_TYPE` alone.

The next live Windows diagnostics at `17:19:08` reported `AWT headless` despite
an interactive game window. Exact Minecraft `26.1.2` `Main.<clinit>` bytecode
unconditionally sets `java.awt.headless=true`; this is not evidence that the
machine lacks a desktop. JDK 25 `GraphicsEnvironment.getHeadlessProperty()`
caches its result on the first query. A preliminary `isHeadless()` guard would
therefore preserve the forced value, and changing the property afterward
cannot repair that cache through the public API. The inspected vanilla client
classes contain no AWT, Swing or ImageIO initialization before the client mod
initializer; dedicated-server GUI classes are separate.

`MapOverlayWindow.bootstrapDesktop()` now runs at the beginning of the client
initializer, before ASL's first AWT access. On Windows, macOS, and Linux with
a nonempty `DISPLAY` and an actual X11 GLFW backend, it clears the forced
property and lets the JDK determine real desktop availability. Linux waits for
the backend before the first AWT query; native Wayland keeps the property even
if an XWayland `DISPLAY` is present. An explicit JVM
`-Djava.awt.headless=true` request is retained. The regression harness never runs that bootstrap;
its pure policy checks leave the JVM property unchanged. Native GLFW Wayland
is rejected before querying AWT, and unavailable desktop/toolkit state retains
the logged HUD fallback. Other mods may initialize AWT earlier; that remains
a reported fallback, without reflection into JDK caches. Live verification
requires restarting Minecraft, and diagnostics record bootstrap status and the
current property. The caching behavior is directly visible in the official
[OpenJDK 25 GraphicsEnvironment source](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/java.desktop/share/classes/java/awt/GraphicsEnvironment.java).

`MapOverlayWindow` still publishes a completed operation list by one reference
write and touches Swing state on the EDT. Headless AWT and initialization,
painting, native-library or dispatch errors record the failure and release
desktop ownership so subsequent frames use the HUD. A supported backend whose
window manager refuses the requested location retains the documented top-left
desktop placement; that differs from native Wayland's HUD fallback. Java
documents that always-on-top support varies by platform, and EDT dispatch is
explicit in `EventQueue.invokeLater`. Neither API promises compositor-visible
pixels. See [JDK 25 Window](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/java/awt/Window.html)
and [JDK 25 EventQueue](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/java/awt/EventQueue.html).

**Measurements and checks.** `MapRenderingRegressionCheck.kt` covers every
gameplay gate, routes, late entrance data and late floor detection. Existing
canvas checks cover recorded operation replay, transforms and Java2D geometry.
Map diagnostics report the current gate, extraction age/count, route,
rectangle, map bytes/nonzero pixels, dimensions, rooms and doors. Desktop
diagnostics separately report publications, paints, paint age, bounds, toolkit,
DPI, placement rejection and failures. A growing publication count with no
paints localizes the issue to desktop dispatch/painting; successful paints
still require checking actual desktop placement and visibility.

The HUD registration matches the versioned
[Fabric 26.1.2 HUD documentation](https://github.com/FabricMC/fabric-docs/blob/main/versions/26.1.2/develop/rendering/hud.md).
The editor calls the shared drawing routine directly, while gameplay adds
dungeon, map-held, boss, F1 and Quiet Mode gates. The fix preserves those
requirements rather than placing a preview into gameplay.

## 2. Custom terminal grid

**Expected path.** A recognized Minecraft container reaches
`MixinScreen.extractRenderStateWithTooltipAndSubtitles`, which owns the custom
frame when `TermGui.active()` passes. `TermGui` reads the live menu and draws
its centered grid; its layout also supplies pointer targets and mouse hit
tests. `MixinHandledScreen` routes input to `TerminalInput`. Practice uses a
local container screen; this feature does not launch a shell or terminal
emulator process.

**Confirmed causes and changes.** `e8f6cc6` separated `terminalSolverEnabled`
from `termGuiEnabled`, but the Terminal Solver description still promised a
custom GUI. The description now states that it highlights the next input;
the independent Custom Terminal GUI option controls the grid. Quiet Mode
intentionally rejects the custom grid, including in the supplied footage.

During the Windows baseline, SELECT practice had populated item data in its
capture but showed blank gray tiles. The icon predicate explicitly excluded
SELECT and STARTS. Their identities/colors are the puzzle information, so
gray backgrounds plus hover names were insufficient for the intended manual
interface. `d0ccd9d` had added practice icons; `2dc61b7` explicitly removed
SELECT/STARTS icons and retained their hover names. The reason for the removal
is not independently established by its commit message. The corrected
`drawItems()` policy shows nonempty SELECT/STARTS choices in both live and
practice menus, regardless of solver state, while preserving the previous
policy for other terminal types. `tileBackground()` retains the gray neutral
base or existing hint color, so gray tiles and item icons coexist. Hover names
follow the same icon policy.

**Hook investigation.** The exact cached Minecraft `Screen` bytecode and
Fabric screen module were inspected. Fabric's `GameRendererMixin.onExtractGui`
wraps the screen call with before/after extraction events. ASL's HEAD
cancellation still returns through that wrapper. Adding a second after-event
dispatch would duplicate pointer extraction and the input clock; no such
dispatch was added. The Java mixins are listed in `asthoonlite.mixins.json`.
There is no source evidence of a Linux-only missing screen hook. The module
source is in the versioned
[Fabric screen mixin directory](https://github.com/FabricMC/fabric-api/tree/26.1.2/fabric-screen-api-v1/src/client/java/net/fabricmc/fabric/mixin/screen);
the cached binary is authoritative for this resolved build.

**Measurements and checks.** Diagnostics distinguish `custom-gui-disabled`,
`quiet-mode`, `unrecognized-title`, `menu-incomplete`, and `ready`; they record
required/live slot counts, grid bounds, extraction attempts, tiles enqueued
and errors. Existing layout checks and new presentation checks cover shared
gates, slot requirements, all icon-policy combinations, empty slots and
neutral/hint tile colors. A populated board with a `ready` gate and
nonzero extracted tiles narrows a remaining blank-grid failure to visual
content or final GUI rendering; an extraction counter alone is not success.

## 3. Terminal pointer and native cursor

**Expected path and previous attempt.** `ScreenEvents.AFTER_INIT` attaches an
`afterExtract` handler to containers. `AutoTerminal` advances its clock;
`CursorMotion` produces positions; `TerminalCursor.render()` chooses a
presentation, submits its image/trail, and manages native cursor ownership.
`99a5041` changed Default style (`autoTerminalCursorStyle == 1`) to call
`glfwSetCursorPos`, release software hiding and return before drawing a
software arrow. The absence of a custom image in that mode was deliberate
on supported backends. On native Wayland, however, normal/hidden GUI cursor
warping is unsupported, leaving that branch without moving native output or
a replacement. The recording's saved style/backend is needed before assigning
this cause to its exact pointer symptom.

GLFW documents the normal-cursor warp restriction and main-thread/focus
requirements in its [3.4 input reference](https://www.glfw.org/docs/3.4/group__input.html).
The rejection also appears in
[`_glfwSetCursorPosWayland`](https://github.com/glfw/glfw/blob/3.4/src/wl_window.c).
The appropriate software-cursor mode is `GLFW_CURSOR_HIDDEN`; gameplay camera
capture uses `GLFW_CURSOR_DISABLED`, which has different semantics. See the
[GLFW 3.4 cursor-mode guide](https://www.glfw.org/docs/3.4/input_guide.html#cursor_mode).

**Changes.** `presentation()` qualifies Default native motion by the actual
GLFW backend: Win32/Cocoa/X11 use native motion; native Wayland uses a software
arrow during a glide and returns the user's native pointer while idle. Osu
style remains software-drawn between clicks as well. Hiding uses the native
HIDDEN mode instead of a transparent cursor object, with mode readback and
ownership tracking. The software primitive must be extracted, intersect the
viewport and pass clipping checks before hiding is attempted. This is the
observable guarantee available in the extraction hook, not proof of GPU
presentation. Texture extraction failure records a diagnostic and uses the
arrow spans.

Cleanup covers deactivation, focus loss, iconification where the backend
reports it, gameplay grab, terminal/menu replacement, screen removal,
disconnect, client stop and extraction errors. Restoration only changes a
hidden mode acquired by this module; it preserves another owner's hidden or
captured mode and Minecraft's camera grab. Failed restoration retains
ownership for retry. Native Wayland handback does not issue another rejected
warp.

**Automatic input stall.** A separate Windows practice run beginning at
`17:23:50` remained at RUBIX `0/8` for over 70 seconds. Its capture contained
drawn-pointer frames but no queued automatic clicks. Source inspection found
that upstream `99a5041` unconditionally called `motion.reset(from)` in
`TerminalCursor.glideTo()` before `CursorMotion.glideTo()` checked its
existing target. `AutoTerminal` aims every tick and rendered frame, so every
observation restarted the dwell/travel/settle clock. The pointer could
approach a pane while `pointerBlocksClick()` still saw movement forever.
This platform-independent regression predates the current rendering changes.
Human mode uses built-in timing distributions; it requires no learned profile.
The saved Break Threshold and No Break fields are explicitly unwired, so the
observed 550 ms Click Delay versus 500 ms Break Threshold did not cause this stall.

`prepareGlide()` now checks the remembered target before resetting native
idle position or sampling new trip settings. The same aim preserves its
original landing time, including after landing. A changed target during
flight starts at the current sampled position; a new Default-style trip
while idle reads the physical cursor once. Terminal reset clears that target.
The shared preparation path is exercised with per-frame aim, native/software
seeding, dwell, overshoot, settling and the real `AutoTerminal` clock/pointer
gate: a 550 ms trip releases its due click at 550 ms instead of restarting.
The check also pins continuous mid-flight retargeting and new native idle
origins. Final build and patched runtime results are recorded separately below.

The later instrumented Windows run exposed another lifecycle defect: at
`17:55:04`, seven completed boards had accumulated 667 successful hides and
667 restores. During the one-second completed-board delay, `AutoTerminal.reset()`
released ownership every frame and pointer extraction immediately hid it again.
Inactive automation also reset the manual follower's ownership. Completed
practice boards now reject pointer presentation, and inactive automation clears
only its clock/solver state while the manual follower updates position without
resetting native ownership. The final run below recorded one successful
hide/restore pair for each of five opened boards instead of this per-frame churn.

**Measurements and checks.** `TerminalPresentationRegressionCheck.kt` covers
backend/style policy, inactive gates, ownership restoration, nonfinite/offscreen
positions and actual sparse arrow visibility. Diagnostics report the GLFW
version/backend, requested/observed mode, focus, grab, ownership, extraction
and software-enqueue counts, native warp requests, outcome, release reason
and errors. They are not a compositor test. The baseline desktop image also
contained an external `ParsecCursor` overlay, which cannot establish whether
Minecraft's native cursor was hidden. Use an in-game F2 frame for the software
image plus native-mode diagnostics for hiding, and an uncontaminated desktop
recording for the final user-visible result.

## 4. Star-mob highlights and shared world drawing

**Expected and actual source path.** `StarMobESP` reads named entity metadata
and scans loaded starred armor stands every five ticks as a fallback. It
associates a living entity, removes dead/invalid/cleared entries, and checks
feature, dungeon, Quiet Mode, room and sight conditions before queueing boxes
during `END_EXTRACTION`. `WorldBoxRenderer` clears the queue first in
registration order, then consumes it at `AFTER_TRANSLUCENT_TERRAIN`.

The exact Minecraft bytecode confirms custom-name metadata ID 2 and the
current extraction signatures. The shared renderer follows the official
[Fabric 26.1.2 custom pipeline example](https://github.com/FabricMC/fabric-docs/blob/main/reference/26.1.2/src/client/java/com/example/docs/rendering/CustomRenderPipeline.java):
camera-relative matrices, position/color quads, ring-buffer upload, sorted
indices, default uniforms, DynamicTransforms and indexed submission. The
event separation matches
[Fabric 26.1.2 LevelRenderEvents](https://github.com/FabricMC/fabric-api/blob/26.1.2/fabric-rendering-v1/src/client/java/net/fabricmc/fabric/api/client/rendering/v1/level/LevelRenderEvents.java).
Agreement with these APIs does not certify final GPU pixels.

**Previous attempts and limits.** `e8f6cc6` adjusted cleared-room/room and
line-of-sight filtering and render-target handling. `e9d3721` migrated mixins
and explicitly specified LESS_THAN_OR_EQUAL with no depth write on the
normal box pipeline. Cached `RenderPipelines` bytecode already gives
`DEBUG_FILLED_SNIPPET` that state, so this last change did not establish a
Linux repair. The migration exists and targets valid methods; there is no
evidence that repeating it would fix the current symptom.

The investigated [Iris issue #3137](https://github.com/IrisShaders/Iris/issues/3137)
reports missing sampler bindings with PBR shaders on Minecraft 26.1.2 and a
Linux Mesa setup. ASL's position/color box pipeline declares no texture
samplers, so that failure signature does not justify copying an Iris fix or
blaming Mesa here. Shader interference remains a runtime comparison, not a
confirmed cause. No permanent box-pipeline rewrite was made without that
evidence.

**Measurements.** Detection diagnostics count named/starred packets, fallback
scans, starred stands, tracked mobs and rejection reasons. Renderer diagnostics
count extraction and draw callbacks, queue sizes, submitted passes/vertices/
indices, selected targets and exceptions. Rendering exceptions remain
exceptions rather than being swallowed. World text separately reports queued
and flushed entries. Quiet Mode in the supplied recording explains suppression;
any failure with Quiet Mode off needs these stages measured on the failing
client.

## 5. Interaction hitbox visuals and settings

Interaction expansion, the vanilla selection outline, and colored world
visuals are three different paths. `MixinBlockStateShape` changes the picking
shape only for nonempty interaction contexts. `MixinLevelRenderer` wraps the
shape read for selection drawing, while colored control/pressed visuals queue
through `WorldBoxRenderer`. The exact `LevelRenderer.extractBlockOutline`
bytecode contains the wrapped `getShape(BlockGetter, BlockPos, CollisionContext)`
call; no stale outline target was found. Quiet Mode suppresses colored boxes
and gates the custom selection-outline override while the interaction feature
continues to work; this does not imply that vanilla draws no selection outline.

The current geometry/regression checks preserve mounted button depth, clamp
expansion inside the cell, keep 0% vanilla, and ensure monotonic expansion.
The investigation adds on-demand enabled/dungeon/anywhere/Quiet Mode flags,
tick/extraction gates, tracked controls, queued visual counts and outline
settings. It does not alter shape geometry as a speculative rendering fix.
The hitbox module's control/pressed counts describe requested queue calls;
the shared renderer can reject them under Quiet Mode. Use its draw gate and
submitted vertex/index counts to establish whether those requests reached a
draw pass. Cumulative counters can include earlier frames, so correlate the
latest gate/outcome and timestamps with the recorded interaction.

Settings formerly kept a 540-pixel panel and minimum height 360 regardless of
the actual scaled GUI viewport. At a 384×240 viewport, the panel origin was
negative in both axes. `GuiViewport` now fits a minimum 564×384 logical canvas
uniformly, retaining margins and the existing fixed panel layout. Render
hover, click/release/move/scroll coordinates and drag deltas share its inverse;
resize rebuilds the fit and cancels stale drags. Exact Minecraft bytecode
confirms `MouseButtonEvent(double,double,MouseButtonInfo)`,
`Screen.resize(int,int)`, and the transformed-scissor/native-widget-hover
mismatch. Content widgets therefore use opaque header/footer masks and reject
hidden clicks instead of testing logical mouse positions against transformed
scissors. The footer has separate room for its hint/banner above Done,
correcting the overlap also observed in the Windows baseline.

Exact `26.1.2` `Screen` bytecode also exposed a deferred IME composition edge:
the screen draws preedit text after the settings method has popped its fitted
pose. Leaving that deferred output unchanged would therefore separate the
composition text and native input rectangle from the scaled input widget.
The registered, read-only `GuiGraphicsExtractorAccessor` and
`IMEPreeditOverlayAccessor` mixins expose the pending overlay, shared
`GuiRenderState`, and its anchor/font dimensions; their exact targets were
verified with `javap`. A deferred adapter retains vanilla text/caret extraction
and ordering, shares the same render state, copies the fitted pose, and applies
edge clamps in the logical viewport. The native input rectangle is then
converted through the same fit once, rounding left/top down and right/bottom
up so the entire input area remains covered. This source/API fix is separate
from the ordinary Windows typing and resize observations: no live IME
composition was exercised.

`GuiViewportRegressionCheck.kt` covers 384×240, 427×240, 320×180, 540×360,
854×480 and 1280×720 GUI viewports, shadow bounds, inverse positions, drag
distances, unchanged roomy layouts and finite minimized-window behavior.
It also checks IME composition above/below the input, logical edge clamps,
long-composition placement and outward rounding of the transformed native area.
These are GUI-space sizes: a desktop capture can have different dimensions
because of Windows DPI scaling. Record Minecraft framebuffer, GUI dimensions
and GUI scale rather than inferring them from the capture alone.

## Verification record

### Source/API inspection completed

History, registration order, config gates and mixin declarations were reviewed.
`javap` inspected the cached unobfuscated Minecraft 26.1.2 jar and resolved
Fabric modules. The inspected jar in this checkout is
`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.1.2/minecraft-merged-043a8b3edf-26.1.2.jar`; use the
actual resolved cache path if it differs on another machine. Official versioned
Fabric, GLFW and JDK 25 references are linked above. No API from a different
Minecraft rendering generation was substituted.

### Build and regression results

The rendering and pointer-fix build completed at `17:31` on 2026-10-11 with
`.\gradlew.bat build --offline --console=plain`, using Microsoft Prism
`java-runtime-epsilon` Java `25.0.1` through `JAVA_HOME` and repository-local
`GRADLE_USER_HOME=.gradle/user-home`. The sources were on
`codex/linux-rendering-and-gui-fixes`, based on
`7166c9d8e17418b857ce7af5de5cd51657c65d41`, with the reviewed changes not yet
committed at verification time. The baseline used the same Gradle flags.

`build/linux-rendering-verified-build.log` records `BUILD SUCCESSFUL in 18s`,
seven executed tasks and two up-to-date tasks, successful compilation,
`regressionCheck`, `check`, and `copyJarToRoot`. The harness printed
`Dungeon regression checks passed`. Its coverage includes map visibility and
routing, incomplete/late calibration, every style/checkmark's actual texture
output and resource validation, desktop bootstrap policy, terminal pointer
target reuse/retargeting, presentation/ownership, and settings viewport input.
The JOML `sun.misc.Unsafe` and Gradle deprecation notices were warnings; no
compiler or harness failure remained.

The verified `17:31` repository root jar and installed verification jar have SHA-256
`252E3CA1350388F6003CE15E6ADE5E8FB1B6BE9F41450EE4FF1632B6ABE0BD2D`.
That identifies the build containing the AWT bootstrap and repeated-target
pointer fix. Compilation/regressions do not verify native cursor hiding,
AWT placement or GPU output.

The instrumented source build, including active cursor-mode counters and the deferred
IME adapter, used the same command and environment. Its preserved log is
`build/linux-rendering-final-build.log`: `BUILD SUCCESSFUL in 14s`, nine tasks,
eight executed and one up-to-date, with the regression harness and
`copyJarToRoot` successful. The repository root and installed jar matched
SHA-256
`DA6858B617C5AB931071CECAEA301D007D7ABEA433FE8D5A63422F3CEAE7A240`.
Minecraft was fully restarted on this jar at approximately `17:51`.
The earlier `252E3CA1…` observations below identify the already-tested rendering
and flight behavior. Instrumentation on this run exposed the cursor ownership
churn described above; this was an intermediate verification build.

The final source build additionally contains the completed-board presentation
gate and ownership-preserving manual follower. It completed at `18:04:38`
using the same command and environment. The preserved log
`build/linux-rendering-cursor-lifecycle-build.log` records `BUILD SUCCESSFUL in
11s`, nine tasks, seven executed and two up-to-date, including successful
compilation, the regression harness and `copyJarToRoot`. The final repository
root and installed verification jar have matching SHA-256
`040C566F13BA925DC2E7CFC44B3CE14711774FD4D60FD4C111CADECD79249291`.
Minecraft was fully restarted for the final runtime sequence below; startup
records loading ASL at `18:05:24`. No ASL mixin or rendering error was recorded.
The startup log does record an OpenAL device failure, separately from these
rendering checks.

### Windows runtime evidence on the 252E3CA1 build

The dedicated Prism instance is `Sky-Myce-Verification`, Minecraft `26.1.2`,
Fabric Loader `0.19.5`, Fabric API `0.155.3+26.1.2`, and Fabric Kotlin
`1.14.1+kotlin.2.4.20`. Runtime diagnostics report Windows 11, Java `25.0.1`,
GLFW `3.5.0 Win32 WGL Null EGL OSMesa VisualC DLL`, and the Win32 backend.
These installed versions differ slightly from the build declarations; the
loaded rendering and screen modules match the versions inspected above.
LWJGL's runtime banner reports `3.4.1-snapshot`.

The operator verified a fullscreen framebuffer of 2880×1801, a 960×601 logical
GUI viewport, GUI scale 3 and Windows 125% DPI. The 2304×1440 desktop capture
does not establish a different GUI scale. In-game F2 screenshots preserve
the framebuffer dimensions. Tests used the dedicated creative `ASL` world;
the fixture supplied real F7 sidebar, inventory/map, entity and block data.
Quiet Mode was off for the patched gameplay observations.

| Stage / feature | Recorded result | Evidence and limits |
| --- | --- | --- |
| Upstream baseline SELECT practice | The title and 4×7 gray tile grid drew, but no item icons were visible | `build/rendering-evidence/baseline-select-no-items.png` and `baseline-latest.log`, screenshot at `17:05:04`; this establishes missing content, not a missing grid hook |
| First fixture map run | Real F7 detection and `(5,5)` calibration succeeded; drawing the Devonian green check crashed | `fixture-map-resource-crash.log`, `17:14:11`–`17:14:12`; invalid uppercase texture path described above |
| Patched Devonian HUD/resources | The map background, colored rooms, player marker and green check drew successfully | `patched-devonian-hitboxes.png`, F2 screenshot at `17:18:43`; this earlier patched run predates the AWT bootstrap and does not prove desktop output |
| Shared world outlines | Cyan interaction-control outlines and a highlighted fixture mob were visibly rendered | Same F2 frame; `patched-headless-diagnostics.log` at `17:19:08` records one tracked mob, nine tracked controls and submitted world geometry without a renderer failure; not every mount, pressed state or picking expansion was individually exercised |
| F1 transition | F1 hid the HUD map and restored it when toggled back | Operator-observed Windows interaction; no separate F1 screenshot was preserved |
| Desktop map before bootstrap | Diagnostics reported AWT headless, zero publications and no window creation | `patched-headless-diagnostics.log` at `17:19:08`; confirms the forced-headless defect, not successful desktop placement |
| Automatic practice before target-reuse fix | RUBIX remained at `0/8` for over 70 seconds with no queued automatic clicks | `patched-rubix-pointer.png` and recorded terminal capture/source investigation; this earlier patched run exposed the separate upstream pointer restart defect |
| Settings and resize | Tabs, scrolling and a Map Scale slider sequence `1 → 2 → 1` worked; settings fitted a 684×414 desktop game window after F11; fullscreen was restored with GUI scale 3 retained | Operator-observed Windows input/resize run; desktop window dimensions are not assumed to be logical GUI dimensions |

The `252E3CA1…` jar was installed and Minecraft fully restarted at approximately
`17:32`, so AWT began without the previously cached headless state. The
following observations use that build, after the earlier baseline and
intermediate runs.

| Feature / transition | Recorded result | Evidence and limits |
| --- | --- | --- |
| Secondary desktop map | A real secondary AWT window visibly drew colored rooms, the green check and unopened-room question marks | Operator-observed desktop screenshots emitted through computer use; `final-windows-diagnostics.log` at `17:37:16` confirms the desktop route, paints and successful placement |
| Map style and editor | Switching Devonian to Noamm changed both HUD and desktop pixels; the editor displayed Noamm using actual fixture data, hid the secondary window, and Escape restored it | Operator-observed style/editor interactions; `build/rendering-evidence/patched-noamm-gameplay.png` is an F2 frame of the game's explored layer alone, not the secondary window |
| F1 after bootstrap | F1 removed both the HUD map and secondary window, then restored both | Operator-observed desktop screenshots; checks the lifecycle of both destinations |
| Window resize during Numbers | F11 switched to a 684×414 desktop game window; the secondary map resized from 241×241 to 155×155, while the terminal grid fitted the window and kept its numbers visible; fullscreen restored | Operator-observed automatic Numbers run and desktop resize; dimensions refer to AWT/desktop units, not inferred logical GUI sizes |
| Automatic terminal sequence | Eighteen rounds completed with 132 accepted automatic inputs across all six terminal kinds and one-second random reopen; Escape stopped the nineteenth round | `final-windows-diagnostics.log` and `terminal-practice-summary-20261011.json`; no wrong inputs or capture errors; round 19 applied two accepted inputs and discarded one pending input on stop |
| Pointer completion and close | Automatic clicks progressed after the target-reuse fix; after Escape the software pointer was gone and diagnostics read native mode `normal`, `ownedHidden=false`, `moving=false` | `patched-completed-rubix-pointer.png` and `final-windows-diagnostics.log`; 9,584 historical software enqueues and no cursor error were recorded, but post-close state does not prove active native hiding |
| Continued world drawing | The fixture mob and cyan interaction-control outlines remained visible during the restarted run | Operator observation and the final snapshot's nonempty tracked/queued/submitted stages, with no renderer failure |
| Quiet Mode hide/restore | Enabling the QOL row removed the HUD map, actual secondary AWT window, cyan control boxes and mob outline on the next frame; disabling it restored those outputs | Operator-observed toggles on this build; suppression was preserved rather than weakened |
| Quiet Mode settings footer | Reopening settings while Quiet Mode was on showed `Quiet Mode: map, terminal GUI and world highlights are hidden`, with no footer/Done overlap | Operator-observed settings at retained GUI scale 3; engaging Quiet Mode still closed the original settings screen |
| Save and Quit | Leaving the fixture world removed both map layers and the secondary window, then returned to a usable title screen cursor | Operator-observed shutdown transition on this build; Quit Game followed before the final installation |

At `17:37:16`, the AWT snapshot reports `available`, 24,373 publications,
24,352 paints, `visible=true`, bounds `(28,100,241,241)`, DPI `(1.25,1.25)`,
`alwaysOnTop=true`, `placementRejected=false`, and
`toolkit=sun.awt.windows.WToolkit`. Bootstrap status says it cleared
Minecraft's forced headless flag before ASL AWT access, and
`headlessProperty=automatic`. Combined with desktop screenshots, this
establishes actual Windows secondary-window output and placement; the counters
alone would not have established visible pixels. The same snapshot reports
the map route `external=true`, Noamm style, full-grid enabled, and fixture
pixels calibrated from the normal map path.

The sanitized capture summary at
`build/rendering-evidence/terminal-practice-summary-20261011.json` identifies
this same jar hash and records rounds `17:34:46`–`17:36:55`. It confirms 18
completed rounds, 132 accepted automatic inputs, all six types, zero wrong or
suspected-wrong inputs, zero lost events, and no file, parse or event-order
errors. The nineteenth round was intentionally aborted: three inputs were
routed, two were applied and accepted, and one pending input was discarded
when practice stopped. The run used Human mode, solver hints off, 550 ms
Click Delay, 430 ms first-click delay, Random Delay off, Humanize 50, cursor
glide on and simulated ping 0 ms. The summary contains aggregate data rather
than raw pointer/key events or slot snapshots. Its pointer samples establish
motion and module ownership; they do not establish native cursor mode or
GPU/compositor presentation. A zero-size window and long input gap in round
13 are recorded without assigning a focus/minimize cause from capture alone.

The `17:37:16` snapshot was taken after Escape and only establishes restored
post-close state. The later `DA6858B6…` run preserved native-mode readbacks in
`build/rendering-evidence/final-cursor-transitions.log`: at `17:55:04`, 667
successful hides observed `212994` (`GLFW_CURSOR_HIDDEN`) and 667 restores
returned the cursor to `normal`, with `ownedHidden=false`. This proves native
mode changes, but exposed excessive completed-board ownership churn rather
than a successful lifecycle result. The same run's F2
`build/rendering-evidence/final-terminal-frame.png` at `17:54:31` shows populated
SELECT icons on gray tiles with solver hints disabled and the software pointer.
Both pointer styles' full focus/disconnect/Quiet Mode lifecycle,
every mounting/pressed interaction visual and expanded picking, and all
SELECT/STARTS solver-disabled item interactions were not individually
exercised by this sequence. Baseline and patched runs before `252E3CA1…` did
not retain their jar hashes in this report, so their screenshots remain
identified by test stage rather than attributed to that build. This report update
changed documentation only.

### Final Windows cursor lifecycle evidence on the 040C566F build

The restarted final jar completed STARTS, SELECT, MELODY and ORDER between
`18:06:59` and `18:07:19`, with 5, 7, 3 and 10 accepted inputs respectively:
four completed rounds and 25 accepted inputs. Escape aborted the fifth MELODY
round at `18:07:23` after two inputs. All five close records report zero wrong
or suspected-wrong inputs and zero lost events. These exact records and the
following snapshot are preserved in
`build/rendering-evidence/cursor-lifecycle-final.log`.

At `18:07:45`, the final snapshot reports five hide requests/successes and five
restore requests/successes, with the last successful hide read back as `212994`
(`GLFW_CURSOR_HIDDEN`). The current mode is `normal`, `ownedHidden=false` and
`moving=false`. It records 1,968 pointer callbacks, 1,620 software enqueues,
zero native warps, `queued-osu-texture`, `spriteFallback=false` and no cursor
error. This establishes one hide/restore pair per opened board in this tested
Osu-style sequence, including completed-board delays and Escape handback;
it replaces the earlier per-frame ownership churn. The mode readbacks establish
native hiding during the sequence, while the F2 image above separately
establishes the software image. No live IME composition was exercised.

The same final snapshot reports the actual map route available with 10,423
publications and 10,397 paints, visible desktop bounds `(28,100,241,241)`,
automatic headless detection after bootstrap, and no placement rejection.
The real fixture still supplies F7 detection, 16-pixel map calibration, one
tracked/queued starred mob and nine queued interaction controls. The shared
world renderer records a submitted pass with 3,096 vertices and 4,644 indices
and no failure. Terminal layout diagnostics report the 960×601 GUI viewport;
the operator retained GUI scale 3. These counters confirm the final source
continued through the previously verified rendering paths, without claiming
new Linux or compositor evidence.

### Linux/KDE runtime evidence — unavailable

No Ubuntu/KDE Wayland, X11 or XWayland client was available during this work.
Native backend handling is supported by API/source evidence and pure policy
checks; Linux rendering and compositor behavior are **not runtime-verified**.
The user's desktop session/backend cannot be inferred from "KDE" alone.

## Reproducible runtime procedure

1. Build with JDK 25 using `./gradlew build`; install that exact root jar and
   record its hash. Keep the client's usual mod list and capture the startup
   log. Run `/asl diagnostics` for OS, Java, native GLFW platform, session and
   initial visibility gates.
2. In a dedicated singleplayer Overworld named `ASL` or `ASL Rendering Test`,
   run `/asl diagnostics fixture`. This intentionally changes the fixture
   area near `-177,64,-181`, installs real blocks, a tagged named mob/stand,
   a locked map in the selected inventory slot and an F7 sidebar. It runs on
   the integrated server; normal packets feed the client. It does not force
   `DungeonContext`, inject renderer queues, enable features, or paint a fake
   preview. Repeated requests replace only tagged fixture entities, although
   the fixture area itself is cleared/rebuilt each time.
3. Clear Quiet Mode in settings and enable the feature under test. Confirm
   `dungeon=true` and map/nonzero pixel data in diagnostics. Check Devonian
   and Noamm with Full Map Overlay off/on; check gameplay, editor, F1,
   disconnect and Quiet Mode transitions. On native Wayland, expect the
   advanced HUD fallback; on X11/Windows, inspect both in-game and desktop
   captures and window paint/bounds evidence.
4. Enable star-mob highlights and interaction display. Wait at least five
   ticks for the normal starred-stand scan. Look at the fixture mob and floor,
   wall and ceiling controls; move the camera and switch normal/depth-disabled
   output and fill/outline options. Test enlarged picking separately from
   colored boxes and vanilla selection outlines. Take diagnostics before/after.
5. Use `/asl termsim`; test SELECT and STARTS with solver highlights off/on,
   then the other terminal kinds, mouse/keyboard input and completion/restart.
   Test Osu and Default pointer styles, Hide Real Cursor off/on, focus loss,
   screen close/reopen, deactivation and disconnect. Capture F2 software frames,
   native-mode diagnostics and desktop cursor footage without an external
   pointer overlay. A snapshot after closing a terminal retains historical
   counters but correctly says no current container; do not mistake that
   post-close gate for failed extraction while it was open.
6. Test settings at multiple actual GUI sizes/scales, including 384×240 and
   427×240 when available. Exercise tabs, search, toggles, slider drag/release,
   scroll wheel, scrollbar dragging and route-node dragging. Resize while a
   screen is open. Confirm clipping, hover and click positions stay aligned,
   Done remains reachable, and the Quiet Mode footer is legible.

For a remaining world highlight failure, first establish `quiet=false`,
`dungeon=true`, nonzero tracked entities and queue counts. If extraction
grows but draw callbacks do not, investigate the event/renderer integration.
If draw callbacks grow with an empty queue, investigate detection or gates.
If passes/vertices/indices are submitted but no pixels appear, capture the
exact render target, graphics mode, GPU/driver, shaders and screenshot, then
compare the same fixture with conflicting rendering mods disabled. That is
the next discriminating GPU experiment; another unmeasured pipeline patch
would not establish the missing stage.
