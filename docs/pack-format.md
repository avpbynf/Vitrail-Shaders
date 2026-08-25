# The pack format

What an OptiFine-format shader pack is made of, and what Vitrail reads from it. This is the page
to start from if you write packs, or if you want to know why a pack behaves the way it does.

## A pack is a directory or a zip, read the same way

Both shapes go through one path. A zip is mounted as a filesystem whose lifetime bounds every read,
which has a consequence worth knowing: nothing that outlives loading may hold a path into the pack.
Once loading ends, the pack is data.

The pack's name comes from the folder name, or from the archive filename without its suffix, never
from anything inside. The GLSL root is `shaders/`, and if it is not directly at the archive root it
is searched at bounded depth in sorted order, which is why a pack someone re-zipped with a wrapper
folder still loads.

Every path handed around afterwards is relative to `shaders/`, with forward slashes. That is the
key used for sorting, display, provenance markers and error text, and it is why no absolute disk
path ever appears in a log line: under a zip it would mean nothing, and under a folder it would
leak the user's directory tree.

**File traversal is explicitly sorted.** That is not tidiness. The option index is first-declaration-
wins, and packs do declare the same option twice in different shapes, so an unsorted walk would
build two different indexes from one pack on two machines.

## How programs are found

**The extension decides the pipeline stage, never the name or the content.** Files ending in
`.glsl`, `.inc` or `.settings` can never be entry points, whatever they are called and wherever
they sit.

**A directory only counts as a dimension directory** if it is the `shaders/` root itself, a name
declared in `dimension.properties`, or a direct subdirectory matching the conventional world-number
shape. Packs do declare arbitrary dimension names through that file, and those folders are simply
lost if it is not read.

What makes a file an entry point is its **extension** and nothing else: a shared body is a `.glsl`,
and it never reaches the question of where it sits. No directory is excluded by name, here or in the
reference: a folder called `lib` is not special, its contents are simply not entry points.

What is left is then filtered twice: the directory has to be a dimension directory, and the base
name has to be a program name. Both catch real cases: shared bodies parked in a subfolder carry
perfectly valid program names, and shared bodies parked inside a dimension folder carry names
outside the list. Whatever is rejected is named in the log rather than dropped silently.

A program name is one of a closed list of geometry names, a numbered family where the unnumbered
spelling means slot zero, or one of a small set of unnumbered names. One detail bites implementers:
**slot indices are sparse and reach well past the historical bound**, so anything that loops from
zero to the first empty slot drops real programs.

A compute program hangs a single-letter suffix off a name that has to be valid without it, and the
suffix is recognised on the numbered families and on two of the unnumbered names, not on the
geometry names. Vitrail reads those files and names them in the log. One family of them runs, the
shadow compute, translated like any other unit and dispatched once a frame wherever the pack's own
toggle keeps it. The rest are named and go no further, so what their order among themselves would
be is the reference's business and not this engine's yet.

Stages are never paired or cross-checked. A compute program and a fragment program with the same
slot name coexist as independent entries, which is a real case.

### A dimension directory replaces the root

This is the rule most likely to be got wrong, because the intuitive reading is the other one.

A pack that ships a dimension folder holding two programs has **exactly two programs** in that
dimension. Everything else there resolves through the fallback tree *inside that folder*, not
through the root. The reference implementation states this in as many words.

Two corollaries follow. The condition is the **existence** of the directory, not its contents, so an
empty dimension directory yields an empty program set rather than falling back to the root, because
emptying a folder is the only way a pack has of saying "nothing here", and reading the base set
instead would overrule it. A directory that is named and *absent* does fall back. And the base set
is not necessarily the root: it is the directory bound to the catch-all entry of the dimension
mapping, and in practice most packs keep no programs at the root at all.

### The fallback tree

When a program is missing, resolution walks up a tree where each program names a parent, and it
substitutes one geometry kind for another: textured and line programs fall back to basic; terrain,
items, entities, the held item, weather and particles to a lit textured program; terrain variants
and water to terrain; and so on. This is not theoretical: packs routinely ship neither of the
intermediate programs and get their terrain through several levels of it.

The numbered families and the final pass have **no fallback at all**. A missing slot is empty, and
nothing is drawn there.

Resolution stops at a name it has already walked, so a mis-edited table cannot loop. It does not
report anything either: the chain simply ends, and the program comes out unserved exactly as if it
had no parent.

## shaders.properties

Only `=` separates a key from its value, and **there is no end-of-line comment**: everything after
the first `=` is the value.

Backslash continuations are joined before the file is split into lines, and the joining rule
swallows the following line's indentation, but never a blank line. That boundary is the whole of
the rule and it is not a detail: widening it to "any whitespace" makes a continued key absorb
whatever block follows it, and packs do end continuations on a blank line. One pack's main screen
swallowed the commented-out block underneath when the rule was written the other way.

The recognised families include profiles, per-program enable flags, custom uniform and variable
declarations, the settings screen and its pages, blending, alpha test and sliders, and also the
buffer sizes, the sky toggles, the shadow caster directives, the noise and custom texture keys, the
images and the per-program flip directives described further down. Patterns are anchored and tried
in a fixed order, first match wins.

**A value is cut on SPACES, and a tab never separates two words.** The lists, which are the screen
and its pages, the two feature lines, the sliders and the profile bodies, take a run of spaces as
one separator; every other directive takes a single space, so a double space there leaves an empty
word in the middle of the value and changes how many words the directive holds. That is not a
nicety of ours, it is where the reference draws the line, and a value written with a tab is read by
neither.

Two rules are worth calling out:

**The per-program enable flag is read through the pack's own preprocessor conditionals**, using the
same condition stack and evaluator as the GLSL, because packs disable whole programs behind
conditionals in this file. Read it flat and you report programs as active that the pack switches
off.

**A program toggle is looked up under one key and one only**: the relative folder path exactly as
written, with no fallback to the bare name. The fallback would look harmless and is not: one pack
conditions `world0/composite1` and `world-1/composite1` on two different expressions, so reading the
bare key when the qualified one is absent would run in the Nether a pass the pack switched off
there. Deriving that key by substituting characters in the folder name misses instead, silently, on
every pack with an unusual dimension name.

**A compute is looked up under the file it ships in, letter included.** A compute hangs off a
name by a single letter, so `world0/composite21_a` is a key of its own and a pack switching off
`world0/composite21` has said nothing about it. The reference keeps such a compute and runs it
with no fragment stage at all, which is why the lookup cannot fall back to the name the letter
came off.

Both an empty value and a non-evaluable expression mean enabled: this file is read fail-open.

**Unrecognised keys are not dropped in silence.** They are counted by prefix and printed, which is
what makes a pack's misspelled key visible: correct to ignore, wrong to lose.

### The shadow caster directives, and the two that are not flags

**`shadow.enabled` comes before all of them.** It says whether the pack wants a shadow map at all,
and an explicit `false` is the only value that moves anything: a pack that says nothing leaves the
stage standing on whichever shadow programs it ships, which is how the reference reads it. Written
false, nothing is ever drawn from the light, and the six directives below decide nothing because
there is no map for them to decide about. Like them it takes a literal boolean and nothing else, a
value that is not one being honoured nowhere, and like them it is read through the pack's own
conditionals, so the line may sit inside a branch of the pack's own settings.

`shadowTerrain`, `shadowTranslucent`, `shadowEntities`, `shadowPlayer`, `shadowBlockEntities` and
`shadowLightBlockEntities` say which families a pack wants drawn into its shadow map. Four of them
default to on; the two that default to off are `shadowPlayer` and `shadowLightBlockEntities`, which
are also the two that are not flags.

**`shadowPlayer` is not a flag that adds the player to the others.** It is what is left when the
others are refused: where `shadowEntities` is on, the player is one of the entities and is drawn
with them, and `shadowPlayer` decides nothing; where `shadowEntities` is off, `shadowPlayer` is the
whole of what the walk extracts, together with whatever the player is riding. Read additively, a
directive that is off by default would keep the player out of every default shadow map there is.

**`shadowLightBlockEntities` is the same shape**, one family down: it is consulted where
`shadowBlockEntities` is off, and there what reaches the map is the block entities that give off
light and nothing else. With both on, the wider one decides and every block entity is drawn.

These six are read through the pack's own preprocessor conditionals, like the per-program enable
flags above and for a sharper reason: packs write the same word twice with two different values in
two arms of one conditional, and some write a word whose value the pack's own settings file then
contradicts. Read flat, the answer is whichever line the file happens to end with, and the most
used packs of the corpus lose their entity shadows to a line their settings had already killed.

**`dhShadow.enabled` belongs to the same family and defaults the other way.** It says whether
Distant Horizons' far terrain is drawn into the map, and a pack that ships a `dh_shadow` and writes
nothing has asked for it: there is no second flag it could have meant. It is read through the
conditionals like the six above, and there it matters more than for any of them, since the one pack
of the corpus that ships the program writes the key three times, true once under a setting it leaves
switched off and false twice. A pack that ships no `dh_shadow` needs no key at all, that name having
no parent in the fallback tree to reach.

`entityShadowDistanceMul` is read too, but as a `const float` of the pack's source rather than as a
key of this file. It bounds how far from the camera a caster that moves may stand and still reach
the map.

`shadow.culling` is read, and **its three written words do not mean what they look like they mean**.
It is not a switch: it names one of four shapes the light measures a section against, and the
default is the tightest of them rather than the loosest.

- nothing written, or `true`: the camera's own volume swept along the light. A section that cannot
  drop anything onto what the camera can see is thrown away before it is drawn.
- `false`: no sweep. The light walks its own volume, the one the map is drawn in, which is what this
  engine did for every pack before the sweep existed.
- `reversed` or `safe_zone`, two spellings of one word: the same sweep, plus a box at the pack's own
  `voxelDistance` that is kept whatever the sweep says of it, for a pack that samples its map from
  places the sweep knows nothing about.

A word this cannot read leaves the default standing, which is what the reference does with it.

**The safe zone's box can have a width now, where for most of the corpus it could not.** The width
comes from a `const float voxelDistance`, and it only counts where it sits on a line that survives
the pack's own preprocessor. Most packs that write one put it behind `IRIS_FEATURE_CUSTOM_IMAGES`,
directly or through the coloured lighting the same flag gates, and while no such define was posed
those lines were dead: the value never arrived, and the box came out nought blocks wide with the
state still chosen, so the shape said safe zone and behaved as the plain sweep. Custom images being
served, a pack that also turns its own coloured lighting on brings a width. A pack that declares the
width outside any such condition always had one.

That box is normally shorter than the shadow distance, so it settles nothing about how far the walk
reaches. What it settles is what the SWEEP would have thrown away: a section inside it is kept
whatever the sweep says. That is what a pack sampling its own voxel grid is owed, because the sweep
is built from what the CAMERA can see extended towards the light, and a grid is read from places the
camera cannot see at all. Without the box, a pack's voxel grid reads air where the world has blocks,
anywhere outside that view.

**How far the walk reaches is a separate question from the shape.** Whichever shape is chosen, a box
around the camera is cut out of it wherever a distance bounds the walk, and that distance is
`shadowDistance` times the `const float shadowDistanceRenderMul` of the pack's own source, or the
player's Max Shadow Distance where the pack declares no multiplier or declares a negative one, the
sign being the whole of the switch. Two of the four states step
outside that arbitration and both do so in the reference: `false` reads the pack's product alone and
never the player's setting, and the safe zone reads neither, its two boxes being the pack's
throughout.

Iris has a fifth road into the first line, and this engine cannot take it: it drops the sweep for a
pack whose shadow program ships a geometry stage, on the grounds that such a pack is moving its
vertices somewhere the camera's volume cannot predict. Nothing here runs a geometry stage at all, so
no pack can move a vertex that way and the question does not arise.

## Settings, and how a user changes them

**Settings are declared inside the GLSL, not in a manifest**: a define, optionally commented out, or
a typed constant. A commented-out declaration is still an offered setting the user can switch on.

The kind of a setting is decided by whether the rest of the declaration line is empty, not by the
presence of a value list. Empty means a toggle; non-empty means a value; a constant is always a
constant. A define carrying a value but no bracketed list stays a value setting with no enumerated
choices: it is not a toggle. The allowed values come from a bracketed comment on the declaration
line.

**A constant is only a setting when its name is on a closed list**, the same twenty five names and
per-buffer spellings Iris configures and nothing else, and the list is necessary rather than
sufficient: a `const bool` also needs an `#ifdef` testing its name, a constant holding a number
needs its bracketed list, and a `uint` never qualifies, the reference reading only `int`, `float`
and `bool` as configurable. Every other `const` is a plain constant of the program, whatever its
comment offers: it appears on no screen, no settings file line can rewrite it, and conditionals
read its name exactly as they read a name the pack never declared. Through those bars, a
`const bool` is a toggle like a bare define, and a numeric constant cycles through its bracketed
values. The same two demands hold for the defines: a bare toggle is only a setting while an
`#ifdef` or `#ifndef` somewhere tests it, and a define carrying a value is only one with a
bracketed list beside it.

The scan deliberately runs with no preprocessor and no comment-block removal, so declarations
sitting inside a documentation block enter the index. That is a fidelity choice, not an oversight:
filtering them is a separate decision that also moves every measurement.

### Applying a setting rewrites the declaration in place

A setting is applied by rewriting its declaration line **where it stands**, not by emitting a block
of defines at the top of the unit. The reason is positional: a define moved to the header changes
the result of any conditional that sits before the original declaration.

A name the pack declares nowhere is not applied at all, and no header define is emitted for it
either. The reason is a different one, and it is worse: with no declaration anywhere there is no
position to argue about, and a header define would simply be a word nobody offered as a setting
landing on top of whatever the pack uses that word for.

The rewrite rules are asymmetric on purpose: a true boolean uncomments the define, a false boolean
comments it out, a value rewrites the define's value, and a value on a constant rewrites only its
right-hand side, when that constant's name is on the closed list at all. A
boolean lands on a constant in one of two ways: on a `const bool` it is written out as `true` or
`false`, since a constant is read as an expression rather than tested for existence and commenting
the line out would leave the name undeclared; on a constant holding a number it is ignored and the
line is left exactly as it was, a switch having nothing to say about a number. Indentation and the
trailing value-list comment are preserved.

### Two define tables, deliberately different

The table used to preprocess `shaders.properties` carries the engine's defines, the default of
every non-constant uncommented setting, and the variant overrides. The per-unit table carries the
engine's defines alone: the pack's own defaults and the overrides applied to them enter as
expansion walks over its define lines, like a real preprocessor.

Unifying them is a mistake, and the asymmetry is the whole point: the properties table has to be
complete before its first line is read, because that file may test any setting, while a source file
has to meet the pack's own defaults where the pack wrote them. A constant that really is a setting
sits in the properties table under its current value, and a boolean one only while it is true,
which is the polarity the reference's preprocessor gives its booleans: an `#ifdef` on one declared
false reads false. No constant is ever in the per-unit table, no preprocessor being able to test
one. A constant that is not a setting enters neither.

### Profiles

A profile is a token list with four shapes: a nested profile reference, a negated name meaning
false, a name with a value, and a bare name meaning true. They are applied in reading order, last
one winning, and each profile is an independent variant: there is no running "current profile"
accumulating across them.

The profile shown to the user is **deduced, not stored**: it is the first profile, from most
constrained to least, whose values all match the ones in force, and Custom otherwise. No profile is
synthesised from the pack's own defaults, here or in the reference, so a reset lands on a named
profile only when the pack happens to declare one holding those defaults, and shows Custom the rest
of the time.

Selecting a profile stages its values rather than applying them, which is what gives the Apply
button something to do.

The screen itself is described by the pack: a root screen key and per-page keys, and a screen is
built from **every** token those keys carry, not from the identifiers alone. A blank is layout and
there are hundreds of them in the corpus; a link opens another page; one token selects a profile;
and what is left over still has to land somewhere: the `*` token pours out every setting no other
page names, where it stands, and what the main screen names comes out of it again, because the
reference builds its leftover list only after its main screen and packs laid their columns against
that. A pack that writes no root screen key at all is read as a main screen holding exactly that
token, one page with everything on it rather than an empty one. A name exposed
on a screen may be declared nowhere in the pack,
so the screen has to tolerate an orphan name: neither crash on it nor fabricate a setting for it.

## Assembling a program's source

An include is recognised only in a strict shape. Angle brackets and quotes are interchangeable and
may be mixed, anything after the closing delimiter is ignored, and widening that tolerance makes
the engine follow includes the reference does not.

A specification starting with a forward slash resolves against `shaders/`; anything else resolves
against **the directory of the file carrying the directive**, not the entry file's directory, and
not the root. There is one attempt, no search path, and no implicit extension.

**A missing include does not abort loading.** A literal error directive is written into the unit and
expansion continues, so the failure surfaces at compile time with a name attached, instead of being
swallowed at load time.

**There is no include-once.** A file included twice is re-expanded in full, because packs guard
themselves with their own sentinels and forcing include-once would neutralise those guards and
change the produced text. Cycle protection is therefore on the current inclusion stack only, never
a global set.

**Dead branches are not eliminated.** Conditional evaluation only decides which files to open;
inactive lines are re-emitted as they stand, because the compiler will re-evaluate the same
conditions on the final text anyway. One line is the exception and it has to be: an `#include` on a
branch that is off becomes a comment naming what was not pulled in, since leaving it would open the
file after all. The line count is preserved either way, which is what the numbering below rests on.

**A non-evaluable condition is taken as true**, and counted. The asymmetry is deliberate: including
too much is recoverable, while a skipped include produces an avalanche of undeclared identifiers
with no visible relation to its cause.

Name resolution inside conditions follows a ladder: an unknown name is zero, a name defined without
a value is one, a numeric value is that number, a bare identifier resolves recursively, and an
expression **keeps its value** rather than collapsing to one or zero.

That last rung is the one that matters, and getting it wrong is subtle. A pack that defines a
shadow resolution as a quality setting multiplied by a base size, and then compares it against a
threshold, is comparing sizes. Reduce it to a truth value and the comparison quietly takes the
wrong branch, with no error anywhere, because both readings are valid conditions.

Arithmetic is done in integers with C semantics, so the engine cannot disagree with the compiler
that re-evaluates the same conditions on the emitted text.

An include directive is replaced by the lines of the file it names, so the unit that reaches the
compiler is one flat text and errors are numbered against that.

Version and extension directives are **blanked in place rather than deleted**, which looks fussy
and is not: later passes carry per-line information about which lines a branch actually took, and
that information is indexed by line number. Remove a line and every index after it is wrong. The
unit takes its version from the header the engine writes, and the directives that were blanked are
counted, so an unexpected one shows up in the totals instead of vanishing.

## Textures a pack supplies itself

A pack can declare its own textures under two key families: one naming a texture by name, one
naming a stage and a sampler. Both are read through the pack's conditionals, so a texture declared
inside a disabled branch is not bound. A declared texture rebinds that sampler name to the file
instead of to the colour target that would otherwise carry the same name.

Four rules here were each paid for:

**A cap on file size does not bound decoding.** A flat-colour image of huge dimensions compresses to
almost nothing and demands gigabytes once decoded, so the decoder reads the image header before the
pixels and refuses beyond a per-side and a total-texel limit.

**A texture the device refuses costs only that texture.** The allocation is caught per texture,
where it previously brought down every colour target of the pack.

**A refused directive must still consume the name it claims.** When a refusal let the name fall
through, a typo in the pixel type made a sampler name resolve back onto the colour target of the
same name, so the pack read the scene where it asked for its own lookup table. That is the exact
shape of a plausible, wrong image. A key naming a stage and a sampler now takes that name whatever
pixel type follows on the line. Two things take nothing: a key this cannot read that far, and a value
naming a file the pack does not ship, which the reference drops whole so that the name goes on
meaning what it meant.

**A three-dimensional volume is flattened onto a two-dimensional atlas**, its declaration rewritten
under a forged name, and each read replaced by a helper that reads two slices and interpolates.
This works because the backend refuses the declared type, not what actually sits behind the sampler.
The rename is applied in every program carrying the declaration, not only in the targeted stage: the
reference renames only in the targeted stage, which leaves an unbound three-dimensional sampler
alive elsewhere: tolerated by the old backend, refused by this one.

## Images and buffers a pack asks the engine for

Separate from the textures above, which are files the pack ships: these are memory the pack asks
the engine to allocate, writes to from its own shaders, and reads back. They are what
`IRIS_FEATURE_CUSTOM_IMAGES` announces, and a pack gates its voxel lighting on that define before
writing a single one of them.

**`image.NAME` declares one image.** The value is the reference's own word list: a sampler name, or
`none` where the image is stored and never sampled; a pixel format, an internal format and a pixel
type; whether the engine empties it every frame; whether the size is a fraction of the screen; then
the dimensions, one, two or three of them, and exactly two where the size is a fraction. Sixteen
images is the ceiling and a seventeenth is
dropped, which is the reference's ceiling and not this engine's. A dimension may be written as the
name of one of the pack's own settings rather than as a number, and it is looked up in the table the
conditionals are evaluated against, which is how a pack sizes a volume with the same setting that
switches its coloured lighting on.

**`bufferObject.N` declares one shader storage block**, at an index of nought to twelve. The value
is a size in bytes and an optional block name, or the reference's four-word relative form where the
size follows the screen. A size below one disables the buffer rather than allocating an empty one.

Both are read through the pack's conditionals like everything else in this file, which matters more
here than anywhere: most of the corpus writes these lines behind the coloured-lighting setting, so
read flat a pack would be handed volumes its own settings had already switched off. **A line that is
live and cannot be read is named in the log and dropped**, one line each, rather than taking the
declaration next to it with it.

## A pack is untrusted content

This is the frame to keep in mind for everything above. A shader pack is a file someone downloaded.

**Any path a pack writes is refused if it normalises to somewhere outside `shaders/`.** Without that
check, a pack can make the engine read an arbitrary file from the user's disk and hand it to a
shader. That door is not only the include directive: the texture keys go through it too, which is
why they were routed through the same resolution rather than given their own.

Path resolution also falls back to a case-insensitive lookup in the parent directory, cached per
directory, because a folder on a case-insensitive filesystem and a zip do not agree: a pack that
misspells the case of one of its own files works in one shape and breaks in the other.

And every loop whose trip count depends on pack content is bounded on **total work** rather than on
nesting depth. [Translation](translation.md) covers why that distinction is the one that matters.
