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

Datalevin (1.0.1+) **patches** an attribute's definition: `update-schema` with a
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

## Disabling an index: `:db/noindex`

`:db/noindex true` keeps an attribute out of the AVE index. Set it in a
`:schema/create` step for a new attribute. The attribute still stores and reads
back per entity, so pull and entity-bound queries work. A value-bound query on it
throws, because there is no AVE index to search. Unique and reference attributes
cannot opt out.

```clojure
{:schema/create {:document/body {:db/valueType :db.type/string :db/noindex true}}}
```

A `:schema/alter` step can toggle `:db/noindex`, but only before the attribute has
data. Once data exist, Datalevin rejects the change. To index an attribute that
already has data, use `index-attr` instead.

### Re-enabling the index with `index-attr`

Call Datalevin's `index-attr` from a function step. It backfills the existing
values and clears `:db/noindex` from the schema. It runs inside the migration's
transaction, so it folds atomically like any other step.

```clojure
;; resources/migrations/0007-index-body.edn
{:up [my.app.migrations/index-document-body]   ; resolves to a (fn [conn] ...)
 :irreversible? true}

;; my/app/migrations.clj
(defn index-document-body [conn]
  (datalevin.core/index-attr conn :document/body))
```

Mark the migration `:irreversible? true`, or supply a real `:down`. A rollback
cannot simply set `:db/noindex` back on, because Datalevin rejects a `:db/noindex`
change once the attribute has data. Undoing an index means re-creating the
attribute, which a `:down` must do explicitly.

## Value types that need special handling

Three value types or options cannot be created and populated by an ordinary
transactional migration. This is a Datalevin constraint across the supported
range, not something Syncopate can work around at the migration layer. Scalars,
references, and tuples are unaffected.

### `:db.type/idoc`: populate in a non-transactional migration

An idoc attribute builds its document index on the first write to it, and that
build fails inside a Datalevin transaction. The error reads `Cannot read field
"max_doc" because "index" is null`. Creating an empty idoc attribute in a
transactional migration works. Inserting idoc data in one does not.

Set `:transaction? false` on the migration that inserts idoc data. The body then
runs without `with-transaction`, so the index builds normally. Syncopate records
the applied id as a separate write for such a migration. That is the one case
where it cannot fold the body and its bookkeeping into one transaction.

```clojure
{:id "0010-add-documents"
 :transaction? false
 :up [{:schema/create {:document/body {:db/valueType :db.type/idoc}}}
      {:tx [{:document/body {:title "t" :text "..."}}]}]
 :down [{:schema/remove [:document/body]}]}
```

### `:db.type/vec` and `:db/fulltext`: enable them at connection open

A vector attribute needs a vector index, and a fulltext attribute needs a search
engine. Datalevin builds these only on a connection that opened with the feature.
A migration that adds such an attribute and then writes to it fails with a
missing-implementation error, in a transaction or not. `:db.type/vec` also needs
a `:dimensions` value, which a plain `:schema/create` does not supply.

Declare these attributes in the schema you pass to `get-conn`, or open the
connection with the matching option. Do not rely on a migration to make such an
attribute usable.

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
