# Luma listening test — ten minutes, real phone, real earbuds

Everything in this project so far has been measured, not heard. The numbers
say the equalizer is doing what it was told to. Nobody has confirmed it
*sounds* right, and no measurement can.

That is what this is for. **Nothing below is claimed to pass.** Fill in the
boxes; a FAIL here is a useful result, not a setback.

---

## How to install on my phone

**The file**

```
C:\Users\akash\Downloads\recovery\AndroidReceiver\app\build\outputs\apk\debug\app-debug.apk
```

6.9 MB (7,223,283 bytes). App name on the phone: **Luma**.

**Signing.** Signed with the standard Android debug key
(`CN=Android Debug, O=Android, C=US`), APK Signature Scheme v2. That is a real
signature, so the phone will install it — it just is not *your* key, so this
build cannot be updated over the top of a Play Store version and should not be
given to anyone else.

**Yes, a phone can install it directly.** No developer mode, no USB debugging,
no computer needed after the file is on the phone.

1. Get the file onto the phone — USB cable, or upload it somewhere and
   download it.
2. Open it with the Files app.
3. Android will say the app can't be installed from this source. Tap
   **Settings** on that prompt and turn on **Allow from this source** (this is
   the "Install unknown apps" permission, granted per-app to whichever app is
   opening the APK — usually Files or Chrome).
4. Go back and tap Install.
5. Play Protect may show "Unsafe app blocked" or offer to scan it. That is the
   normal warning for any app not from the Play Store. Choose **Install
   anyway** / **More details → Install anyway**.

**If install fails with a signature error:** you already have a Luma on the
phone signed with a different key. Uninstall the old one first. It will take
your saved devices and sound settings with it.

**Needs Android 8.0 or newer.** The app asks for internet and network-state
permissions only — no microphone, no storage, no location.

**Before you start:** phone and PC on the same Wi-Fi (a phone hotspot is the
reliable choice), Luma running on the PC, wired earbuds plugged into the
phone. Connect by scanning the QR code.

**Pick two tracks before you begin**, so you are not choosing music mid-test:

- one with real low bass — kick drum or bass guitar you can feel
- one with a clear lead vocal

---

## The test

Open **Now Playing → Sound** on the phone. Work down the list. Each item is
about a minute.

---

### 1. Does Compare really match the loudness?

The equalizer turns everything down to make room for its boosts, so without a
level match the equalized side sounds quieter and quieter sounds worse. The
Compare control is supposed to cancel that out.

**Do this.** Play the bass track. Tap **Flat**, then tap **Bass**. Now use the
**Compare** control: tap **Original**, then **Equalized**, back and forth a
few times.

**What to judge.** Ignore the tone completely. Ask only: *is one side louder
than the other?* If you can hear a difference in tone but not in volume, that
is the result we want. If one side is clearly louder, say which.

```
[ ] PASS   [ ] FAIL

NOTES:



```

---

### 2. Turning Sound on in the middle of a song

**Do this.** Close the Sound sheet. Start the bass track. While it is playing,
open Sound and tap the switch at the top right to turn it **on**. Then off.
Then on again.

**What to listen for.** Any click, pop, gap, stutter, or momentary silence at
the instant you tap. The sound should change, but it should not *break*.

```
[ ] PASS   [ ] FAIL

NOTES:



```

---

### 3. Does the switch between Original and Equalized feel instant?

The crossfade takes 50 milliseconds. That was chosen to be fast enough to feel
immediate but slow enough not to click. It has never been judged by a person.

**Do this.** With the bass track playing and Compare on screen, tap between
**Original** and **Equalized** quickly, maybe ten times.

**What to judge.** Does it respond the moment you tap, or is there a lag? And
separately: does it ever click or thump as it switches?

```
[ ] PASS   [ ] FAIL

NOTES:  (if it feels slow, roughly how slow? if it clicks, on which direction?)



```

---

### 4. The heaviest setting, on a loud track

This is the worst case the app allows: the Bass preset with the Bass slider
pushed all the way up. The app should protect itself, but nobody has heard it
try.

**Do this.** Tap **Bass**, then drag the **Bass** slider fully right (+6.0 dB).
The Headroom line should show a largish negative number. Play the loudest,
most bass-heavy part of your track. Turn your phone volume up to where you
would normally listen.

**What to listen for.** Crackling, buzzing, a gritty edge on bass notes, or
the sound "breaking up" on peaks. That is distortion. A simple change in tone
is not.

Then open **Advanced** and scroll to **Diagnostics**. Write down what
**Clipped samples** says.

```
[ ] PASS   [ ] FAIL

Clipped samples read: ____________

NOTES:



```

---

### 5. TalkBack on the sliders

Every control has a spoken description, and the sliders have spoken
adjustment actions, because a screen reader user cannot drag. This has been
checked in software but never actually used.

**Do this.** Turn on TalkBack: **Settings → Accessibility → TalkBack**. Go
back to Luma, open Sound, and try to:

- find the Bass slider by swiping right through the controls
- change its value without dragging
- do the same for Warmth
- reach the preset buttons and the Compare buttons

To change a slider, TalkBack puts the adjust options in a menu rather than on
the slider itself. On most versions you open it by swiping **up then right**
in one motion, then choosing **Actions**; you should see entries like
"Increase Bass" and "Decrease Bass". *I am not certain that gesture is the
same on your Android version* — if it is different, note what you had to do.

**Turn TalkBack back off when you are done** (same Settings screen, or hold
both volume keys if you set that up).

```
[ ] PASS   [ ] FAIL

NOTES:  (which controls you could reach, which you could not, and how it felt)



```

---

### 6. First impression of the Sound sheet

Everything above tests whether it works. This tests whether it makes sense.

**Do this.** Close the sheet. Now open it again and **stop**. Do not scroll.
Look at only what is on screen.

**What to judge.**

- Is it obvious what to do first?
- Does anything look like clutter or a debug readout?
- The Headroom line and its explanation — does it read as helpful, or as a
  warning that something is wrong?
- If you handed this to someone who knows nothing about audio, would they
  understand it?

```
[ ] PASS   [ ] FAIL

NOTES:  (and anything you would cut, move, or reword)



```

---

## Afterwards

Three things are worth more than the boxes:

1. **Anything that surprised you**, even if it technically passed.
2. **Whether you would actually leave the equalizer on** while listening
   normally. That is the real test.
3. **Battery and heat** — if the phone feels warmer than usual after this, or
   the battery dropped more than you expected, say so. The equalizer runs on
   every 10 ms of audio and that cost has only ever been measured on an
   emulator, which tells us nothing about a phone.

## What this test does not cover

- Long runs. Clock drift between the two machines takes about half an hour to
  show up, so a ten-minute test cannot see it.
- Real Wi-Fi conditions. If you use a hotspot, you are on the easiest possible
  network.
- Any other phone, any other earbuds, or Bluetooth.
- The light colour scheme. Everything so far has been looked at in dark mode.
