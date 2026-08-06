# The pack format

What an OptiFine-format shader pack is made of, and what Vitrail reads from it. This is the page
to start from if you write packs, or if you want to know why a pack behaves the way it does.

## A pack is a directory or a zip, read the same way

Both shapes go through one path. A zip is mounted as a filesystem whose lifetime bounds every read,
which has a consequence worth knowing: nothing that outlives loading may hold a path into the pack.
Once loading ends, the pack is data.

The pack's name comes from the folder name, or from the archive filename without its suffix, never
from anything inside. The GLSL root is `shaders/`, and if it is not directly at the archive root it
is searched at bounded depth in sorted order - which is why a pack someone re-zipped with a wrapper
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

Entry points are then filtered by three cumulative rules: directories named for shared code are
excluded outright; the directory must be a dimension directory; and the base name must be a program
name. Both of the later filters catch real cases - shared bodies parked in a subfolder carry
perfectly valid program names, and shared bodies parked inside a dimension folder carry names
outside the list. Whatever is rejected is named in the log rather than dropped silently.

A program name is one of a closed list of geometry names, a numbered family where the unnumbered
spelling means slot zero, or one of a small set of unnumbered names, plus an optional single-letter
suffix. Two details bite implementers: **slot indices are sparse and reach well past the historical
bound**, so anything that loops from zero to the first empty slot drops real programs; and compute
programs sharing a slot are distinguished by that letter suffix and run in its alphabetical order,
the unsuffixed one first.

Stages are never paired or cross-checked. A compute program and a fragment program with the same
slot name coexist as independent entries, which is a real case.

### A dimension directory replaces the root

This is the rule most likely to be got wrong, because the intuitive reading is the other one.

A pack that ships a dimension folder holding two programs has **exactly two programs** in that
dimension. Everything else there resolves through the fallback tree *inside that folder*, not
through the root. The reference implementation states this in as many words.

Two corollaries follow. The condition is the **existence** of the directory, not its contents, so
an empty dimension directory yields an empty program set rather than falling back to the root. And
the base set is not necessarily the root: it is the directory bound to the catch-all entry of the
dimension mapping, and in practice most packs keep no programs at the root at all.

### The fallback tree

When a program is missing, resolution walks up a tree where each program names a parent, and it
substitutes one geometry kind for another - textured and line programs fall back to basic; terrain,
items, entities, the held item, weather and particles to a lit textured program; terrain variants
and water to terrain; and so on. This is not theoretical: packs routinely ship neither of the
intermediate programs and get their terrain through several levels of it.

The numbered families and the final pass have **no fallback at all**. A missing slot is empty, and
nothing is drawn there.

Resolution carries a visited set, so a mis-edited table fails rather than loops.

## shaders.properties

Only `=` separates a key from its value, and **there is no end-of-line comment**: everything after
the first `=` is the value.

Backslash continuations are joined before the file is split into lines, and the joining rule
swallows the following line's leading whitespace across blank lines - which a stock properties
reader does not do. Real packs depend on it, so a stock reader produces a different screen
definition from the same file.

The recognised families cover profiles, per-program enable flags, custom uniform and variable
declarations, the settings screen and its pages, blending, alpha test, and sliders. Patterns are
anchored and tried in a fixed order, first match wins.

Two rules are worth calling out:

**The per-program enable flag is read through the pack's own preprocessor conditionals**, using the
same condition stack and evaluator as the GLSL, because packs disable whole programs behind
conditionals in this file. Read it flat and you report programs as active that the pack switches
off.

**A program toggle is looked up first as dimension-qualified, then bare**, with the dimension key
being the relative folder path exactly as written. Deriving that key by substituting characters in
the folder name makes every dimension-qualified key of packs with unusual dimension names miss,
silently.

Both an empty value and a non-evaluable expression mean enabled: this file is read fail-open.

**Unrecognised keys are not dropped in silence.** They are counted by prefix and printed, which is
what makes a pack's misspelled key visible - correct to ignore, wrong to lose.

## Settings, and how a user changes them

**Settings are declared inside the GLSL, not in a manifest**: a define, optionally commented out, or
a typed constant. A commented-out declaration is still an offered setting the user can switch on.

The kind of a setting is decided by whether the rest of the declaration line is empty, not by the
presence of a value list. Empty means a toggle; non-empty means a value; a constant is always a
constant. A define carrying a value but no bracketed list stays a value setting with no enumerated
choices - it is not a toggle. The allowed values come from a bracketed comment on the declaration
line.

The scan deliberately runs with no preprocessor and no comment-block removal, so declarations
sitting inside a documentation block enter the index. That is a fidelity choice, not an oversight:
filtering them is a separate decision that also moves every measurement.

### Applying a setting rewrites the declaration in place

A setting is applied by rewriting its declaration line **where it stands**, not by emitting a block
of defines at the top of the unit. The reason is positional: a define moved to the header changes
the result of any conditional that sits before the original declaration.

Only names the pack declares nowhere are emitted as header defines. The rewrite rules are
asymmetric on purpose - a true boolean uncomments the define, a false boolean comments it out, a
value rewrites the define's value, a boolean on a constant leaves the line untouched, and a value on
a constant rewrites only its right-hand side. Indentation and the trailing value-list comment are
preserved.

### Two define tables, deliberately different

The table used to preprocess `shaders.properties` carries the engine's defines, the default of
every non-constant uncommented setting, and the variant overrides. The per-unit table carries the
engine's defines and only those overrides the pack never declares - the pack's own defaults enter
as expansion walks over its define lines, like a real preprocessor.

Unifying them is a mistake, and there is a known consequence of the split: **configuration
constants are never injected as defines**, so a condition in `shaders.properties` that tests a
constant name - a shadow resolution, a sun path rotation - resolves that name to zero.

### Profiles

A profile is a token list with four shapes: a nested profile reference, a negated name meaning
false, a name with a value, and a bare name meaning true. They are applied in reading order, last
one winning, and each profile is an independent variant - there is no running "current profile"
accumulating across them.

The profile shown to the user is **deduced, not stored**: it is the first profile, from most
constrained to least, whose values all match the ones in force, and Custom otherwise. That is what
makes a reset land back on a named profile rather than an empty field, since the pack's own defaults
do constitute a profile.

Selecting a profile stages its values rather than applying them, which is what gives the Apply
button something to do.

The screen itself is described by the pack: a root screen key and per-page keys, whose tokens are
filtered down to plain identifiers. A name exposed on a screen may be declared nowhere in the pack,
so the screen has to tolerate an orphan name - neither crash on it nor fabricate a setting for it.

## Assembling a program's source

An include is recognised only in a strict shape. Angle brackets and quotes are interchangeable and
may be mixed, anything after the closing delimiter is ignored, and widening that tolerance makes
the engine follow includes the reference does not.

A specification starting with a forward slash resolves against `shaders/`; anything else resolves
against **the directory of the file carrying the directive** - not the entry file's directory, and
not the root. There is one attempt, no search path, and no implicit extension.

**A missing include does not abort loading.** A literal error directive is written into the unit and
expansion continues, so the failure surfaces at compile time with a name attached, instead of being
swallowed at load time.

**There is no include-once.** A file included twice is re-expanded in full, because packs guard
themselves with their own sentinels and forcing include-once would neutralise those guards and
change the produced text. Cycle protection is therefore on the current inclusion stack only, never
a global set.

**Dead branches are not eliminated.** Conditional evaluation only decides which files to open;
inactive lines are re-emitted verbatim, because the compiler will re-evaluate the same conditions
on the final text anyway.

**A non-evaluable condition is taken as true**, and counted. The asymmetry is deliberate: including
too much is recoverable, while a skipped include produces an avalanche of undeclared identifiers
with no visible relation to its cause.

One rule inside conditions looks wrong and is kept, because it is what the reference does: an
unknown name is zero, a name defined without a value is one, a numeric value is that number, a bare
identifier resolves recursively, and anything else is evaluated as a boolean - so a name defined to
a parenthesised expression compares equal to one, not to its arithmetic value. Arithmetic is done in
integers with C semantics, so the engine cannot disagree with the compiler that re-evaluates the
same conditions on the emitted text.

Each opened file is bracketed by provenance comments carrying its pack-relative path. They are
comments rather than line directives, so the compiler numbers errors against the flattened unit,
and a separate span map records which output range came from which source file. Every version
directive is commented out in place rather than deleted, and the first one is hoisted, because the
line map requires the line count not to change.

## Textures a pack supplies itself

A pack can declare its own textures under two key families - one naming a texture by name, one
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
same name - so the pack read the scene where it asked for its own lookup table. That is the exact
shape of a plausible, wrong image. A key naming a stage and a sampler now takes that name whatever
follows on the line; only an unreadable key takes nothing.

**A three-dimensional volume is flattened onto a two-dimensional atlas**, its declaration rewritten
under a forged name, and each read replaced by a helper that reads two slices and interpolates.
This works because the backend refuses the declared type, not what actually sits behind the sampler.
The rename is applied in every program carrying the declaration, not only in the targeted stage: the
reference renames only in the targeted stage, which leaves an unbound three-dimensional sampler
alive elsewhere - tolerated by the old backend, refused by this one.

## A pack is untrusted content

This is the frame to keep in mind for everything above. A shader pack is a file someone downloaded.

**Any path a pack writes is refused if it normalises to somewhere outside `shaders/`.** Without that
check, a pack can make the engine read an arbitrary file from the user's disk and hand it to a
shader. That door is not only the include directive - the texture keys go through it too, which is
why they were routed through the same resolution rather than given their own.

Path resolution also falls back to a case-insensitive lookup in the parent directory, cached per
directory, because a folder on a case-insensitive filesystem and a zip do not agree: a pack that
misspells the case of one of its own files works in one shape and breaks in the other.

And every loop whose trip count depends on pack content is bounded on **total work** rather than on
nesting depth. [Translation](translation.md) covers why that distinction is the one that matters.
