# Contributing

## How releases work

Every merge to `master` is a release. The CI `ci` job deploys the JAR to Clojars
and pushes a git tag. The version is `1.0.<N>`. Here `N` is the rev-count: the
number of commits on `master`, from `git rev-list --count HEAD`. Each merged PR
is squashed to one commit, so each merge raises the rev-count by one.

The version of a release equals the rev-count of its own merge commit. So the
dated CHANGELOG heading for a version must live in that same commit. A later
commit that only edits the CHANGELOG is itself the next release. It can never
catch up.

## Release checklist

Before you merge a release PR, do these steps in the PR branch.

1. Read the current rev-count. Run `git rev-list --count master`.
2. Add one. That sum is the release version `1.0.<sum>`.
3. Rename the `## [Unreleased]` heading to the new version with today's date.
   Copy the heading format from the dated sections below it.
4. Add a fresh empty `## [Unreleased]` heading above it.
5. Make sure that every change in the PR has an entry under the new version.
6. Merge the PR. CI deploys `1.0.<sum>` and tags it.

## A release shipped under [Unreleased]

If a release shipped with its notes under `[Unreleased]`, the tag and the
published JAR are frozen that way, because Clojars is immutable. Do not try to
rewrite a shipped tag. Date that version's section in your next release PR. Add
the new version's section in the same commit.
