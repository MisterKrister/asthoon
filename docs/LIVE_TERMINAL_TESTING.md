# Live terminal and route testing

Windows testing on 11 October 2026 uses Prism's Sky-Myce Verification
instance, Minecraft 26.1.2 and GUI scale 3. The test server is
`hypixelp3sim.zapto.org`: right-click the lobby compass, select a sim
instance, then use `/start p3` to start or reset the terminal phase.

## Route restart

The seven-node `terminal return` route supplied for this test was imported
without changing its coordinates or actions. Its last-played ID is retained
when execution stops. In ASL's Pathfinding / User Presets screen,
**Goldor + Last Route Keybind** was set to F8. A press sends `/tpto goldor`,
waits for the server position update and two settled ground ticks, then
replays the saved route. Changing the binding is available in the same UI.

The previous tick-polling implementation missed brief presses. Existing
keyboard and mouse callbacks now retain a matched press until the next
tick, including when press and release both occur between ticks. Repeats,
menu input, disconnected worlds and focus loss do not request a restart.
Stopping the old route must not erase a newly queued restart.

The rebuilt client successfully restarted this route with a short F8 press
on sim2 at 20:01. After the route fell into lava, another F8 press recovered
to the Goldor spawn and restarted it. The route itself reproduced an early
WALK-to-CROUCH transition: at `(99.74, 113.58, 66.56)`, the player was still
airborne, about two blocks before the landing waypoint `(99.5, 113, 68.5)`.
The subsequent sample lost forward travel and fell. The corrected arrival policy
requires touchdown before advancing to a CROUCH node. Air guidance remains
on the landing waypoint until that happens; ordinary WALK-to-WALK continuity
is unchanged. A regression pins the observed position and velocity.

A replay at 20:11 retained the WALK node correctly but still fell short.
Its horizontal travel stopped at `z=66.70`, consistent with collision at
the front of the landing block. The controller was requesting forward input;
this was not evidence of a braking command. The explicit JUMP radius and
generic ledge jump could trigger before the recorded takeoff at `z=58.5`.
Recorded JUMP nodes now request takeoff within one horizontal movement tick
of their coordinates, clamped to 0.35–0.8 m. Generic ledge detection cannot
start their pulse. The explicit launch keeps its three-tick jump pulse,
directs forward movement toward the next landing, and leaves that landing
to the next tick. A launch-request log records exact position and distance.
Regressions pin the observed 15.14 blocks/second approach and verify the
early point waits while the next point near the recorded takeoff launches.

The combined correction completed the exact route on sim2 at 20:18:50.
The launch request occurred at `(100.50, 115.00, 58.11)`, 0.39 m from the
recorded JUMP node. Air guidance retained the landing target; the next
CROUCH node began on the ground. The final position faced the terminal.
The route has no TERMINAL action, so opening it required a manual right
click on the command block below the skull. Computer Use camera movement
required temporarily turning Minecraft's Raw Input off; GUI scale stayed 3.

## Terminal corrections

Starting or restarting Auto Terminal used to reset packet sensing after a
menu's initial contents had already arrived. Packet sensing now retains
those contents until its own matching open/close or connection reset. The
regression sends actual open/content/slot packet objects through the
production callbacks, then starts and resets automation, and verifies menu
contents, timing and Melody markers survive. Stale menu packets remain
rejected after close or replacement.

Manual color terminal filtering used to recompute an optimal target while
rendering and click direction used the cached target. A partially changed
pane could therefore become unclickable before reaching the displayed
color. Rendering and manual left-click direction now share the same committed
or cached target. Manual eligibility follows the visible controls independently
of the solver's next candidate. A regression changes the optimal color midway
through a two-click sequence and completes the board using the fixed target.

Live Melody used a separate packet-marker algorithm that compared rows
using different bases, bypassed the click clock after the first input and
queued skip inputs without consuming them. Live and practice now use one
decision over the actual menu items, retaining the packet load gate. It
shares the configured clock, acknowledgement grace, premove and revalidated
skip queue. Packet-driven regressions verify a 550 ms cadence after an early
row update, premove before acknowledgement, and queued inputs staying pending
until sent and disappearing when completed. Delayed updates from an earlier
row cannot pull the pointer backward during the existing 400 ms grace after
a later queued input; the earlier row remains retryable after that grace.

At 20:28, a real packet-updated Melody board exposed a separate three-row
assumption. Its fourth button was slot 43, moving note slot 41, and footer
marker slot 50. The grid omitted that button and automation stopped after
the third row. Earlier completed rows used red terracotta buttons and white
tracks rather than the practice board's green completion buttons.
Detection, geometry, click targets and premove now share button-evidenced
content rows 1–3 or 1–4. A later observed moving note confirms earlier rows
advanced; sending a click alone does not. Indicator strips and inventory
items cannot add note rows. The captured fourth-row state is pinned in the
regression harness, including its slot-43 hit test and production decision.

Live Numbers displayed all fourteen stack-count labels correctly at 20:33,
but its progress denominator was capped at ten. The denominator now counts
the actual numbered panes; the local practice board still generates ten.
The recorder's initial goal also grows as the opening slot packets assemble
the board, then freezes at the first sent input. This prevents a partial
one-pane opening snapshot from becoming the permanent fourteen-pane goal.

Before the four-row correction was loaded, the combined route/packet/click
clock build (`F9912576B300F06B63A6ED25A15386213E1958BF4912C20DFE9943DF47BA645C`)
completed these representative live boards. Each completion was confirmed
by server chat and the green Terminal Active hologram, independently of the
recorder's close classification:

| Local time | Board | Sent automatic inputs | Duration |
|---|---|---:|---:|
| 20:25:16 | Starts with | 6 | 3.46 s |
| 20:26:27 | Change color | 9 | 4.93 s |
| 20:27:24 | Starts with | 8 | 4.57 s |
| 20:32:45 | Change color | 6 | 3.34 s |
| 20:33:17 | Numbers, fourteen panes | 14 | 9.03 s |
| 20:33:58 | Select red items | 4 | 2.46 s |

SELECT and STARTS item icons remained visible with solver hints off, over
the neutral tile background. These live captures reported zero suspected
wrong inputs and zero lost events; live boards cannot provide the practice
engine's authoritative wrong-input count. Short zero-input recordings
preceding the boards corresponded to server screen replacement, not a solve.

With the adaptive Melody and actual-number-goal build loaded
(`EED1641F0543CD0BEDF5F04DD928B2B9BA45F77E6FAD298D988C5A2BE1B4B2B3`),
F8 replay completed the unchanged route at 20:43:39, 20:44:30, 20:45:46
and 20:46:53. The jump launched at z=58.11, 0.39 blocks from its node.
The following live boards completed with server chat and hologram confirmation:

| Board | Sent automatic inputs / recorded goal | Duration |
|---|---:|---:|
| Select yellow items | 7 / 7 | 4.255 s |
| Select blue items | 10 / 10 | 6.004 s |
| Red/green panes | 12 / 12 | 6.175 s |
| Red/green panes | 12 / 12 | 6.201 s |
| Starts with C | 11 / 11 | 6.257 s |
| Melody, four content rows | 5 / 4 | 11.087 s |

The Melody input sequence was slots 16, 25, 34, 43 and a retry of 43.
Both fourth-row inputs landed inside the displayed button. The first fourth-row
input was followed by a note moving off its target rather than completion;
the retry occurred at the next alignment and the server closed the board
143 ms later. Every sent input matched its current candidate. Captures
reported no lost events, sequence gaps or timestamp backsteps. Their final
classification remains `closed_unconfirmed`: the server closes the screen
without sending a final completed slot state. Live capture does not receive
authoritative input acceptance, so its authoritative wrong-input count remains
unknown.

A previous build also retained actual red/green pane states and all five
current color-terminal states when solver hints were off. Its JDK 25 offline
build and full regression harness passed at 20:54:17. The root jar and the
jar installed into Prism have identical SHA-256:
`D8328415AC4976546A2F5FD4CC5CDF476C8C756AF66D94A5E491868174341BE6`.
F8 replay of the unchanged route passed again at 20:58:09 with this jar.
Live Computer Use observed the actual pane/color states and neutral item
tiles with solver hints off at GUI scale 3.

Representative boards completed with this previous jar, independently confirmed
by server chat and the green Terminal Active hologram:

| Server confirmation time | Board | Sent automatic inputs / recorded goal | Duration |
|---|---|---:|---:|
| 20:58:51 | Change color | 7 / 7 | 3.867 s |
| 21:06:57 | Starts with B | 7 / 7 | 3.965 s |
| 21:07:46 | Select light blue items | 8 / 8 | 4.816 s |
| 21:08:37 | Melody, four content rows | 6 / 4 | 7.028 s |
| 21:09:32 | Red/green panes | 12 / 12 | 6.219 s |
| 21:13:24 | Numbers, fourteen panes | 14 / 14 | 9.027 s |

The D8328415 build's Numbers board displayed all fourteen labels and a `/14` progress
denominator. Its capture goal was fourteen before the first input and stayed
fourteen throughout; inputs selected labels 1 through 14 in order. That build's
Melody sequence was slots 16, 25, 34, 34, 43, 43, with one retry each on rows
three and four. Both fourth-row inputs were inside the displayed button.
Every input matched its current candidate, and the shortest Melody interval
was 550.643 ms with a configured 550 ms cadence. These captures reported no
suspected wrong inputs, lost events, ordering errors or file failures.
The server-completed boards can still be classified `closed_unconfirmed`
when the final completed slot snapshot does not arrive before menu closure;
the authoritative live wrong-input count remains unknown. Raw Input was
restored to on after that Computer Use session, with GUI scale left at 3.

## Current manual-input correction and verification

Manual custom-grid input now checks structural eligibility rather than the
solver's next candidate. Visible numbered panes, choice items, color panes and
evidenced Melody buttons can receive manual input while the server validates
order and timing. Empty tiles, filler, gaps and positions outside the interactive
grid remain excluded. Rubix left clicks still choose the shortest direction
toward the fixed target, while explicit right clicks retain their direction.
An immutable pre-send `RubixClickPlan` retains the effective button and next
ring color before vanilla `PICKUP` can empty the local slot. Successful sends
use that stored color for prediction and presentation.

The current JDK 25 offline build and full regression harness passed at
22:02:41. The jar was installed at 22:04:10 and launched at 22:04:20; its SHA-256 is:
`FA1BF38BA4C9E50897B5DC87065C55FFE204393CCEAFC6E229BD8767497D1F05`.
A short F8 press replayed the exact unchanged route successfully between
22:07:59 and 22:08:02.

With Auto Terminal and solver hints both off, the live Numbers test ran from
22:08:40.357 to 22:09:56.856. Fourteen raw left-click presses produced fourteen
client-accepted inputs routed as `CLONE`, button 2, selecting labels 1 through
14 with a recorded goal of 14. Server completion confirmed the solve.
Acknowledgements for inputs 1–13 arrived after 131.405–165.578 ms; the screen
closed 130.628 ms after the final input. This sequence did not exercise clicks
faster than the previous acknowledgement; the rapid manual acknowledgement
case is covered by regression checks only.

At 22:10:25.875, an intentional off-beat manual Melody input reached row one's
button at slot 16 despite `candidateMatch=false`. It was accepted by the client
input route, the server did not advance the row, and Escape then ended the
test. This demonstrates manual eligibility independently of solver alignment;
it is not a completed Melody board.

After enabling Auto Terminal, the reopened four-row Melody board ran from
22:12:48.296 to 22:12:53.910, completing in 5.614 seconds with server
confirmation. Its five inputs were slots 16, 25, 34, 43 and a retry of 43,
against a recorded goal of four rows. All matched their current candidates;
the fourth-row retry interval was 550.990 ms. These runs reported no lost
events, ordering errors or file failures. Candidate matching does not provide
an authoritative live wrong-input count.

Manual Rubix also completed on the same FA1BF38B build with Auto Terminal and
solver hints off at GUI scale 3. The board opened at 22:24:26.977 and closed at
22:25:58.605, lasting 91.627604 seconds. Its initial normalized ring was
`[3,1,0] / [1,1,4] / [3,3,2]`, with an optimal yellow target (index 1) and a
recorded goal of ten clicks. The cached target itself is not logged, but the
recorded directions are consistent with retaining yellow.

Twelve client-accepted manual `PICKUP` inputs came from one raw right click and
eleven raw left clicks, with no automatic inputs. Eight used effective button
1 (backward) and four used button 0 (forward). The intentional first right click
at slot 22 changed an already-yellow pane to orange; the following left click
restored yellow. That deliberate two-input detour explains twelve inputs
against the original goal of ten, and produced the only candidate mismatch.
All subsequent inputs matched their candidates.

| Local time | Slot | Raw button → sent button | Server color change | Acknowledgement |
|---|---:|---|---|---:|
| 22:24:48.223 | 22 | right → 1 | yellow → orange | 132.618 ms |
| 22:24:53.840 | 22 | left → 0 | orange → yellow | 165.331 ms |
| 22:25:03.508 | 12 | left → 1 | blue → green | 146.582 ms |
| 22:25:08.657 | 12 | left → 1 | green → yellow | 147.924 ms |
| 22:25:14.324 | 14 | left → 0 | orange → yellow | 121.714 ms |
| 22:25:20.839 | 23 | left → 0 | red → orange | 165.508 ms |
| 22:25:27.809 | 23 | left → 0 | orange → yellow | 145.055 ms |
| 22:25:33.922 | 32 | left → 1 | green → yellow | 132.750 ms |
| 22:25:40.605 | 31 | left → 1 | blue → green | 149.028 ms |
| 22:25:46.823 | 31 | left → 1 | green → yellow | 130.456 ms |
| 22:25:52.155 | 30 | left → 1 | blue → green | 148.516 ms |
| 22:25:58.439 | 30 | left → 1 | final input; no final slot packet | menu closed after 165.156 ms |

The first eleven matching server updates arrived after 121.714–165.508 ms,
with a median of 146.582 ms. The final input was independently confirmed by
the testing player's server activation at 22:25:58 and the green Terminal
Active hologram. The capture classified this board as completed after the
optimistic final pane update, so the server confirmation is the independent
completion proof. The recording reported no lost events, ordering errors or
file failures. It does not provide an authoritative wrong-input count.
These manual clicks were spaced seconds apart; the click-before-acknowledgement
race remains covered by regressions only. Raw Input was restored to on after
this session, with GUI scale left at 3.

Live terminal input recording is enabled for these tests. JSONL captures
and game logs stay local under the verification instance and ignored build
evidence directory; they are not included in the pull request.
