<!--
Merge with "Rebase and merge" and with nothing else. The other two buttons write a commit this
history does not carry: "Create a merge commit" forks the tree, "Squash and merge" throws away the
per-commit reasoning the bodies hold. If the rebase button is greyed out, this branch is behind
`dev`; rebase it locally and force-push rather than merging `dev` into it.
-->

## What changes

<!-- One paragraph. What the branch does to the engine, not how the diff is arranged. -->

## How it differs from the reference, and for what obstacle

<!--
"It does not" is the answer most of the time, and it is worth writing rather than leaving blank.
A divergence is only a divergence when it carries all three of these, and it is a workaround
either way rather than a preference:

- what Iris does, `file:line` in its own tree;
- what makes that impossible or wrong here, `file:line` in this one;
- what it costs the image.
-->

## What proves it

<!--
Say which of these the claim rests on. An unticked line is not a failure, it is a scope.

- `gradlew build` green.
- The out-of-game harness, and which corpus it ran over.
- In the game: which pack, which place, and what was looked at. Say if a pack setting had to be
  forced to make the pass visible at all, because two packs out of two ship an effect switched off.
-->

## What it leaves owing

<!-- Known gaps, families served by half, anything a later branch has to finish. Or "nothing". -->

---

- [ ] Rebased onto `dev`, so the merge is a fast-forward.
- [ ] `gradlew build` green locally, after the rebase and not before it.
- [ ] `CHANGELOG.md` carries an entry under `Unreleased`, or this changes nothing a player sees.
- [ ] Every place that states a fact this branch changed now states the new one. A fact is cited
      in more places than it lives in: javadoc, `docs/`, `README.md`, and the lines the engine
      prints at load.
