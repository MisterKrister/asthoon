# Vocabulary

House terms for comments, commit messages, docs, identifiers, and user-facing
strings. The point is consistency, not euphemism: these are the boring
engineering words that describe what the code actually does. Use them so the
codebase reads like a normal rendering/QoL project in every context it
appears.

| Instead of | Say |
|---|---|
| ESP / X-ray | highlight, world-space box, overlay, outline pass |
| "wallhack" / through-wall render | depth-disabled pass, `throughWalls` flag |
| cheat / hack / hack client | utility mod, helper, client addon |
| aimbot / triggerbot | targeting assist, click timing |
| autoclicker | click driver, input timing, click loop |
| macro injection | input sequence, simulated input |
| keylogger | input recorder |
| stealth / undetectable / anti-detect | quiet mode, reduced footprint, clean session |
| screenshare evasion | externalised overlay, window separation |
| persistence / survive reboot | config reload, session restore |
| payload | buffer, message, packet |
| obfuscation | naming convention, indirection |
| victim / target / host | player, peer, endpoint, sample |
| exfiltration | export, offload, sync |
| red team / TTP / IOC | input path, behaviour, signature, marker |
| detection / bypass | guard, gate, short-circuit |
| "hide from" | "render outside", "decouple from" |

Identifier guidance:

- A module that draws on screen is a `*Overlay`, `*Display`, `*Hud`, or
  `*Renderer`.
- A module that computes an answer from game state is a `*Solver` or
  `*Scanner`.
- A module that fires input is a `*Driver`, `*Loop`, or `*Timed*`.
- A module that decides whether something runs is a `*Gate` or `*Guard`.
- Booleans in `Config.Data` read as `featureThingEnabled`.

Commit subjects: short, lowercase, imperative, say what changed and why:

```
fix hitbox shape overrides for wall skulls and buttons
cache secret block scan on the tick thread instead of per frame
move dungeon map rendering into the external overlay window
```
