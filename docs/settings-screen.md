# The settings screen

A shader pack ships its own settings, and it ships the *screen* for them too: which settings appear,
on which page, in which column, and in what order are all laid out in the pack's own properties
file. So this screen is not a preferences dialog the engine designed. It is a renderer for a layout
somebody else wrote, against a reference implementation they had in front of them while writing it.

That single fact decides most of what follows.

Open it with the key bound in the game's controls - `I` by default - or from wherever you reached
the mod's options.

## Two views, one way back

The screen opens on the list of packs in the folder. From there a button leads into the pages of
whichever pack is being drawn, and it is inactive when there is nothing to configure.

Making the pack list the root is what makes "back" mean one thing everywhere: up one page, then out
to the list, then out to whatever screen asked for this one. Escape walks the same path.

Which view it opens on is a line of `vitrail/options.txt`, alongside that file's other reserved
lines.

## Nothing happens until you press Apply

Clicking a value changes the value and nothing else on disk: nothing is written, nothing is
recompiled, and leaving never writes - a pack read again for somebody who was only looking is a
second of hitch nobody asked for. This is the one convention of the reference's screen deliberately
not kept: it applies on the way out.

The screen itself does redraw, and every widget is re-read rather than only the one clicked. One
click really can move ten of them, since choosing a profile queues every value that profile names at
once; the status line and the last button move with them.

What keeps that from being a trap is that **Done is not offered while anything is waiting** on a
pack's pages. The last button of the row is Apply until there is nothing left to apply, and only
then does it become Done. The word that leaves is never the word that would throw work away. On the
pack list it is always Done, since that button is the only way off the list at all and Back has
nowhere to go from the root.

A setting changed and not yet applied is marked, in the colour the reference marks it in, for the
same reason the layout rules are the reference's: it is what the pack's author saw.

Apply writes by reading the file first and laying the pending changes over it, so an edit made by
hand while the screen is open and an edit made here compose rather than overwrite each other.

Two consequences of not applying on the way out are worth knowing before they surprise you.
**Going back to the pack list throws away what is pending**, deliberately: a pending value belongs
to one pack's page, and carrying it to a list where the next click may load a different pack leaves
it waiting for a file it was never meant for. And **clicking a pack in that list is not a selection,
it is a load**: it writes the pack file and reloads there and then.

## The gestures, which are the reference's

On an ordinary setting, a left click walks its values forwards and a **right click walks them
back**. Shift and a click hand back the value the pack ships; from the keyboard, control does that
and shift walks backwards instead, a keyboard having no second button to walk back with.

Two things on a page do not answer to that. A **slider** is the game's own widget and takes its
drag, its arrow keys and nothing else. And the **profile selector** ignores shift and a click, on
purpose: a profile is a whole set of values rather than one of them, so there is no single value to
hand back.

## The profile is worked out, not remembered

A pack can group its settings into profiles, and the screen shows one as if it were an ordinary
setting whose values are the profile names.

What it shows is derived from the values currently in effect: the most constrained profile all of
whose values match, and *Custom* when none does. **No name is stored anywhere**, here or in the
file, which is what makes the label survive a Reset: the file is emptied, the pack's own values come
back, and they still amount to a profile.

Choosing a profile puts its values in the pending set, which is also what the reference does, so
Apply is what makes it real and the file ends up carrying the values rather than the name. It is not
quite like any other change though: a profile decides every setting it names, so it overrides a
value already waiting on one of them, and leaves the settings it does not name exactly where they
were.

Clicking walks the profiles **from the one that decides the most settings to the one that decides
the fewest**, which is the reference's order and not the order the pack declares them in. On most
packs the two are the same, every profile naming the same count; where they differ, a click from
*Custom* lands somewhere else than the pack's first profile.

**Reset asks before it acts, and then empties this pack's settings file rather than deleting it.**
The reference deletes its own, and the two read back the same: a file of comments carries no value
at all, and the reference removes it itself the next time it loads the pack. Emptying is what keeps
the confirmation naming the file true, and lets somebody watching the file see it go blank. Reset
also drops what is pending - a pending value is a change to the settings being discarded - and it
does not touch `vitrail/options.txt`, so anything greyed out stays greyed out afterwards.

## The pack list refreshes itself

There is no reload button, in either reference. The folder is looked at about once a second while
the list is on screen, so a pack dropped in appears on its own.

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
"no internals" nor "internals everywhere" - it is a published surface with one edge sticking out,
which is worth knowing before planning around either extreme.

**A trap worth knowing before measuring any of this yourself**: what Maven serves as Sodium's
NeoForge artefact is a launcher shim - a few dozen classes, none of them the renderer - and the mod
is a jar nested inside it. Looking for a package in the outer jar finds nothing and proves nothing.
This project's own build script unpacks the nested jar for exactly that reason.

## Where settings live: one file per pack, shared with the reference

Each pack gets its own file, `shaderpacks/<pack file name>.txt`. That is the path the reference
resolves and the only one it reads, so **the two engines share one file per pack**: a setting changed
under either is the setting the other reads next. It is written in ISO-8859-1, which is what the
format specifies and what the reference does on both sides.

Sharing it is what puts the constraints on how it is written. A boolean is written `true` or
`false`, because the reference's reader takes literally nothing else and falls back to the pack's
default on anything it does not recognise. A chosen profile is written as the values it names, one
per line, because the reference has no key for a profile name at all: writing the name alone would
hand it a file carrying none of the eight settings a profile like BSL's ULTRA decides.

A file this engine wrote before the settings moved is still read, once, from where it used to live.
The values come back and the next Apply writes them to the shared file; the old one then stops being
read. The line naming a profile that those files carry is dropped on the way, a profile being the
name of a set of values rather than a value of its own.

**A name a pack no longer declares is kept, not dropped.** It is reported once and left in the file.
The reference deletes such names, which loses a player's settings for good the day they try a new
version of a pack and go back. It also stays in what the pack is built with, because the authority
on what a pack declares is the pack's own option index rather than the menu: a setting can still be
declared and simply no longer be on a page.

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
to run, it may name a setting no pack declares at all, and it is edited by hand while the game is
running. It decides what is drawn and never what is written.

## What is greyed out, and why

Several different things, and only the first is about you being overruled.

- A setting `vitrail/options.txt` forces. Its value is reported to the screen so the widget can be
  greyed rather than letting a click lose to it in silence.
- A **heading**, which a pack writes as a setting with no values to walk through. There is nothing
  to click.
- A **link to a page the pack never wrote**. It would lead nowhere, so it does not lead.
- On the pack list, the pack already being drawn, and the *None* entry when nothing is loaded. Both
  are the same idea: the button that would put you where you already are.

A slot naming a setting the pack does not declare is not in that list, because it is not greyed at
all - it becomes a blank. See below.

## What a broken layout does

A slot naming an option the pack does not declare does not throw. It becomes a **blank**, and a line
in the log names the slot and the setting it wanted. Packs in the test corpus do ship such slots,
and a pack with one broken name is still a working pack.

The log reports those together with the links that lead nowhere, in one line that says the entries
concerned are shown "blank or greyed". Both kinds are in it, and which you get depends on which kind
it was: a missing setting is the blank, a missing page is the grey. The line is a count of things
for the pack's author to fix, not a description of one symptom.

Blanks matter as much as options do. Packs align their columns by hand using empty slots, and there
are a great many of them; dropping blanks would collapse every column a pack laid out.

## What is the game's and what is not

The layout rules, the way a value cycles on a click and the mark on an unapplied change are taken
from the reference's behaviour, because that is what pack authors wrote their files against.

The drawing is not. Every widget here is a vanilla button, so the sprites, the focus ring, the
tooltips and the narration are the game's and work the same on either backend.

The model behind the screen touches no Minecraft class and logs nothing, which is what lets the
whole of it be checked against the pack corpus outside the game in seconds rather than in a play
session.
