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

The screen itself does redraw. One click can move ten widgets, because a setting can be the
condition of others, and the status line and the last button change with it.

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

A left click walks a setting's values forwards and a **right click walks them back**. Shift and a
click hand back the value the pack ships; from the keyboard, control does that and shift walks
backwards instead, a keyboard having no second button to walk back with.

## The profile is worked out, not remembered

A pack can group its settings into profiles, and the screen shows one as if it were an ordinary
setting whose values are the profile names.

What it shows is derived from the values currently in effect: the most constrained profile all of
whose values match, and *Custom* when none does. The name is stored in the settings file, but the
values are the authority - which is what makes the label survive a Reset, where the file is emptied
and the pack's own values come back and still amount to a profile.

Choosing a profile puts its values in the pending set, so Apply is what makes it real. It is not
quite like any other change though: a profile decides every setting it names, so it overrides a
value already waiting on one of them, and leaves the settings it does not name exactly where they
were.

**Reset empties this pack's settings file rather than deleting it**, and the difference matters. A
missing file falls back to the one the reference left for the same pack, so deleting ours would hand
the pack the reference's settings instead of its own and land a Reset on values nobody chose here. A
file holding nothing but its header is how this side says out loud that nothing was chosen. Reset
also drops what is pending - a pending value is a change to the settings being discarded - and it
does not touch `vitrail/options.txt`, so anything greyed out stays greyed out afterwards.

## The pack list refreshes itself

There is no reload button, in either reference. The folder is looked at about once a second while
the list is on screen, so a pack dropped in appears on its own.

## Why this is a screen of its own, and what it does not rule out

It is its own screen because this is where the value is: the layout, the four layers, the pending
set and what Apply writes are all this project's problem, and none of them get easier by living
somewhere else.

That is a different question from whether it can also be *reached* from Sodium's options, and the
answer there is yes. Sodium publishes a configuration entry point, and the reference implementation
registers through it - declaring an entry point class in its mod metadata and implementing Sodium's
own interface, so that a page appears in Sodium's options and opens the reference's own screen. No
reaching into internals is needed for the entry itself.

**A trap worth knowing before measuring any of this yourself**: what Maven serves as Sodium's
NeoForge artefact is a launcher shim of a few dozen classes, and the mod is a jar nested inside it.
Looking for a package in the outer jar finds nothing and proves nothing. This project's own build
script unpacks the nested jar for exactly that reason.

## Where settings live, and why one file per pack

Each pack gets its own file, `vitrail/settings/<pack file name>.txt`. That is not tidiness.

A setting the player chose is written into the head of every GLSL unit of the pack, whether or not
that pack declares it. Packs barely share names: on the corpus, the great majority of the names one
pack knows are foreign to the next. So a single shared file would inject every identifier anybody
had ever touched into every pack's GLSL, the day the screen started writing what it was given.

**A name a pack no longer declares is kept, not dropped.** It is reported once and left in the file.
The reference deletes such names, which loses a player's settings for good the day they try a new
version of a pack and go back. It also stays in what the pack is built with, because the authority
on what a pack declares is the pack's own option index rather than the menu: a setting can still be
declared and simply no longer be on a page.

`vitrail/options.txt` keeps its own job and is never written by this screen.

## The layers, and why the screen's order is not the engine's

**What the engine builds a pack with** stacks from the bottom: what the pack's own source declares,
then the profile the pack names, then the pack's settings file, then `vitrail/options.txt` over
everything.

**What the screen shows you** resolves in a different order, and the difference is the profile.
There, a profile you have chosen sits *above* the settings file rather than under it: picking a
profile is meant to decide the settings it names, and it would decide nothing if the file you are
editing outranked it. Read from the top: what is pending, then a chosen profile, then the settings
file, then the applied profile, then the pack's own default. That is how the reference behaves too.

The `options.txt` layer is deliberately last in both orders and deliberately global. It is how a
pass is proved to run, it may name a setting no pack declares at all, and it is edited by hand while
the game is running. It decides what is drawn and never what is written.

## What is greyed out, and why

Three different things, and only one of them is about you being overruled.

- A setting `vitrail/options.txt` forces. Its value is reported to the screen so the widget can be
  greyed rather than letting a click lose to it in silence.
- A **heading**, which a pack writes as a setting with no values to walk through. There is nothing
  to click.
- A **link to a page the pack never wrote**. It would lead nowhere, so it does not lead.

## Settings the reference wrote

A settings file the reference wrote for the same pack is read when there is no file here yet, once,
and never written back. The reference does not know the profile line this side writes, so rewriting
one of its files would silently drop what the player chose there the next time they opened the pack
under it.

## What a broken layout does

A slot naming an option the pack does not declare leaves a blank and a warning line rather than
throwing. A handful of slots in the test corpus do exactly that - a pack that ships one broken name
is still a working pack.

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
