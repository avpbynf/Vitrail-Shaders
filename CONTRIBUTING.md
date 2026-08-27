# Contributing

Interactions here are covered by the [code of conduct](CODE_OF_CONDUCT.md), and
anything that looks exploitable goes through [SECURITY.md](SECURITY.md) rather
than the issue tracker.

## The short version

Everything under it is the reference. What bites somebody who skips the page:

- Branch from `dev`, name it `<type>/what-it-does`, and open the request **against `dev`**. A hook
  refuses another name, and the base defaults to `main`.
- One logical change per commit, and a conventional-commit subject of 72 columns or fewer.
- A change a player would notice writes its `CHANGELOG.md` entry in the same commit.
- Files are UTF-8 with no byte order mark, and line endings are LF. A BOM breaks anything that
  feeds sources to a GLSL compiler.
- `gradlew build` is the check and it fails on warnings. Run it before pushing, not after.

## Branches

Two long-lived branches, and the difference between them is one question: has this been published?

- `dev` is where work lands. It stays buildable, and it is what a topic branch is opened from and
  rebased onto.
- `main` is what is out there. Every commit on it has been released under some tag, and nothing
  reaches it except by fast-forward from `dev` at the moment of a release.

So `main` is always an exact prefix of `dev`, which is what makes the two readable side by side:
whatever `dev` holds beyond `main` is precisely what is written and not yet published.

Every other branch is a topic branch, and it is named for what it does:

    <type>/what-the-branch-does

The type is one of the nine a commit subject uses, listed under [Commits](#commits), and it is
whatever the branch mostly is. After the slash come two to five words in lower case joined by
dashes, saying what the branch does rather than which class it opens.

    feat/entity-color-from-overlay
    fix/hand-bob-frame
    ci/commit-and-branch-format

`release/0.5.0-beta` is the one shape that departs from it, the version being the whole of the
name: such a branch carries the version bump and nothing else. No name records who wrote the
branch, or when, or which issue it answers. That last one is a choice rather than an absence: the
commit that does the work names the issue it closes, and a branch name carrying the number would
say it in the one place nothing reads it back from.

**The history is linear and carries no merge commit anywhere, which is not a preference.** A tree
that forks is a tree nobody reads once it is public, and this one is public. A topic branch is
rebased onto `dev` and enters by a pull request, merged with the rebase button. If that button
refuses, the rebase was not done, and the answer is to rebase rather than to merge.

**Rebase a topic branch as soon as `dev` moves under it, and read the whole of
`git diff dev..HEAD` afterwards rather than only the hunks git marked.** Git follows a file that
moved. What it does not follow is code that moved *between* classes, which comes back as a
conflict whose two sides are about different files. And a fact may change on one side while the
other side's prose still describes the old one, in a paragraph far from anything git touched: that
compiles, reads well, and is simply false. Both happen because a fact is cited in more places than
it lives in.

**`dev` reaches `main` by a fast-forward and NOT by the merge button.** That button always writes
new commits, and `main` is already an ancestor of `dev`, so replaying `dev` onto it would give
`main` a second copy of every commit under a different hash. A pull request is still the right
place to look at a release, for the record and for the checks it runs. What merges it is

    git push origin origin/dev:main

and the request closes itself as merged once its commits are on `main`. That one has a template of
its own, `.github/PULL_REQUEST_TEMPLATE/release.md`, opened with
`gh pr create --base main --head dev --template release.md`. It asks the version in both the places
it is written, and one question no command can answer: whether the range about to be published
carries a batch that changed the picture and left no changelog line.

No repository setting can refuse the wrong press, so `prefix.yml` checks after the fact, on every
push to `main`, that `main` is still contained in `dev`. Recovering is a reset of `main` back onto
`dev`, cheap for exactly as long as nothing has been built on top.

## Pull requests

**Every batch enters by one, including one written by whoever owns the repository.** Folding a
branch in locally skips the one thing the pull request is for: `build.yml` runs on `pull_request`,
so a batch that goes in by hand is built only once it is already in `dev`, and a red build then
lands on the branch everything else is opened from.

**"Rebase and merge", and neither of the other two buttons.** "Create a merge commit" forks a
history that never forks. "Squash and merge" collapses a branch into one commit and throws away the
bodies, which is where the reasoning for each step lives: a branch is a sequence of logical changes
here rather than a unit of work, and the sequence is the part worth keeping. A rebase button that
refuses means the branch is behind `dev`: rebase it and force-push, rather than merging `dev` into
it.

That takes two settings, because each one lets through what the other stops. The repository allows
the rebase merge alone, and a ruleset requires a linear history on `main` and `dev`. **The second
does not imply the first**: a squash is linear, so the ruleset would take it happily, and it is the
button setting that rules it out. The same ruleset refuses the deletion of either branch.

Two more rulesets require status checks, and they are what turns a check into a refusal. On `dev`:
`build`, `commits` and `label`. On `main`: `build` and `main-from-dev`, the second being a workflow
that fails every request not opened from `dev`, so what the fast-forward moves onto `main` is
exactly what a green request from `dev` carried. Repairing either branch by hand therefore means
switching its ruleset off for the length of the push, which is a deliberate act rather than a slip.

`.github/pull_request_template.md` is what a request opens with, and its four headings are the four
questions this repository answers before anything lands. Three of them are ordinary. The one that
is not is the second, "how it differs from the reference": packs are written against Iris, so a
difference in behaviour is a pack rendering wrongly however good the reason sounds, and the answer
is either "it does not" or the three parts a divergence owes.

## Commits

One logical change per commit, and a subject in the form the wider ecosystem calls a conventional
commit:

    <type>(<scope>)!: what the commit does

The rebase button replays every subject verbatim into the public history instead of collapsing them
into the request's title, so a subject is not a note to a reviewer: it is the line somebody reads
two years later, in a log where `git log --oneline` is the only thing they will look at.

| type | what it carries |
| --- | --- |
| `feat` | geometry served, a uniform provided, a screen opened: something the engine did not do |
| `fix` | a defect corrected, in the image or in what a pack is allowed to say |
| `perf` | the same behaviour for less |
| `refactor` | no change of behaviour at all, dead code removed included |
| `docs` | `docs/`, the changelog, this file, javadoc on its own |
| `test` | the out-of-game harness and the corpus it runs over |
| `build` | Gradle, the JDK, loader versions, what the build refuses, the version bump |
| `ci` | `.github/workflows` |
| `chore` | whatever none of the others is |

The scope is optional and names a tree of code: `pack`, `glsl`, `uniform`, `render`, `screen`,
`settings`, `sodium`, `mixin`, `platform`, or `neoforge` and `fabric` for a whole module. It is left
out where the type already says everything, which `docs` and `ci` usually do.

The subject is imperative and therefore starts on a verb, in lower case, and it carries no full
stop. The whole line is 72 columns or fewer with the prefix counted in, and the prefix is not free:
`feat(render): ` is fourteen of them. A body is for the reason, when the reason is not in the diff,
and a blank line separates it from the subject.

A `!` before the colon marks a change that breaks a pack or a configuration that used to work. What
breaks is written in the body, and there is no `BREAKING CHANGE:` footer: the changelog is written
by hand and the version typed by a human, so nothing here would read one.

**An issue is closed from the COMMIT that closes it and never from the pull request**, on a line of
its own at the foot of the body:

    Closes #30

A closing keyword fires when the request it is written in merges into the DEFAULT branch, which
here is `main`, and every request here merges into `dev`: written in a request's body it links the
issue and then leaves it open for good. Written on the commit it travels with it and lands on
`main` the day `dev` does. **Check it really closed once `dev` has landed**, this being the one
rule here whose far end is a service rather than a script.

```
feat(render): read entityColor off the entity mesh overlay
fix(pack)!: refuse a customTexture path that leaves the pack
docs: correct five sentences against the source
build: raise the version to 0.5.0-beta
```

A commit that changes a mechanism and the paragraph describing it is one commit under the type of
the mechanism rather than two, since a changelog entry and a doc line belong with the change that
made them true. `docs` is for the batch that is documentation and nothing else.

None of this is retroactive, and the line falls at what LANDS rather than at what was written. A
subject already in `dev` or `main` is history and stays as it is. A branch opened before the
convention and not yet landed is not history: it owes a rebase anyway, since `dev` has moved under
it, and its subjects are amended in that same gesture.

### Both ends of the rule are one file

`.githooks/commit-msg` refuses a subject or a branch name that is not in the form above. Install it
once per clone, worktrees sharing the same config:

    git config core.hooksPath .githooks

`.github/workflows/commits.yml` runs that same file over every commit of a pull request, for
whoever never ran that command and for anything arriving from a fork. It is deliberately the same
file: a workflow rewriting the same expression would be a second home for the rule, and the two
would agree only until one of them changed.

`.githooks/prepare-commit-msg` sits one step earlier and takes a `Co-authored-by`, `Made-with` or
`Generated-by` line out of the message before it is stored. What the log keeps is the reasoning for
a change, and a trailer naming an editor is not that.

Catch it at the commit rather than at the request: a subject is amended in one gesture while it is
still the last one, and rebuilt by hand once nine commits stand on top of it. The hook reads the
branch you are standing on too, so a name outside the form refuses the next commit wherever it was
going, until `git branch -m <type>/what-it-does` renames it and the request it was pushed to is
replaced by one on the new name. `main`, `dev` and a detached HEAD are exempt, which is why a
rebase replays a branch without ever asking.

## Labels

Everything carries one, requests and issues alike, and neither set is decoration: a list of a dozen
open things has to say what each one is before any of them is opened.

**A pull request carries the type of its branch, and only that**, so the label is not a second
opinion about the change: it is the word already in the branch name and at the head of every
subject the branch carries. **The repository poses it itself**, off the
branch name and at the moment the request is opened (`.github/workflows/label.yml`), a label
derived rather than decided having no reason to be asked of anybody. Where it posts nothing, the
branch opened on a word this convention does not know, and `commits.yml` is what says so.

**A release request carries `release` and no type**, posed by that same workflow. Two requests
carry it: `release/<version>` into `dev`, which lands the bump, and `dev` into `main`, which
records the fast-forward.

**An issue carries what it is about instead**, being a report rather than a change:

| label | what it marks |
| --- | --- |
| `known limitation` | a gap this engine already knows about, opened here rather than waited for |
| `pack compatibility` | a pack does not draw as it should |
| `upstream` | the cause is in another project or in the backend, and nothing here closes it |

beside GitHub's own `bug`, `enhancement` and `question`, which the issue forms set themselves. The
two sets do not overlap: a type says what a change does, and these say what a report is about. A
known limitation is open as an issue rather than living in a file for that reason, so that a branch
can point at one.

## Changelog

`CHANGELOG.md` carries an `Unreleased` section, and a change a player would notice is written into
it in the same commit that makes the change, not gathered from the log afterwards. Gathering
afterwards is how a subject line ends up standing in for an entry, and the two are not the same
thing: a subject says what the commit did to the tree, an entry says what the version does
differently to somebody running it. A refactor that changes nothing a player sees gets no entry.

Raising `mod_version` is what closes the section: `Unreleased` becomes that number, and the section
is what the release body and both stores are handed.

## Versions and releasing

A version is three numbers, and after them either `-alpha`, or `-beta`, or nothing at all. Those
three are one version reached in order: `0.5.0-alpha`, then `0.5.0-beta`, then `0.5.0`. Nothing
follows the word, and a counter least of all, so `0.5.0-beta.1` is not a version here. There is no
second beta of a version: a beta that needs a fix is a new version whose patch number moves,
`0.5.1-beta`. The hook refuses a release branch named otherwise, and the release workflow refuses
such a tag before it builds anything.

The version lives on one line, `mod_version` in `gradle.properties`, and a tag is `v` followed by
what that line holds. Nothing derives one from the other: a human types the tag, and the release
workflow refuses it when the two disagree rather than publishing a jar named after one and built
from the other. The target Minecraft version is in the artifact name and comes from the same file.
A tag is pushed on `main` and on nothing else, which is what keeps that sentence true.

Publishing, in order: rewrite the pack table at the head of
[docs/compatibility.md](docs/compatibility.md) against whatever has been seen since the last one,
bump `mod_version`, land those commits on `main` the way every other commit lands, push `main`,
then tag that commit `v` plus the same version and push the tag. The order matters, a tag push
carrying its own objects and nothing else: tagging before the branch is pushed publishes a commit
that is on no branch.

**Pushing the tag is what publishes.** `.github/workflows/release.yml` runs the same
`gradlew build` anyone runs, creates a GitHub release under the tag's own name, attaches the jar
that build produced, and mirrors it to CurseForge and to Modrinth from the same job. Those three
are the only places a build goes. A version carrying `-alpha` or `-beta` is published as a
pre-release, one carrying neither as a release. The body both stores are handed is this version's
entry in `CHANGELOG.md`, read back off the release page at the moment the run reaches it, and the
run refuses the tag when that entry is missing rather than publishing an empty one. A body
corrected by hand afterwards reaches the stores by running the workflow again on that tag, and by
no other road.

## Encoding and text

UTF-8 without BOM everywhere, accents included. A BOM breaks several tools in this
project's toolchain, in particular anything that feeds files to a GLSL compiler.

Line endings are normalised to LF in the repository by `.gitattributes`. Do not commit
files with CRLF.

## Code

Java: standard formatting, tabs for indentation to match Minecraft and Sodium sources,
so that diffs against decompiled code stay readable.

Comments in English, and there are a lot of them: the density is deliberate rather than
accidental. A class says what it is for and what goes wrong without it, a line says why it
is where it is and what would break if it moved, and a figure says what was measured to
arrive at it. What is not wanted is the other kind, the comment that restates the line
below it.

Two more go with that one. A comment says what holds now and not how the code got there, so
"this used to read" and the story of the batch that changed it belong to the history. And a
mechanism has one home in `docs/`, so a comment that explains one a second time is a copy that
will drift: point at the page, and keep only the trap the page does not carry.

One rule is not negotiable, because a pack is downloaded content: a path a pack writes
never leaves the pack. `customTexture.x = ../../../secret.png` served an arbitrary PNG of
the disk to a shader until c1c50c0 confined it, the way an include was already confined.
Anything new that turns text from a pack into a file on disk goes through the same
confinement.

## Verifying a change

`pack/`, `glsl/` and `uniform/` use no Minecraft class at all. That is deliberate: it lets them be
compiled and run on their own, outside the game, against a corpus of real packs, which is how a
translation regression is caught in seconds instead of in a play session. Keep the property. A
Minecraft import in one of those three packages costs more than it looks.

`render/` has no equivalent and cannot have one, since it exists only inside a frame. A change
there is argued from the code and from the log it produces, and it is worth saying which of the two
a claim rests on rather than leaving it implied.

What each check covers, and what a clone cannot run at all because shader packs are not
redistributable, is in [developing](docs/developing.md).

## Building

```
gradlew.bat build
```

The JDK it wants is pinned in `gradle.properties`, along with the Minecraft and loader versions.
The first build decompiles Minecraft and takes a couple of minutes. After that it is a few seconds.

Three jars land in `build/libs`, named for the version in `gradle.properties` and the Minecraft
version beside it. The first is the one a release ships and runs on either loader; the other two are
the slices it is merged from, one per loader, kept beside it for whoever wants only theirs:

```
build/libs/vitrail-<version>+mc<minecraft>.jar
build/libs/vitrail-<loader>-<version>+mc<minecraft>.jar
```

`gradlew.bat :neoforge:build` builds the NeoForge slice alone, into that module's own
`neoforge/build/libs`, and `:fabric:build` the other; only the full build refreshes `build/libs`.

To run the mod in a development client instead of installing it:

```
gradlew.bat :neoforge:runClient
```

## What the build refuses

`gradlew build` is also the check, and it fails on warnings rather than printing them. What it
holds:

- compiler warnings, minus four categories: deprecation, annotation processing, the one that only
  ever reports annotations missing from a dependency's own jar, and the one asking for a
  `serialVersionUID` on exceptions nothing serialises
- javadoc linting as errors, everything but the missing comments, so a broken reference, a
  malformed tag or malformed HTML fails the build
- Error Prone, contributing the checks it rates as errors, plus two of its warnings promoted to
  join them, `StringSplitter` and `OperatorPrecedence`
- `checkText`, which covers the two things no compiler sees: a byte order mark, which PowerShell
  writes unless told not to, and typographic punctuation

`gradlew build -PlintReport` prints the remaining Error Prone warnings and every compiler warning
with them, since it drops `-Werror`. A run under it is a listing rather than a check, and the build
says so on the way past. What it cannot let through is anything javac calls an error, which is the
javadoc lint and the two promotions.

The vendored stareval sources under `uniform/expr/kroppeb/` are left out of the javadoc lint and out
of the static analyser, promotions included, so that borrowed code stays as its author wrote it.
Nothing guards that package, so a change made there is worth reading twice.

Why each of those gates exists, what the two promotions are really about and what none of them
covers is in [developing](docs/developing.md). Run the build before pushing rather than after:
`main` staying buildable is a promise kept by whoever pushes.
