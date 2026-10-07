# Terminal practice and input capture

Join a world, open `/asl`, select **Terminals**, and press **Start Terminal
Simulator**. `/asl termsim` opens the same practice session. A completed board
stays visible for one second, then another random board opens. Press Escape
or your inventory key to stop. Closing the screen or disconnecting cancels
pending inputs and the next-board timer.

Practice includes panes, numbers, same-color, starts-with, select-color, and
three-row melody terminals. The generation and click rules are adapted from
[Odin revision 833e0533](https://github.com/odtheking/Odin/tree/833e0533ef9c47529b790612627a65618ebd5a58/src/main/kotlin/com/odtheking/odin/features/impl/boss/termsim).
The BSD notice ships in `META-INF/licenses/Odin.txt`. ASL uses separate local
menus, seeded boards, guaranteed nonempty targets, visible completed melody
buttons, and a continuous random practice loop.

Practice defaults to manual input collection. **Use Auto Terminal in Simulator**
lets the current enabled Auto Terminal settings drive the practice screen; its
master and individual terminal-type switches still apply. Simulator automation
works outside dungeons. **Simulator Ping** adds 0–500 ms before the local model
applies each click. The delay setting is fixed for each board. Inputs apply on
the client tick, so records include both the scheduled time and actual apply
time. The simulator owns its items and does not change the player's inventory.

The custom grid shows item icons and hover names for starts-with and select-color
practice. Turning off solver hints still leaves the underlying items visible.
Wrong answers and repeated selections reach the simulator and are recorded.

## Collecting data

Simulator recording is automatic. Enable **Record Terminal Inputs** to also
record real terminal screens. The existing `/asl capture` command enables the
terminal recorder alongside its existing general capture. Recording is scoped
to terminal screens.

Each terminal produces a UTF-8 JSONL file under the Minecraft instance's
`logs/asthoonlite/terminals/` directory. ASL prints `[ASL-TERMINAL]` open/close
summaries with the full filename to `logs/latest.log`; `[ASL-TermSim]` lines
include kind, seed, mode, ping, completion count, and restart timing. Preserve
the JSONL files when collecting a dataset: the detailed samples are in these
files.

Each JSONL record has `schema`, `sessionId`, `sequence`, `type`, `monotonicNs`,
`elapsedNs`, and `fields`. Nanosecond timestamps come from `System.nanoTime()`
when ASL receives the callback; `session_open.wallClockMs` provides a wall-clock
anchor. Keep these timestamps as 64-bit integers when analysing them.

| Event | Data |
| --- | --- |
| `session_open`, `simulation_started` | Kind, title, menu ID, settings, geometry; practice group, board number, seed, source revision and ping |
| `mouse_move` | Every mouse callback, raw/scaled coordinates, deltas, elapsed time, slot hit, and pointer position |
| `mouse_button`, `key`, `mouse_scroll` | Press/release/repeat, button or key/scancode, modifiers, position and scrolling |
| `drawn_pointer_frame` | Each rendered automated-pointer position; kept separate from raw mouse events |
| `state_snapshot`, `menu_update` | Item IDs, names, counts, selection, changed slots, candidates, melody rows, marker, dimensions and settings |
| `click_routed` | Manual/keyboard/automatic source, slot, button, input path, whether the route succeeded, candidate match and previous-click interval |
| `click_queued`, `click_outcome` | Practice input ID, request/due/apply timing, model revision, exact validation result and changed slots |
| `inputs_cancelled` | Practice input IDs discarded at completion or screen close |
| `session_close` | Duration, click totals, completion/abort status, validation failures and event loss |
| `events_lost` | Count and timestamp/sequence range if the bounded writer queue fills |

`click_routed.accepted` means the input reached its route. For practice,
`click_outcome.accepted` is the model's exact acceptance result. Model rejection
counts are exact; an accepted pane toggle can still undo progress, which is
visible in the slot snapshots. For real terminals, candidate mismatches are
labelled `suspectedWrongClicks`; close status stays `closed_unconfirmed` unless
completion was observed in the slot state. A practice seed plus kind recreates
the board, while snapshots preserve the displayed item names and settings used
for that run.

File writes run on a buffered worker. Events retain callback order, rapid clicks
are not deduplicated, and the queue reports any missing range rather than
silently dropping it. The writer flushes periodically and drains on stop.

## Verification

`gradlew build` includes regression checks for all six generators, independent
click validation, solvability, seed replay, localized starts-with generation,
melody timing, queued-input order/cancellation, the one-second restart,
backward-compatible configuration defaults, JSON precision and overflow records.
