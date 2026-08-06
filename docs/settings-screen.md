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

Clicking a value changes nothing on screen but the value itself. Nothing is written, nothing is
recompiled, and leaving never writes - a pack read again for somebody who was only looking is a
second of hitch nobody asked for. This is the one convention of the reference's screen deliberately
not kept: it applies on the way out.

What keeps that from being a trap is that **Done is not offered while anything is waiting**. The
last button of the row is Apply until there is nothing left to apply, and only then does it become
Done. The word that leaves is never the word that would throw work away.

A setting changed and not yet applied is marked, in the colour the reference marks it in, for the
same reason the layout rules are the reference's: it is what the pack's author saw.

Apply writes by reading the file first and laying the pending changes over it, so an edit made by
hand while the screen is open and an edit made here compose rather than overwrite each other.

Two consequences of not applying on the way out are worth knowing before they surprise you.
**Going back to the pack list throws away what is pending**, deliberately. And **clicking a pack in
that list is not a selection, it is a load**: it writes the pack file and reloads there and then,
where the reference would have waited for Apply.

## The profile is worked out, not remembered

A pack can group its settings into profiles, and the screen shows one as if it were an ordinary
setting whose values are the profile names.

What it shows is derived from the values currently in effect: the most constrained profile all of
whose values match, and *Custom* when none does. The name is stored in the settings file, but the
values are the authority - which is what makes the label survive a Reset. After a reset the file is
gone and the pack's own values are back, and those values still amount to a profile.

Choosing a profile puts its values in the pending set like any other change, so Apply is what makes
it real.

## The pack list refreshes itself

There is no reload button, in either reference. The folder is looked at about once a second while
the list is on screen, so a pack dropped in appears on its own.

## Why this is a screen of its own and not a page in Sodium's

Sodium ships a configuration API - in its Fabric artefact. **The NeoForge artefact of the same
version ships none of it**, not one class of that package, and this project targets NeoForge. The
version being built against is pinned in the build properties; the asymmetry is worth re-checking
whenever that pin moves, since it is a fact about somebody else's packaging rather than a design.

So an entry inside Sodium's own options would have to be made by reaching into its internals. The
reference implementation does exactly that, and has a screen of its own anyway, reached from a
placeholder entry. Aiming straight at a page integrated into Sodium would be aiming at something
that does not exist there to be integrated with.

## Where settings live, and why one file per pack

Each pack gets its own file, `vitrail/settings/<pack file name>.txt`. That is not tidiness.

A setting the player chose is written into the head of every GLSL unit of the pack. Names do not
overlap between packs - most of the names in the corpus are foreign to any one pack - so a single
shared file would inject a large number of bare identifiers into a pack's GLSL the moment the screen
started writing everything anybody had ever touched.

**A name a pack no longer declares is kept, not dropped.** It is reported once and left in the file.
The reference deletes such names, which loses a player's settings for good the day they try a new
version of a pack and go back. It also stays in what the pack is built with, because the authority
on what a pack declares is the pack's own option index rather than the menu: a setting can still be
declared and simply no longer be on a page.

`vitrail/options.txt` keeps its own job and is never written by this screen.

## The four layers, from the bottom

A value shown on this screen is the top of a stack:

1. what the pack's own source declares,
2. the profile the pack names,
3. the pack's settings file - what this screen writes,
4. `vitrail/options.txt`, over everything.

The last layer is deliberately last and deliberately global. It is how a pass is proved to run, it
may name a setting no pack declares at all, and it is edited by hand while the game is running. What
it holds is reported to the screen so those settings can be greyed out, rather than letting a click
lose to them in silence.

## Settings the reference wrote

A settings file the reference wrote for the same pack is read when there is no file here yet, once,
and never written back. It does not know the profile line, so rewriting one of its files would
either lose that line or leave the player's next session under the reference reading something it
does not understand.

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
