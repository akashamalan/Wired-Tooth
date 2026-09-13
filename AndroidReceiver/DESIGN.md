# Wired Tooth — interface design

Three screens, a token file, and the reasoning behind every number. Nothing
here is chosen by eye; if a value cannot be defended it is a bug.

Source: `app/src/main/java/com/wiredtooth/receiver/ui/`.

---

## Tokens

### Spacing — 8pt grid

`4 · 8 · 16 · 24 · 32 · 48 · 64`

4pt is a half-step reserved for optical corrections only — icon to label,
baseline nudges — never for structure. Layout that cannot be expressed on this
grid is a signal that the hierarchy is wrong, not that the grid needs another
value.

### Radii — three values, each meaning something

| Value | Surface class |
|---|---|
| 10pt | Buttons |
| 12pt | Cards, tiles, panels |
| 20pt | Sheets |
| pill | Status chips only |

Radius alone tells you what kind of thing you are looking at. A fourth value
would dilute that.

### Type — size-specific tracking, inverse leading

The system font, deliberately. It already ships optical sizing, tracking
tables and legibility tuning that a bundled face throws away.

| Style | Size | Leading | Tracking | Use |
|---|---|---|---|---|
| hero | 56 | 58 (1.04) | −1.2 | Connection state. One per app. |
| title | 34 | 40 (1.18) | −0.6 | Screen titles |
| statValue | 28 | 32 | −0.4 | A single number |
| heading | 22 | 28 (1.27) | −0.2 | Device name, section |
| body / button | 17 | 24 (1.41) | 0 | Reading, labels |
| subhead | 15 | 20 | +0.1 | Supporting copy |
| caption | 13 | 18 (1.38) | +0.3 | Stat labels, timestamps |
| overline | 12 | 16 | +0.9 | Group headers, the only caps |
| mono | 15 | 20 | 0 | IP addresses only |

Two rules produce that table:

**Tracking is size-specific.** Large text reads too loose as it grows, so it
tightens; small text needs air, so it opens. A single `letter-spacing` for
every size is wrong somewhere — usually at both ends.

**Leading moves inversely to size.** 1.04 on the hero, 1.41 on body. Large
type already has presence; body copy is read line after line and needs room.

Sizes are `sp`, so they scale with the user's font-size setting. Surrounding
spacing is `dp` on the grid, and components use `defaultMinSize` rather than
fixed heights so large type grows the layout instead of clipping.

### Colour — dark first

This is an audio app used at night. Every dark value was chosen first and the
light palette derived from it, not the other way round.

Backgrounds are near-black (`#0B0B0F`), not pure black: against OLED, pure
black makes the translucent panel's edge vanish, and a material needs
something to sit on to read as a material.

Semantic tones — `good` / `warn` / `bad` — never appear alone. Every coloured
state also carries text or a shape, because colour as the sole signal fails
for a large share of users.

### Motion — springs, described as damping and response

A fixed-duration animation has already decided where it is going and when it
will arrive, so it cannot respond to new input. A spring re-targets from
wherever it currently is, carrying velocity — which is what makes an interface
interruptible.

Compose wants `dampingRatio` and `stiffness`, so stiffness is derived:
`stiffness = (2π / response)²`.

| Spec | Damping | Response | Stiffness | Used for |
|---|---|---|---|---|
| `fast` | 1.0 | 0.30s | 438 | Press states, small reveals |
| `standard` | 1.0 | 0.40s | 247 | Default for everything |
| `momentum` | ~0.75 | 0.30s | 438 | Sheets, swipe release |

**Damping 1.0 is the default.** Bounce is reserved for motion that followed a
real gesture. Overshoot on a panel that merely appeared feels wrong; overshoot
on a row you flicked feels right.

### Reduced motion

Android has no `prefers-reduced-motion`. The real signal is
`Settings.Global.ANIMATOR_DURATION_SCALE == 0`, which is what Accessibility →
Remove animations actually sets. `rememberA11yPrefs()` reads it once.

Reduced motion is **not** no feedback — every spring becomes a 180ms
cross-fade. Opacity aids comprehension; translation is the vestibular part, so
translation is what gets dropped. Cards still fade in, they just stop
travelling.

The same flag stands in for reduced transparency, alongside the API-31 check
that `Modifier.blur` requires. Below API 31 there is no real translucency to
reduce, and the glass panel falls back to a solid surface with identical
border and padding — the same component, not a degraded one.

---

## Components

| Component | Geometry | Notes |
|---|---|---|
| `PrimaryButton` | min 56dp, r10 | Filled accent. Press: scale 0.97, `fast` spring |
| `SecondaryButton` | min 56dp, r10 | Hairline border, no fill. Destructive variant tints text only |
| `GlassPanel` | r12, blur 24dp | **The only translucent surface in the app** |
| `StatTile` | r12, pad 16 | Value 28sp, unit split and dimmed, label 13sp beneath |
| `DeviceCard` | min 72dp, r12 | Name over IP, signal bars trailing. One tap target |
| `StatusDot` | 10dp | Pulses only while working. Always beside text |
| `ConfirmSheet` | r20 top | Scrim + `momentum` spring, enters and exits downward |

**Press feedback fires on pointer-down**, not release. Waiting for the lift to
acknowledge a tap is the cheapest way to make an interface feel dead. Commit
happens on release, and dragging off cancels — the behaviour every button has
trained people to expect.

**Why no Material3.** This interface follows Apple's visual language. Material
brings its own type ramp, shapes, ripple and colour semantics, all of which
would have to be fought at every component. `foundation` gives layout,
gestures and `BasicText`; the rest is ours. The cost is writing our own
button; the benefit is that nothing fights us.

---

## Screens

### 1 · Connect

One primary action, one collapsed escape hatch, one honestly-disabled row.

**Scanning is a pulse, not a spinner.** A spinner says "something is
happening, I have no idea how long". Two concentric rings radiating outward on
a 2.2s cycle say "I am listening outward", which is what a scan *is* — the
motion hints in the direction of the operation. The control the user pressed
becomes the indicator, in place, because that is where they are still looking.

**Found devices cascade in at 40ms per card**, rising 12dp as they fade. Long
enough to read as a sequence; four cards are all present within 160ms, so
nobody waits.

**Manual IP is collapsed, not hidden.** The row is always visible and always
tappable — the common path is shown first and the advanced one lives one level
deeper.

**Errors name the fix, not the failure.** Three states, because each has a
different remedy and "connection failed" tells the user nothing they can act
on:

| Error | Copy |
|---|---|
| Refused | "That PC isn't listening — start Wired Tooth on the computer" |
| Wrong network | "This phone and the PC aren't on the same Wi-Fi" |
| Timeout | "The PC didn't reply. A firewall may be blocking it" |

**Bluetooth is shown disabled, not hidden.** The app answers "can it do
Bluetooth?" rather than leaving the user to wonder. No flow sits behind it,
because nothing is built behind it.

### 2 · Now Playing

Hierarchy is deliberately steep. The single most important fact — am I
connected — is 56sp and unmissable. Everything else is quieter. If the stats
competed with the status, the screen would answer the wrong question first.

Copy is plain: **"Connected"**, never "Session established".

**The stats are the one place asked to be beautiful rather than a debug
dump.** What makes them readable is not decoration:

- the value is the largest thing in the tile; the unit is split off and dimmed
  so the number reads first
- the label sits underneath in caption, not beside it competing
- tone changes *only* when a number means something is wrong

Thresholds, and why each sits where it does:

| Stat | Good | Warn | Reasoning |
|---|---|---|---|
| Latency | <150ms | <250ms | 250ms is roughly where Bluetooth sits — past it the app has lost its argument |
| Packet loss | <1% | <3% | 1% is the project's own bar, from `CLAUDE.md` |
| Buffer | 60±20ms | outside | Drift means the control loop is losing |
| Dropouts | 0 | any | They are audible; one is worth showing |

A calm grid means everything is fine. A tile turning amber tells you where to
look without reading a word.

**Disconnect asks; reconnect does not.** Disconnect stops the thing the user
came for and is easy to hit by accident, so it gets a sheet. Reconnect is one
tap, harmless and instantly reversible — confirming a safe action is how
people learn to dismiss dialogs without reading them.

### 3 · Saved devices

**Swipe tracks the finger 1:1** the whole way, rather than jumping to a
revealed state at a threshold. Past the edge it rubber-bands — a hard stop
reads as frozen; progressive resistance reads as "responsive, but there is
nothing more here".

**A swipe is invisible to a screen reader**, so removal is also exposed as a
custom accessibility action. Gesture-only affordances are how features become
unreachable.

Timestamps are relative — "2 days ago" is understood instantly; an absolute
date has to be decoded against today's first.

---

## Accessibility

Every statistic has a spoken label that is a sentence:

> "Latency, about 112 milliseconds"
> "Packet loss, 0.04 percent"
> "Network round trip, 10.5 milliseconds"

Not "latency 112 ms", and certainly not three separate stops for value, unit
and label. Each tile uses `clearAndSetSemantics` so the group reads as one
phrase.

IP addresses are spoken with "dot" spelled out — `172 dot 20 dot 10 dot 14` —
because a screen reader reading "172.20.10.14" as a decimal number is
unusable.

Interactive rows state what will happen: "…Double tap to connect."

---

## What is real and what is designed ahead

Being explicit, because a design that quietly implies working features is
worse than one that admits its gaps.

**Now working, and built as part of this:**

- **Device discovery.** "Find PC" performs a unicast sweep — an ordinary
  HELLO to every host on the local subnet, collecting whoever HELLO_ACKs.
  This needed no change to the Windows sender and uses no multicast or
  Bonjour, so it satisfies `CLAUDE.md`'s unicast-only rule and would port to
  iOS unchanged. A phone hotspot is a /28: 14 probes. A home /24 is 254
  probes of 20 bytes — about 5 KB, sent once.
- **Saved devices**, persisted in `SharedPreferences`.
- Every statistic on Now Playing, which comes from the existing
  `ReceiverStatus`.

**Designed ahead, rendering correctly when absent:**

- **PC name.** WTP1 carries no machine name, so `Device.name` is null and
  cards fall back to the IP. Adding it is a protocol change.
- **Signal strength.** Nothing in the stream carries it; `signal` is null and
  the bars are simply not drawn.
- **Output route.** Nothing queries `AudioManager` yet, so this defaults to
  Speaker rather than claiming "Wired earbuds" without checking.

**Not designed, because not built:** the Bluetooth flow. It is one disabled
row and nothing more.

---

## Verified

```
./gradlew :app:assembleDebug      -> BUILD SUCCESSFUL, 6.8 MB APK
./gradlew :app:testDebugUnitTest  -> 10 tests, 0 failures
```

The APK grew from 838 KB to 6.8 MB. That is Compose, and it is the honest
cost of this design — the previous UI was an IP field and a button in plain
views. Worth it for three screens; it would not have been worth it for one.

**Not verified: none of this has run on a phone.** No device was attached, so
the layout, the pulse, the swipe physics, the haptic and the glass blur are
all unconfirmed on real hardware. Compose previews are not a substitute for
holding it.
