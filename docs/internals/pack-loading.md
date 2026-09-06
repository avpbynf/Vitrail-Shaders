# Loading a pack

[The pack format](../pack-format.md) says what a pack is made of and what the engine takes from it.
This page is the level below: how the loader is put together, and which of its choices are load
bearing rather than incidental. It is written for someone about to change that code, so it dwells on
the places where the obvious implementation is the wrong one.

## Opening a pack, and the lifetime that bounds every read

A directory and a zip go through one class, and above that class nothing knows which of the two it
is looking at. A directory becomes a root path; a zip is mounted as a filesystem, and that
filesystem is **owned**: the source closes it, and nothing else may. If anything fails between
mounting the archive and finding its GLSL root, the mount is closed before the failure propagates,
or a rejected pack leaves an archive open for the rest of the session.

The GLSL root is the direct child named `shaders` when there is one. Otherwise the tree is walked to
a bounded depth and the lexicographically smallest match is taken: bounded so a deep archive costs
nothing, smallest so two runs cannot disagree. The pack's name comes from the directory name or the
archive filename, never from anything inside.

**Closing the source invalidates every path taken from it**, and the failure does not surface at the
close: it surfaces much later, on an unrelated read, as a closed-filesystem error with no obvious
relation to the pack that caused it. So the rule is structural rather than careful. Every entry point
that needs to read a pack opens the source in a try-with-resources block and returns records holding
strings, and no loaded form of a pack holds a path. Callers that need a file later reopen the pack;
they do not keep the source alive.

That is also why reading is cheap to repeat and expensive to hold. The loader builds the dimension
set, the properties, the settings index, the program set and the resolution over it, then flattens
every entry point and throws the text away, because at that stage the question is whether the graph
resolves and not what comes out of it.

One ordering inside the loader is not free to change: **the per-program enable flags can only be
computed once the settings are**, because the conditionals in `shaders.properties` read the settings.
Anything that needs to know which programs exist therefore comes after the settings are resolved.

Two ceilings sit on the read itself. A source file past a size ceiling is refused rather than read,
so an archive that unpacks to something enormous is stopped before it is in memory rather than after.
And the same ceiling applies to raw bytes, an image included: an image is not exempt from being
hostile. Where a directive claims a length, the length on disk is asked for separately, so a blob
that does not match its claim is refused without being read whole first.

Decoding never throws. Malformed input is replaced, a leading byte-order mark is dropped, and lines
are split on all three endings including a lone carriage return: a file with classic Mac endings
read as one long line loses every directive in it. A pack that ships one file in the wrong encoding
should lose that file's accented comments, not fail to load.

## The sorted walk, over a closed set of extensions

Only a fixed list of extensions is read as source. Widening it is not free: packs ship JSON meant
for other mods that contains lines beginning with an include directive, and scanning those changes
every number the loader reports. The extension list is the only filter on what becomes source, and
everything it leaves out is still reachable: proving that a name is mentioned nowhere in a pack
means reading those files too, by a second walk that filters on the size ceiling instead.

The walk is then **sorted by pack-relative path**, and that sort is part of the contract. The
settings index keeps the first declaration of a name and drops later ones, and packs do declare the
same setting twice in different shapes: once as a bare switch, once carrying a list of allowed
values. Whichever is seen first decides what kind of setting the player is offered. Directory
iteration order is unspecified, and a zip and a directory do not enumerate alike, so an unsorted walk
gives one pack two different indexes on two machines and neither is wrong.

The lesson generalises: wherever first-one-wins is the rule, traversal order is part of the answer
and has to be written down rather than inherited from the filesystem.

## Resolving a path a pack wrote

There are two resolution entry points and one resolution body. A specification beginning with a
forward slash resolves against the GLSL root; anything else resolves against the directory of the
file that carried the directive. Both then go through the same body, and **that is where the
confinement check lives**: in the resolution rather than in each caller, so that every road to a
file passes through it. Includes and the texture keys of `shaders.properties` share it for that
reason. A pack is downloaded content; without a check that the normalised target is still inside the
GLSL root, a specification made of dots and slashes has the engine read any file the game can reach
and hand it to a shader.

One detail there is easy to get wrong and silent when wrong: **the leading slashes come off before
the resolution, not after.** Resolving an absolute-looking path against a base discards the base, so
the search drops to the root of the archive and finds nothing, and a texture that is not found is
black rather than an error. Packs do write every one of their texture paths that way.

When the exact name does not exist, the parent directory is listed once, cached by directory, and
matched ignoring case. Packs are authored where a name that disagrees with the file on disk still
opens; inside a zip it does not, so the same pack works as a folder and fails as an archive. The
hits are counted so a pack that depends on it can be named rather than merely tolerated.

## The settings index

Three patterns run over each line: a define that may itself be commented out, a typed constant of
a scalar type, and an `#ifdef` or `#ifndef` naming one symbol and nothing else, which records that
something tests that name. The first declaration of a name wins.

**The kind of a setting is decided by whether the rest of the line is empty** once the trailing
comment is stripped, not by whether a list of allowed values is present. Empty is a switch; anything
else is a value, even with no list beside it. A define carrying a value and no list is a value with
nothing to cycle through, not a switch.

The list of allowed values is the first bracketed group anywhere in the trailing comment, not one
required to follow the slashes immediately. Packs routinely describe the setting in words before
offering its values, and requiring adjacency leaves exactly those settings with no choices in the
menu: a failure that shows up as a slider the player cannot move rather than as an error.

**The scan is deliberately naive and has to stay that way.** It runs no preprocessor and skips no
comment block, so a declaration inside a documentation block enters the index, and so does a macro
that takes parameters, its name stopping before the bracket. Both look like defects. Both are what
the measurements the loader is checked against were taken with, and teaching the scan to be clever
moves every total at once, leaving nothing to compare against. Filtering them is a decision to take
deliberately, together with the numbers it moves.

Names that differ only by case are collected and reported. They are harmless while the index is read
case sensitively, which it is, because GLSL identifiers are, but they are the exact shape of a pack
that behaves differently as a folder and as an archive.

The index has a second job beyond listing settings: it is the only way to tell **a name the pack owns
from a name it never declared**. The program-toggle language asks it that question, and so does the
load before it reports what a forced line did or did not move; both give a different answer for an
unknown name than for a declared one.

## Applying a setting where it stands

A chosen setting is applied by rewriting the line that declares it, in place. The alternative
(gathering every setting into a block of defines at the top of the unit) is not a simplification, it
is a different program. A declaration's position is part of its meaning: packs test a setting with
`#ifdef` above the line that declares it, relying on it being undefined at that point, and hoisting
the declaration flips those tests without a word.

The rewrite rules are asymmetric on purpose:

- A switch turned on uncomments the define; a switch turned off **comments the line out** rather
  than defining the name to false, because the pack tests it with `#ifdef`, which any definition at
  all satisfies.
- A value rewrites the define's value. Indentation and the trailing list of allowed values are kept,
  so the line still declares to the menu what it declares to the compiler.
- A switch applied to a numeric constant leaves the line alone: a switch says nothing about a number.
- A switch applied to a boolean constant is written out as the word true or false. A constant is read
  as an expression rather than tested for existence, so commenting it out would leave the name
  undeclared where it is used, and skipping it would drop the player's choice silently.

**Those last two reach a closed list of names and no others.** A `const` the reference does not
configure is a plain constant, whatever its type, and no choice ever rewrites it. The list is the
whole of the gate, though, and that matters for a listed name the option index refuses for a reason
of its own: a `uint`, a number shipped without a list of values to cycle through, a boolean nothing
tests. The rewriter reads one line at a time and holds no index, so a value chosen for such a name
is applied here where the reference drops it. No screen offers the name, so a value for it can only
have been written by hand, in the pack's own settings file or in a profile.

A name the pack declares **nowhere** is not applied at all, and nothing is emitted for it in the
unit's header. There is nowhere to apply it: the section below carries why that is the answer rather
than an omission.

Two consequences for anyone editing the expander. The define table is updated **from the rewritten
line**, not the original, so a later conditional sees what the compiler will see; and when a rewrite
turns a declaration into a comment, the name is removed from the table rather than left behind.
Lines in a branch that is off are emitted exactly as they were and are not rewritten, which follows
from the same principle: the expander only tracks what the compiler will act on.

## Two define tables, and why they must differ

Reading a pack produces two tables of defines. Unifying them makes one of the two readers wrong.

The table used for `shaders.properties` carries the engine's symbols, the default of every setting
the pack does not ship commented out, and the player's choices on top. That file may test any
setting, so every setting the index offers is present before the first line is read. A boolean
constant is the one that enters only while it is true, because an `#ifdef` on one declared false
has to read false whatever text the declaration carries.

The table a source file starts with carries **the engine's symbols and nothing else**. The pack's own
defines enter as the expander walks past their declarations, in file order, exactly as a preprocessor
would, and its constants never do: there is nothing in one for a preprocessor to test. A file that
tests a define above the line declaring it has to see it undefined, because that is what the compiler
will see later.

A chosen name the pack declares nowhere therefore reaches no unit at all. It has no declaration to
rewrite, and writing it into the head of the unit instead is the one thing that must not happen: a
settings name is an identifier, and a pack uses identifiers for its own things.

Which of those names the load says out loud depends on where the name came from, and the difference
is who typed it. A line of `vitrail/options.txt` is named word by word, because a person edits that
file by hand and a typo there is worth a line. A name in the pack's own settings file is named too,
in the line that reports what the menu no longer shows, which is a wider question, and that line
therefore also carries names the pack still declares and still applies. A word both files hold is
left to the first line rather than counted twice.

A profile that names a word its own pack declares nowhere is dropped in silence, and the reference
is nearly as quiet. Four of the forms its parser takes name a setting, and it looks only one of them
up before using it, the bare positive one, which is where it warns. The `!NAME`, `NAME=value` and
`NAME:value` forms go through unchecked, and over the eight test packs those three are 423 tokens of
the 440 the four carry. Here nothing is looked up on any form. What the difference costs is a pack
author's own typo staying invisible in one form of four; it costs the picture nothing, since neither
engine applies the word anywhere.

Under both sits the engine's own table, and three readers have to be handed the same one: the
preprocessor deciding which branch is live, the translator writing those symbols back out, and the
settings menu testing them. A pack read against one table and compiled against another asks for
biomes the engine cannot answer with. So there is one value for the whole process (there is one
machine), and what the machine is arrives as an argument rather than being read where the table is
built. That keeps the loading code free of any graphics API and makes the table buildable with no
device at all, which is what the out-of-game checks in [Developing](../developing.md) depend on.

The table is ordered, and its order is preserved, because it is emitted in order: an ordered emission
is one a person can diff against the previous run.

What it holds are the symbols packs branch on to decide what they are allowed to use: the
reference's own name and version, which a pack tests to know it is on an engine that speaks this
format at all, the game version in the packed form the format specifies, the GL and GLSL levels,
the colour buffer ceiling, the two symbols saying the normal and specular maps are served, one
symbol each for the operating system, the vendor and the renderer, quality and hand-depth
scalars, the render stage
constants taken straight off the engine's own enumeration so the number a pack compares against and
the number a draw carries cannot part company, the block kinds and precipitation kinds packs read
without declaring, and the biome and biome-category symbols, which stay empty until a caller has a
registry to walk. The capability symbols of the `IRIS_FEATURE_` family are posted for every pack,
custom images among them, each of them a promise the engine has to keep; the far terrain symbol is
posted only while that mod is drawing. **A missing symbol does not fail loudly.** It quietly sends
the pack down a fallback path written for a renderer from a decade ago, which is why classifying a
vendor string into the wrong bucket is worse than not classifying it.

## The condition stack, shared on purpose

Each level of the stack remembers three things: whether it is live, whether any branch of it has
already been taken, and whether its parent was live. The middle one is what makes `#elif` and `#else`
behave: once a branch has run, the rest of the chain stays dead even when its own condition is true.

An `#elif` condition is evaluated **only when it can still matter**, and that is not an optimisation:
a later branch may be nonsense once an earlier one has been taken, and evaluating it eagerly makes
the loader answer a question the compiler never asks. An unmatched `#endif` is ignored rather than
fatal, because packs ship them.

The same stack serves the GLSL sources and the properties files. Two implementations would eventually
disagree about what is live, and the disagreement would appear as a program that exists in one place
and not in the other: a defect with no visible cause.

Every reader handed a define table walks its file the same way, whichever of the four it is: **the
conditionals first and the continuations after.** Folding first lets a value continued past the
directive that closes a conditional swallow that directive into the middle of a value, where nothing
recognises it; the conditional above is then never closed and the rest of the file goes dark. Packs
write exactly that, in `block.properties` and in `shaders.properties` alike.

The shader properties file carries a second road, and only for what has to be read before any
setting exists at all: the profiles, the screens and the sliders. That pass folds the continuations
over the whole text and steps over the directives without evaluating one, since the profiles it is
reading are themselves what a conditional would be evaluated against. Everything a setting can
decide is left to the walk above.

The expression evaluator itself is a recursive descent over C precedence, in integers rather than
floating point, because the compiler that sees the same line later will give the C answer whatever is
decided here. Two small things bite: two-character operators are matched before single ones, or `<=`
is read as `<` followed by `=`; and the base of a numeric literal has to be settled before a type
suffix is stripped, since in hexadecimal `f` is a digit and taking it for a float suffix silently
truncates the number. A division by zero, a shift by a negative or absurd amount, and the one
overflowing division are treated as no answer rather than thrown: a condition that cannot be worked
out is not a reason to abandon the load.

## Flattening an entry file

The include graph is a function of the settings, not a property of the pack, because a directive can
sit inside a conditional. That is why changing a setting rebuilds a unit rather than patching one.

A followed include disappears: the directive line is replaced by the included file's lines. An
include in a branch that is off **becomes a comment** rather than being left as it was, because
leaving a directive GLSL has no notion of inside a block the compiler is discarding is a bet on how
that compiler treats directives it is dropping. A specification that resolves to nothing leaves a
literal error directive naming it, so the failure arrives at compile time with a name attached
instead of producing a unit with a hole in it at load time.

Two sets are kept, and they are not the same set. One holds the files on the **current path** and
detects a cycle; the other holds everything seen and only counts re-expansions. Merging them would
amount to include-once, which the format rules out (see
[the pack format](../pack-format.md) for what that would do to a pack's own guards).

Dead branches stay in the output verbatim, but the expander also records **which lines came from a
branch that was taken**. That bit is what a later stage needs: a translator that lifts a declaration
out of a branch nobody takes makes it unconditional, and packs do declare the same name as a uniform
in one branch and as an ordinary global in the other. Conditional directives themselves count as
taken: they are directives, never declarations. What is done with that record is
[Translation](../translation.md).

## What the bounds look like here

[Translation](../translation.md) states the rule: every loop whose trip count depends on pack content
is bounded on total work, and the errors it prevents are not catchable at the call site. This is what
that rule looks like in the loader.

Expansion carries three budgets at once: files expanded, lines written, and characters written.

The file budget is the intuitive one and it is the weakest: it is checked when a file is opened, so
a pack that ships **one enormous file** never expands anything and never reaches it. That is why
lines and characters are checked **on every emitted line** instead. Lines alone would not do either,
because a line is not the cost that matters downstream: the translator that reads the unit holds
far more per line than the line itself.

When a budget runs out, one error line is written and the rest are silent: repeating the message
turns the message itself into the runaway.

The nesting budget inside the expression evaluator is shared between brackets and prefix operators,
because a bracket limit alone bounds nothing: a long run of prefix operators costs the same stack
frames with no bracket in sight. Name resolution and expression evaluation are mutually recursive, so
their budget travels through the whole nest instead of restarting at each hop; two settings defined
in terms of each other would otherwise reach the stack limit.

Profile expansion has the same shape and needs the same pairing. Profiles form a graph, not a tree,
so a profile naming the next one several times multiplies at every level and can run for minutes
without ever reaching a depth limit. A depth guard alone is not a bound; it has to be paired with a
total.
