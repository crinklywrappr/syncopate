# Schema migrations: create, alter, remove

Syncopate models schema changes as three declarative operations. A step performs
**exactly one** operation — one schema op, or a `:tx` data step; a map naming more
than one (e.g. both `:schema/create` and `:schema/remove`, or `:tx` alongside a
schema op) is rejected with an error when the migration is built or run — it is
never silently reduced to one. The three schema operations split along **two
orthogonal axes**.

## The two axes

**Existence — does the attribute exist at all?**

| Op | Meaning | Auto-invertible? |
|----|---------|------------------|
| `:schema/create` | add **new** attributes | **yes** — `:down` drops them |
| `:schema/remove` | retract every datom of the attrs, then drop them | no — data is gone |

`:schema/create` and `:schema/remove` are strict opposites: one brings an
attribute into being, the other takes it out.

**Definition — change an existing attribute's property keys.**

| Op | Meaning | Auto-invertible? |
|----|---------|------------------|
| `:schema/alter` | add / change / clear a property on an **existing** attribute | no — prior definition is unknown |

`:schema/alter` keeps the attribute; it only changes its definition (its
`:db/*` property keys). It is closed under inversion: the `:down` for a
`:schema/alter` is always another `:schema/alter`.

Only `:schema/create` can be inverted automatically, so only a migration whose
`:up` is entirely `:schema/create` may omit `:down`. `:schema/alter`,
`:schema/remove`, `:tx` and function steps require an explicit `:down` (or
`:irreversible? true`).

## Declared intent — not inferred

`:schema/create` and `:schema/alter` apply **identically** at run time (both do
`update-schema conn <attr→def map>`). The difference is purely what you
*declare*, which decides how the migration rolls back. Syncopate never inspects
the live schema to guess whether an attribute already exists — the key you choose
*is* the intent. This keeps behaviour deterministic (a migration doesn't change
meaning based on the database it meets) and keeps crash-recovery re-runs safe (a
`:schema/create` that half-ran and re-runs just re-applies harmlessly, because
`update-schema` is idempotent).

The contract: if you use `:schema/create` for an attribute that already exists,
its auto-`:down` will **drop that attribute and delete its data** on rollback.
Use `:schema/create` only for genuinely new attributes; use `:schema/alter` to
touch an existing one.

## Writing a `:schema/alter` `:down`

Datalevin (1.1.0+) **patches** an attribute's definition: `update-schema` with a
partial map *merges* into the existing definition rather than replacing it. Two
consequences for rollbacks:

1. **Describe the inverse change, not the prior definition.** Re-stating the old
   definition does nothing to a property your `:up` added — the merge just
   re-asserts values that are already there. To undo `:up`, actively reverse each
   property it changed.

   ```clojure
   ;; up added an index; down turns it back off
   {:up   {:schema/alter {:user/email {:db/index true}}}
    :down {:schema/alter {:user/email {:db/index false}}}}
   ```

2. **A property key cannot be deleted, only set.** Patch has no "remove this key"
   operation: setting a property to `nil` (or `false`) stores that value; it does
   not erase the key. So an alter that *added* a property can be neutralised
   (`:db/index false`, `:db/doc nil`) but the definition won't become byte-for-byte
   identical to what it was before. Plan reversals around setting values, not
   removing keys.

## Upgrading from the old `:schema` step

Earlier versions used a single `:schema` step for both adding and updating
attributes. It was ambiguous, and its auto-`:down` always *dropped* the attrs —
silently destroying data when the step had actually modified an existing
attribute. `:schema` is now rejected with an error that tells you which
replacement to use and, for a purely-additive `:up`, prints the `:down` to
copy-paste:

- adding new attributes → replace `:schema` with `:schema/create`
- modifying existing attributes → replace `:schema` with `:schema/alter` and add
  an explicit `:down` (see above)
