# Contributing

Interactions here are covered by the [code of conduct](CODE_OF_CONDUCT.md), and
anything that looks exploitable goes through [SECURITY.md](SECURITY.md) rather
than the issue tracker.

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
dashes, saying what the branch does rather than which class it opens. The two long-lived branches
carry no prefix, being the only two that are not about one thing.

    feat/entity-color-from-overlay
    fix/hand-bob-frame
    ci/commit-and-branch-format

`release/0.5.0-beta` is the one shape that departs from it, and the version is the whole of the
name: such a branch carries the version bump and nothing else, so there is nothing else to say
about it.

Nothing in a name records who wrote the branch or when, and none of them carries an issue number
either. That second one is a choice rather than an absence, now that there are issues to number: the
commit that does the work names the issue it closes, and a name carrying the number would say the
same thing in the one place nothing reads it back from. Commits says where that line goes and why it
is not the request's body.

**The history is linear and carries no merge commit anywhere, which is not a preference.** A tree
that forks is a tree nobody reads once it is public, and this one is public. A topic branch is
rebased onto `dev` and enters by a pull request, merged with the rebase button. If that button
refuses, the rebase was not done, and the answer is to rebase rather than to merge.

**`dev` reaches `main` by a fast-forward and NOT by that button**, and the difference is the whole
of why `main` can be read as a prefix of `dev`. The rebase button always writes new commits: it
re-applies each one under a fresh identity, which is what it is for on a topic branch and what makes
it wrong here. `main` is already an ancestor of `dev`, so replaying `dev` onto it would give `main`
a second copy of every commit under a different hash, and the two branches would then hold the same
work under two histories. A pull request is still the right place to look at a release, for the
record and for the checks it runs; what merges it is the fast-forward, and the pull request closes
itself as merged once its commits are on `main`.

    git push origin origin/dev:main

**That pull request has a template of its own**, `.github/PULL_REQUEST_TEMPLATE/release.md`, opened
with `gh pr create --base main --head dev --template release.md`. It asks nothing the default one
asks: a release changes nothing by itself, everything in it having entered `dev` through a pull
request of its own. What it asks instead is the version in both the places it is written, and one
question no command can answer, whether the range about to be published carries a batch that changed
the picture and left no changelog line. That is not hypothetical.

**Nothing on GitHub can refuse the wrong press**, which is worth knowing rather than assuming: the
merge buttons a repository offers are a repository-wide setting, and the rebase button this forbids
here is the one a topic branch needs. So `prefix.yml` checks after the fact instead, on every push
to `main`, that `main` is still contained in `dev`, and fails loudly within a minute when it is not.
Recovering from it is a reset of `main` back onto `dev`, and it is cheap for exactly as long as
nothing has been built on top.

**Every batch enters that way, including one written by whoever owns the repository.** Folding a
branch in locally skips the one thing the pull request is for: `build.yml` runs on `pull_request`,
so a batch that goes in by hand is built only once it is already in `dev`, and a red build then
lands on the branch everything else is opened from.

### Rebase a topic branch as soon as `dev` moves under it

A branch left standing while a large change lands on `dev` is not merely behind, and the cost is
not the conflicts you are shown. Git follows a file that was moved or renamed, so a branch editing
a class that has since changed module rebases onto the new path without a word. What it does not
follow is code that moved *between* classes: a call the branch edited in one class, where `dev` has
since moved that work into another, comes back as a conflict whose two sides are about different
files, and the branch's half has to be written into its new home by hand.

**Read the whole of `git diff dev..HEAD` after a rebase, not only the hunks git marked.** The
conflicts it raises are the ones it could see; the ones that cost an afternoon are the two it could
not. Two sides may add the same helper under one name in different places, which is a compile error
and therefore cheap. Or a fact may change on one side while the other side's prose still describes
the old one, in a paragraph far from anything git touched: that compiles, reads well, and is
simply false. Both happen because a fact is cited in more places than it lives in.

### A pull request merges one way, and the repository enforces it

**"Rebase and merge", and neither of the other two buttons.** "Create a merge commit" forks a
history the section above says never forks. "Squash and merge" collapses a branch into one commit
and throws away the bodies, which is where the reasoning for each step lives; a branch is a
sequence of logical changes here rather than a unit of work, and the sequence is the part worth
keeping. A rebase button that refuses means the branch is behind `dev`: rebase it and force-push,
rather than merging `dev` into it.

None of that is left to memory, and it takes two settings because each one lets through what the
other stops. The repository allows the rebase merge alone, so the other two buttons do not exist;
and a ruleset requires a linear history on `main` and `dev`, which refuses a merge commit arriving
by a direct push rather than through a request. **The second does not imply the first**, and that
is the part worth knowing before changing either: a squash is linear, so the ruleset would take it
happily, and it is the button setting that rules it out. The same ruleset refuses the deletion of
either branch.

`.github/pull_request_template.md` is what a request opens with, and its four headings are the four
questions this repository answers before anything lands. Three of them are ordinary. The one that
is not is the second, "how it differs from the reference": packs are written against Iris, so a
difference in behaviour is a pack rendering wrongly however good the reason sounds, and the answer
is either "it does not" or the three parts a divergence owes. The final box is today's lesson
rather than hygiene: a fact is cited in more places than it lives in, so changing it in the one
place it lives leaves the rest standing and reading perfectly well.

A tag is `v` followed by whatever `mod_version` in `gradle.properties` holds, and that line
is where the version lives. Nothing derives one from the other: a human types the tag, and
the release workflow refuses it when the two disagree rather than publishing a jar named
after one and built from the other. The target Minecraft version is in the artifact name
and comes from the same file.

A tag is pushed on `main` and on nothing else, which is what keeps the sentence above true.

A version is three numbers, and after them either `-alpha`, or `-beta`, or nothing at all. Those
three are one version reached in order: `0.5.0-alpha`, then `0.5.0-beta`, then `0.5.0`. Nothing
follows the word, and a counter least of all, so `0.5.0-beta.1` is not a version here. What that
costs is worth saying, because it is the whole of the rule: there is no second beta of a version.
A beta that needs a fix is a new version and the patch number moves, `0.5.1-beta`, which is also
what a reader of the two numbers would have assumed. The hook refuses a release branch named
otherwise, and the release workflow refuses such a tag before it builds anything.

## Changelog

`CHANGELOG.md` carries an `Unreleased` section, and a change a player would notice is written into
it in the same commit that makes the change, not gathered from the log afterwards. Gathering
afterwards is how a subject line ends up standing in for an entry, and the two are not the same
thing: a subject says what the commit did to the tree, an entry says what the version does
differently to somebody running it. A refactor that changes nothing a player sees gets no entry.

Raising `mod_version` is what closes the section: `Unreleased` becomes that number, and the section
is what the release body and both stores are handed.

## Commits

One logical change per commit, and a subject in the form the wider ecosystem calls a conventional
commit:

    <type>(<scope>)!: what the commit does

That form is worth more here than it is in most repositories, because of how a branch lands. The
rebase button replays every subject verbatim into the public history instead of collapsing them
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
`feat(render): ` is fourteen of them. What used to fit in a subject running to a hundred columns
goes in the body now, which is where it reads better anyway. A body is for the reason, when the
reason is not in the diff, and a blank line separates it from the subject.

A `!` before the colon marks a change that breaks a pack or a configuration that used to work. What
breaks is written in the body, and there is no `BREAKING CHANGE:` footer: the changelog is written
by hand and the version typed by a human, so nothing here would read one. The `Closes` line below is
the single exception, and what reads it is GitHub rather than anything in this repository.

**An issue is closed from the COMMIT that closes it and never from the pull request**, on a line of
its own at the foot of the body:

    Closes #30

That is the shape of this repository rather than a taste. A closing keyword fires when the request
it is written in merges into the DEFAULT branch, which here is `main`, and every request here merges
into `dev`: written in a request's body it links the issue and then leaves it open for good. Written
on the commit it travels with the commit, and it lands on `main` the day `dev` does.

**Check the issue really closed once `dev` has landed on `main`, and close it by hand if it did
not.** This is the one rule in this file whose far end is a service rather than a script, so it is
the one that can quietly stop being true without anything here changing.

```
feat(render): read entityColor off the entity mesh overlay
fix(pack)!: refuse a customTexture path that leaves the pack
docs: correct five sentences against the source
build: raise the version to 0.5.0-beta
```

A commit that changes a mechanism and the paragraph describing it is one commit under the type of
the mechanism rather than two, since a changelog entry and a doc line belong with the change that
made them true. `docs` is for the batch that is documentation and nothing else.

None of this is retroactive, and what was written before the convention is left as it was. Rewriting
a public history to tidy the shape of its subjects would cost every reference anybody holds to it
and buy a uniformity nobody reads for.

The line falls at what LANDS, and not at what was written. A subject already in `dev` or `main` is
history and stays as it is, for the reason just given. A branch that was opened before this page and
has not landed yet is not history: it owes a rebase before it can enter at all, since `dev` has moved
under it, and a subject is amended in that same gesture. It conforms.

One branch escaped that, on the evening this was written. The branch carrying the settings screen
held fourteen subjects from before the convention, needed nothing else, and entered `dev` while the
check had never run on a request. So the log holds fourteen lines this page would refuse, in the days
just after it. They are left where they are, and this paragraph is what a reader finds instead of
concluding that the rule was ignored from the start.

### Both ends of the rule are one file

`.githooks/commit-msg` refuses a subject or a branch name that is not in the form above. Install it
once per clone, worktrees sharing the same config:

    git config core.hooksPath .githooks

`.github/workflows/commits.yml` runs that same file over every commit of a pull request, for
whoever never ran that command and for anything arriving from a fork. It is deliberately the same
file: a workflow rewriting the same expression would be a second home for the rule, and the two
would agree only until one of them changed.

Catch it at the commit rather than at the request. A subject is amended in one gesture while it is
still the last one, and rebuilt by hand once nine commits stand on top of it.

The hook reads the name of the branch you are standing on, so a branch opened before this page
refuses its next commit until it is renamed, wherever that commit was going. Renaming is one command
and there is nothing to reinstall: `git branch -m <type>/what-it-does`, and the request it was
already pushed to is replaced by one on the new name, GitHub having no way to move a request from
one branch to another. `main` and `dev` are exempt from the name, and so is a detached HEAD, which
is why a rebase replays a branch without ever asking.

## Labels

Everything carries one, requests and issues alike, and neither set is decoration: a list of a dozen
open things has to say what each one is before any of them is opened.

**A pull request carries the type of its branch, and only that.** The nine are the ones in the table
above, so the label is not a second opinion about the change: it is the word already in the branch
name and at the head of every subject the branch carries. `docs/correct-a-page` is labelled `docs`,
and a branch whose commits are mostly `fix` with a `refactor` among them is labelled `fix`, the same
way its name was settled.

**An issue carries what it is about instead**, being a report rather than a change:

| label | what it marks |
| --- | --- |
| `known limitation` | a gap this engine already knows about, opened here rather than waited for |
| `pack compatibility` | a pack does not draw as it should |
| `upstream` | the cause is in another project or in the backend, and nothing here closes it |

beside GitHub's own `bug`, `enhancement` and `question`, which the issue forms set themselves. The
two sets do not overlap and are not meant to: a type says what a change does, and these say what a
report is about.

**A request that closes an issue says so in its body**, `Closes #31` on a line of its own, so that
the issue goes with the merge rather than being noticed weeks later. That is the whole reason the
known limitations are open as issues rather than living in a file somewhere: a branch can point at
one, and a reader can see that somebody is on it.

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

## Verifying a change

Where a change lands decides how it can be checked, and the split is worth knowing before
writing anything.

`pack/`, `glsl/` and `uniform/` use no Minecraft class at all. That is deliberate: it lets
them be compiled and run on their own, outside the game, against a corpus of real packs, which is
how a translation regression is caught in seconds instead of in a play session. Keep the property. A
Minecraft import in one of those three packages costs more than it looks.

There is exactly one exception, and it is worth knowing before you try the standalone compile: a
single file reaches out of the three trees for the logger, and the out-of-game build drops that file
rather than dragging the rest in behind it.

`render/` has no equivalent and cannot have one: it exists only inside a frame. A change
there is argued from the code and from the log it produces, and it is worth saying which
of the two a claim rests on rather than leaving it implied.

One rule is not negotiable, because a pack is downloaded content: a path a pack writes
never leaves the pack. `customTexture.x = ../../../secret.png` served an arbitrary PNG of
the disk to a shader until c1c50c0 confined it, the way an include was already confined.
Anything new that turns text from a pack into a file on disk goes through the same
confinement.

## Building

```
gradlew.bat build
```

The JDK it wants is pinned in `gradle.properties`, along with the Minecraft and loader versions.
Artifacts land in `build/libs`.

## What the build refuses

`gradlew build` is also the check, and it fails on warnings rather than printing them. Not on
all of them: four categories are off. Deprecation, because Minecraft and NeoForge deprecate faster
than a mod can follow and the noise would bury everything else; annotation processing, which reports
which processor claimed what and is a property of how the build is wired; the category that only
ever reports annotations missing from a dependency's own jar; and the one that asks for a
serialVersionUID on exceptions nothing serialises.

Javadoc is linted for everything but missing comments, so broken references, malformed tags,
malformed HTML and accessibility all fail the build. That matters more here than it would elsewhere.
The javadoc carries the design, so a reference that no longer resolves is a piece of the design lost,
and nothing says so until someone goes looking.

Error Prone runs alongside javac and contributes the checks it rates as errors, the part of its
catalogue meant to be a bug rather than a preference. Two of its warnings are promoted to join them,
`StringSplitter` and `OperatorPrecedence`; the rest are worth reading and not worth blocking on, so
`gradlew build -PlintReport` prints them and lets the build through.

That flag lets those remaining warnings through and every compiler warning with them, since it drops
`-Werror`. A run under it is a listing, not a check, and the build says so on the way past. What it
cannot let through is anything javac calls an error, which is the javadoc lint and the two
promotions.

The first of the two is why every `split` here passes a limit: given a pattern and nothing else the
call cannot say which of two readings of an empty field it wants. Which reading each of the two
limits is, why either check was promoted, and what neither of them covers, are in
[developing](docs/developing.md).

`checkText` covers the two things no compiler sees: a byte order mark, which PowerShell writes
unless told not to, and typographic punctuation. Why each of those is a gate, and what the second
one really catches, is in [developing](docs/developing.md).

The vendored stareval sources under `uniform/expr/kroppeb/` are left out of the javadoc lint and
out of the static analyser, promotions included. The code is its author's, and bending borrowed
code to this project's taste only makes the next comparison with upstream harder to read. Nothing
guards that package, so a change made there is worth reading twice.

Run it before pushing rather than after. `main` staying buildable is a promise kept by whoever
pushes.

## Publishing

Pushing a tag is what publishes. Rewrite the pack table at the head of
[docs/compatibility.md](docs/compatibility.md) against whatever has been seen since the
last one, bump `mod_version` in `gradle.properties`, land those commits on `main` the way
every other commit lands, push `main`, then tag that commit `v` plus the same version and
push the tag. The order matters: a tag push carries its own objects and nothing else, so
tagging before the branch is pushed publishes a commit that is on no branch.

`.github/workflows/release.yml` takes it from there. It runs the same `gradlew build`
anyone runs, then creates a GitHub release under the tag's own name and attaches the jar
that build produced, so what is downloaded is what this history compiles rather than what
a machine had lying in `build/libs`.

The same job then mirrors it to CurseForge, which is why there is no second workflow: a
release this one creates is authored by the token it runs under, and such a release starts
no further workflow, so a file listening for it would never wake. The mirror needs two
secrets set in the repository settings, `CURSEFORGE_ID` and `CURSEFORGE_TOKEN`, and without
either it says so and ends green rather than failing a release over it. The id is not a
secret in any real sense (it is on the project's own page), and it lives there only so that
both halves of the same configuration are found in the same place.

The release body is what CurseForge is given as the changelog, read back at the moment the
run reaches it. A body written by hand after the tag went out therefore reaches CurseForge
by running the workflow again on that tag, from the Actions tab, and by no other road.

**Run it once for a given tag.** A second run rebuilds the same tag, and the archives here
are built to be reproducible, so it offers CurseForge a file whose hash it already holds;
CurseForge rejects a duplicate at processing while the run itself still ends green, which
means a changelog corrected on the second attempt quietly never lands. On the GitHub side
the same run replaces the asset rather than adding one, which resets what the old asset had
counted.

CurseForge holds a fresh file under review before it is public, and an app looking for updates
does not see a file that is not approved yet. An instance reading the list inside that window
keeps the release before this one as the newest it knows and offers it as an update, which is a
downgrade: what it compares is which file is installed against which file is newest, and no
version number anywhere. Nothing is wrong with the release and there is nothing to repair, the
instance settling by itself the next time it looks. It is whoever published who meets this,
having installed the jar minutes after the tag while everybody else arrives once the review is
over.

A version carrying `-alpha` or `-beta` is published as a pre-release. One carrying neither is
published as a release.

The release body is this version's entry in `CHANGELOG.md`, and the run refuses the tag when that
entry is missing rather than publishing an empty one. It was GitHub's own generated notes until
`06d5f53`, and they came out empty: those notes list merged pull requests and new contributors,
and a history that is rebased and fast-forwarded has neither to show, so the body arrived as a
comparison link under a heading. A body corrected by hand on the release page afterwards reaches
both stores by dispatching the workflow again on the same tag, since what the stores are handed is
read back off the page rather than rebuilt.

GitHub and CurseForge are the two places a build goes, and nothing else is automated.
