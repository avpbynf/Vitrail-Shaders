# The settings screen

A shader pack ships its own settings, and it ships the *screen* for them too: which settings appear,
on which page, in which column, and in what order are all laid out in the pack's own properties
file. So this screen is not a preferences dialog the engine designed. It is a renderer for a layout
somebody else wrote, against a reference implementation they had in front of them while writing it.

That single fact decides most of what follows, and it decides the biggest thing about it: **this
screen is the reference's screen, ported rather than approximated.** The lists, the cells, the
gestures, the panels, the buttons and the texture they are drawn from all come from it. Where this
document says the reference behaves some way, that is not a comparison; it is the specification.

Open it from the video settings, where it sits under the mod's own name in the list of pages, with
the key bound in the game's controls (`I` by default), or from wherever else you reached the mod's
options. A second key, `R`, reads the pack again from disk without opening anything, which is what
makes editing a shader by hand and seeing the result a two second loop.

## Two views, one screen

The screen holds two views and swaps between them with the button above the bottom row. Tab does the
same.

- **The list of packs** in the folder, with a row at its head that switches shaders on and off for
  the whole engine, one row per pack, and a line at the bottom saying a pack can be dropped in. When
  the folder is empty it offers somewhere to get one.
- **The pages of the pack being configured**, reached by that button, which is dead while there is
  nothing to configure and says why on hover.

**Clicking a row in the list selects it and nothing more.** No file is written and no pack is read,
so a folder of eight packs can be looked through without paying for eight loads. Apply and Done are
what make a selection real. Clicking a pack while shaders are off also switches them on, which is
the reference's answer to a real confusion: before it, a pack could not be picked at all in that
state and nobody worked out that the toggle came first.

**The switch between the two views reads the pack whose settings are asked for, and does not apply
it.** The reference applies at that point, and its own note gives the reason: without something
there, picking a pack in the list and then opening the settings would open the settings of the pack
*before* it, since the pages are built from the pack that is loaded. The reason holds and the price
does not. Applying is the whole of a pack switch, on that pack's own default profile, so anybody
meaning to open a heavy pack, turn it down and then apply paid the heavy profile in full first.
Reading the chosen pack's properties answers the same need for nothing, the loaded pack goes on
drawing, and Apply stays the one thing that costs.

Escape unwinds one step at a time: the hidden screen comes back, then one page, then the pack list,
then the screen closes. Which view it opens on is a line of `vitrail/options.txt`, alongside that
file's other reserved lines.

## Cancel, Apply, Done

Three buttons, and the difference between them is what gets written.

- **Apply** writes what is pending and has the pack read again, without closing. A setting is judged
  by looking at the world it changed, so the screen stays.
- **Done** applies, then closes.
- **Cancel** throws away what is pending and closes.

Clicking a value changes the value and nothing else: nothing is written and nothing is recompiled
until one of those three. The screen itself does redraw, and **every cell is re-read rather than only
the one clicked**, because one click really can move ten of them: choosing a profile queues every
value that profile names at once.

A setting changed and not yet applied has its name drawn in amber, which is the reference's own
colour. It is the only thing telling you whether you are reading the world you see or the one you
are about to get.

Apply writes by reading the file first and laying the pending changes over it, so an edit made by
hand while the screen is open and an edit made here compose rather than overwrite each other. And
**changing which pack is selected drops what was pending on the one before it**: a value set on one
pack has no meaning in the next one's file.

## The eye, and F1

The eye at the bottom right of the screen, in a world, takes every widget away so the world behind
can be looked at. F1 does the same, and Escape brings it back. This is what makes the screen usable
for the one thing it is for, which is looking at what a setting did.

The blur behind the screen leaves with the widgets, and that takes two things rather than one. A
screen says whether it wants a blur under it, which is a yes or a no; how wide that blur is comes
from the video option and reaches the shader in a uniform the game fills for the whole frame. Fading
only the first of those fades nothing: the widgets go in one frame, the blur waits at full width for
the fade to fall under one and then goes out at once. So the screen holds the frame's own radius down
to what its fade has reached, for as long as it is the screen that is open, which is what the
reference does too.

## The gestures

On an ordinary setting, a left click walks its values forwards and a **right click walks them back**.
Shift and a click hand back the value the pack ships; from the keyboard, control does that and shift
walks backwards instead, a keyboard having no second button to walk back with. Holding shift shows,
on the setting under the mouse, what shift and a click would do.

Two things on a page do not answer to that.

- A **slider**, for the settings a pack names in `sliders=`. Dragging moves the handle and redraws
  the label, and **only letting go writes the value**, so a drag across twenty values is one change
  and not twenty. The handle snaps to the value that will be written rather than staying under the
  mouse. From the keyboard, Return picks the handle up and puts it down again, and the arrows move it
  one value while it is up.
- The **profile selector** ignores shift and a click, on purpose: a profile is a whole set of values
  rather than one of them, so there is no single value to hand back. The way back is to reset.

**A pack's own words about a setting appear in a panel at the bottom** after the mouse has rested on
it for a moment, one line per sentence. A setting whose name was too long for its cell offers the
whole of it as a tooltip instead, and not while that panel is up, since the panel already carries it.

## The page's own tools

The header of every page carries three, at its right, and the name of the page to their left. On any
page but the pack's first there is also the way back off it.

- **Import** and **export** open the platform's own file window and read or write a settings file in
  the shared format. They will not open while the game is full screen, and say so: a native window
  over a full screen game hangs it on more than one platform, which is the reference's own finding.
  What export writes is the *applied* settings, because what it copies is the file, and the file is
  the applied settings.
- **Reset** empties this pack's settings file and applies, so the pack goes back to what it declares
  itself. It **asks first**, and it is the only control on this screen that does: it is the only one
  that can lose an evening of tuning. The reference guards the same button with shift held instead,
  and one guard is enough; a button that demands shift and then asks reads as a button that is broken.

Reset does not touch `vitrail/options.txt`, so anything held down from outside stays held down. It
drops what is pending, a pending value being a change to the settings it is discarding. And it
empties the file rather than deleting it, where the reference deletes its own: the two read back the
same, a file carrying no value at all, and the reference removes it itself the next time it loads the
pack. Emptying is what lets somebody watching the file see it go blank.

## Dropping files on it

- **On the pack list**, a zip or a folder is copied into the pack folder and, when it is the only one
  dropped, selected straight away. Anything that is not a pack is refused, by name when it was the
  only file dropped.
- **On a page**, one settings file is imported. More than one at a time is refused, there being no
  sense in importing two.

Either way the line under the title says what happened, for five seconds.

## The folder is watched, the pack is not

The pack folder is watched, so a pack dropped into it from outside the game appears in the list on its
own. Neither reference has a button for that, and neither needs one. A folder that cannot be watched
is not a broken screen: the list is built once either way and what is lost is that one convenience.

**A pack's own files are a different question, and that is what `R` is for.** Nothing watches the
inside of a pack, and Apply reads it again only when it has something to write, so without that key a
GLSL file edited by hand while the game runs could not be seen without restarting. That key is the
reference's own, and this engine is developed on it: edit a shader in an editor, come back to the
game, press it.

The small circular arrow at the bottom left of the screen, in a world, mirroring the eye at the
bottom right, is the same thing for the one place the key cannot reach. The game feeds a key mapping
only while no screen is open, so from the screen where a pack is being worked on, its own key is
dead. That button is the one control here the reference has none of, and that is why.

It keeps the eye's condition as well as its corner, and is there in a world only. That is a choice
about where the loop lives rather than a fact about what a reading would do: out of a world a reading
still rebuilds this screen from the pack it re-read, which is visible on the spot. What it cannot do
out there is change an image, since none is being drawn. The cost is that from the title screen this
screen has no way to read a pack again by hand, the key being dead while it is open.

## The profile is worked out, not remembered

A pack can group its settings into profiles, and the screen shows one as if it were an ordinary
setting whose values are the profile names.

What it shows is derived from the values currently in effect: the most constrained profile all of
whose values match, and *Custom* when none does. **No name is stored anywhere**, here or in the
file, which is what makes the label survive a reset: the file is emptied, the pack's own values come
back, and they still amount to a profile.

Choosing a profile puts its values in the pending set, which is also what the reference does, so
Apply is what makes it real and the file ends up carrying the values rather than the name. It is not
quite like any other change though: a profile decides every setting it names, so it overrides a
value already waiting on one of them, and leaves the settings it does not name exactly where they
were. The selector itself is never marked amber, which is the reference's choice and a sound one:
every setting the profile moved is marked by its own cell, so a mark here would say the same thing
twice.

Clicking walks the profiles **from the one that decides the most settings to the one that decides
the fewest**, which is the reference's order and not the order the pack declares them in. On most
packs the two are the same, every profile naming the same count; where they differ, a click from
*Custom* lands somewhere else than the pack's first profile.

## Where settings live: one file per pack, shared with the reference

Each pack gets its own file, `shaderpacks/<pack file name>.txt`. That is the path the reference
resolves and the only one it reads, so **the two engines share one file per pack**: a setting changed
under either is the setting the other reads next. It is written in ISO-8859-1, which is what the
format specifies and what the reference does on both sides.

Sharing it is what puts the constraints on how it is written. A boolean is written `true` or
`false`, because the reference's reader takes literally nothing else and falls back to the pack's
default on anything it does not recognise; the screen works in *On* and *Off*, because those are the
two values a toggle offers, and the translation between the two happens where the file is read and
written rather than where a cell is drawn. A chosen profile is written as the values it names, one
per line, because the reference has no key for a profile name at all: writing the name alone would
hand it a file carrying none of the eight settings a profile like BSL's ULTRA decides.

A file this engine wrote before the settings moved is carried over at the first load of that pack,
in one go: it is read, written out to the shared file, and renamed aside. It is not read for as long
as the shared file happens to be missing, and the difference matters three times over. The screen's
Apply rebases on the shared file, so a first Apply after a lazy carry-over would write the one
setting just clicked and drop the rest. An Apply with nothing pending writes nothing, so a pack the
player only looks at would never be carried over at all. And a missing shared file does not mean
"not carried over yet": the reference deletes that file whenever nothing differs from the pack's
defaults, so a reset performed under it would bring the old values back.

**The line naming a profile is expanded, not dropped.** The old writer stored a file relative to the
chosen profile, leaving out every value the profile already named, so a player who had picked one has
that line and nothing else. It becomes the values it names, which is what the shared file has to
carry: neither engine has a key for a profile.

**A name a pack no longer declares is kept, not dropped.** It is reported once and left in the file.
The reference deletes such names, which loses a player's settings for good the day they try a new
version of a pack and go back. It also stays in what the pack is built with, because the authority
on what a pack declares is the pack's own option index rather than the menu: a setting can still be
declared and simply no longer be on a page.

**Which pack was chosen, and whether shaders are on at all, are two separate lines** of
`vitrail/pack.txt`. They have to be: a single line cannot switch shaders off and still remember which
pack to come back to. A file holding one bare word is still read the way it always was.

Two more lines of that same file are not this screen's at all, and both are written by the video
settings: `shadowdistance=` is how far the shadow map reaches, in chunks, from the Max Shadow
Distance slider, and `renderscale=` is what fraction of the window the world renders at before
being upscaled, from the FSR Render Scale slider. They sit there because they are the same kind of
thing as the other two, one number a player sets once that outlives whichever pack is loaded,
which is where the reference keeps its own distance. Every writer reads the file before writing
it, so none drops another's line.

`vitrail/options.txt` keeps its own job and is never written by this screen.

## The layers

**What the engine builds a pack with** stacks from the bottom: what the pack's own source declares,
then a profile `vitrail/options.txt` forces, then the pack's settings file, then that same file over
everything.

**What the screen shows you** reads in the same order, first answer wins: whatever
`vitrail/options.txt` forces, then what is pending, then the settings file, then the forced profile,
then the pack's own default.

A profile you choose is not a layer in either order. Choosing one puts its values in the pending set
and keeps nothing else about it, which is what the reference does too, and it is why choosing one
overrides a value you had already queued and leaves the settings it does not name alone. Only a
profile named in `vitrail/options.txt` is a layer, and it behaves like every other line of that
file: it decides what is drawn and never what is written.

The `options.txt` layer wins in both orders and is deliberately global. It is how a pass is proved
to run, it applies to whatever pack is loaded, and it is edited by hand while the game is
running. It decides what is drawn and never what is written.

Of a pack, what it can force is a setting that pack **declares**. A line naming anything else (a
typo, or a setting another pack has) forces nothing: the pack keeps its own default and the load
says so by name, one line per word. A setting is applied where the pack declares it, so a word with
no declaration has nowhere to be applied; writing it into the shaders instead is how a pack that
uses that word for something of its own stops compiling altogether.

The reserved lines are the exception, and they are the reason the file exists: they name this engine
and not a pack, and they are taken out before any pack is read. Those are what proves a pass runs.
They are `seed`, `passes`, `screen`, `dump`, `terrain`, `chain`, `shadow`, `sky`, `entities`,
`hand`, `clouds`, `weather`, `particles`, and `profile`, which is the one that is not an engine
switch: it names a profile of the loaded pack and is expanded underneath everything else. The load
prints the ones it found on their own line, apart from the settings, so that nobody goes looking
through a pack for a setting it never had.

## What is greyed out, and why

Several different things, and only the first is about you being overruled.

- A setting `vitrail/options.txt` forces. **This is the one thing on the page the reference has no
  equivalent of**, and it is a fact about this engine rather than a choice about the screen: that
  file has no counterpart there. The cell is drawn grey and refuses the gesture, because a click
  losing to that file in silence would be worse. How many there are is on the line at the bottom
  left.
- A **link to a page the pack never wrote**. It would lead nowhere, so it does not lead. The
  reference walks into it and lands back on the pack's first page instead, which reads as the screen
  having lost its place; one of the corpus's three hundred and nine links is such a link.
- On the pack list, the shaders toggle while the folder holds no pack. Switching them on would do
  nothing and say nothing.

Two things that look like they belong in that list and do not. A **heading**, which a pack writes as
a setting with a single value, is drawn like any other setting and cycles onto itself, which is what
the reference does with it; twenty two of the corpus are that. And a slot naming a setting the pack
does not declare is not greyed at all: it becomes a blank.

## What a broken layout does

A slot naming an option the pack does not declare does not throw. It becomes a **blank**, and a line
in the log names the slot and the setting it wanted. Packs in the test corpus do ship such slots,
and a pack with one broken name is still a working pack. A slot naming a declaration that is not a
setting gets the same blank and its own line: a toggle nothing tests, a value with no list of
choices, or a constant off the closed list of configurable ones. The reference's screen shows
nothing for any of them either.

The log reports those together with the links that lead nowhere, in one line that says the entries
concerned are shown "blank or greyed". Both kinds are in it, and which you get depends on which kind
it was: a missing setting is the blank, a missing page is the grey. The line is a count of things
for the pack's author to fix, not a description of one symptom.

Blanks matter as much as options do. Packs align their columns by hand using empty slots, and there
are a great many of them; dropping blanks would collapse every column a pack laid out.

## What this port costs, and what it does not

**The drawing is not the game's.** Every button, panel and cell on this screen is cut from the
reference's own widget texture, carried over under its licence, so a pack author recognises the
screen they wrote their file against. The price is that a cell is not a widget as far as the game is
concerned: hit testing and hover are worked out from the width the row gave it, and the click sound
has to be asked for.

**Narration is the one thing the reference drops that is not dropped here.** Its cells are skipped by
the narrator; here the focused cell reads out its name and its value, the way every other button in
the game does. Nothing in the API forced the loss, and it costs no pixel and no gesture to put back,
so a pack author sees the same screen either way. Only the focused cell narrates, a screen reader
being driven by the keyboard.

**What is not paid for** is anything about the model. The layers, the pending set, what Apply writes
and the layout rules touch no Minecraft class and log nothing, which is what lets the whole of it be
checked against the pack corpus outside the game in seconds rather than in a play session.

## Why this is a screen of its own, and what it does not rule out

It is its own screen because this is where the value is: the layout, the layers, the pending
set and what Apply writes are all this project's problem, and none of them get easier by living
somewhere else.

That is a different question from whether it can also be *reached* from Sodium's options, and the
answer there is yes. Sodium publishes a configuration entry point, and the reference implementation
registers through it: an entry point class named in its mod metadata, implementing Sodium's own
interface, so a page appears in Sodium's options and opens the reference's own screen.

The published interface covers more than the entry: there is a builder for a page, for a group, for
each shape of option and for the colour theme, and the reference describes its whole page through
them. It reaches outside that once, for the formatter that labels a slider. So the answer is neither
"no internals" nor "internals everywhere": it is a published surface with one edge sticking out,
which is worth knowing before planning around either extreme.

**A trap worth knowing before measuring any of this yourself**: what Maven serves as Sodium's
NeoForge artefact is a launcher shim (a few dozen classes, none of them the renderer), and the mod
is a jar nested inside it. Looking for a package in the outer jar finds nothing and proves nothing.
This project compiles its common module against Sodium's plain Fabric artefact for exactly that
reason: of the classes the NeoForge jar carries (688 in the build this was measured on), every one
this module names is byte for byte the Fabric one.
