# com.github.crinklywrappr/syncopate

[![CI](https://github.com/crinklywrappr/syncopate/actions/workflows/ci.yml/badge.svg)](https://github.com/crinklywrappr/syncopate/actions/workflows/ci.yml)
[![Clojars Project](https://img.shields.io/clojars/v/com.github.crinklywrappr/syncopate.svg)](https://clojars.org/com.github.crinklywrappr/syncopate)

A claude-quality [ragtime](https://github.com/weavejester/ragtime) adaptor
for [Datalevin](https://github.com/juji-io/datalevin).

Syncopate lets you version and evolve a Datalevin schema (and the data around it)
with ragtime's battle-tested migrate/rollback machinery — while fixing the sharp
edges that the SQL adaptors and hand-rolled Datalevin adaptors trip over.

## Why another adaptor

It began as a toy adaptor that stored migration records as datoms, sorted history
by wall-clock, and swallowed every error. That works in a demo and fails in
production. Syncopate is a from-scratch redesign informed by the two blessed
ragtime adaptors (`ragtime.jdbc`, `ragtime.next-jdbc`), the recurring complaints
in ragtime's issue tracker, and the ideas in
[datalevin-surge](https://github.com/aldebogdanov/datalevin-surge):

- **Bookkeeping stays out of your data.** Applied-migration state lives in a
  dedicated key-value DBI on the *same* LMDB environment your connection already
  holds — never as datoms. It's invisible to `d/schema` and Datalog queries, and
  works under `:closed-schema? true`. (Datalevin refuses a second LMDB connection
  to the same directory, so Syncopate reuses the connection's own environment.)
- **Migrations are atomic** — each direction runs inside `d/with-transaction` by
  default, with a per-migration `:transaction? false` escape for operations that
  can't (ragtime issues #107 / #119).
- **Errors are actionable.** Failures are re-thrown as `ex-info` carrying the
  migration id, direction, phase and offending step — never silently dropped
  (ragtime issues #130 / #146).
- **Observability built in** — `pending` and `status` tell you what will run
  before you run it (ragtime issues #85 / #127).
- **Migrations are code, done right.** Data transforms are referenced by
  fully-qualified symbol and resolved to a real var at run time — no quoting, any
  namespace, editor-navigable (improving on surge's quoted-form restriction, and
  answering ragtime issue #131).

## Migration format

Migrations are **EDN-first**. Put one file per migration under a directory or
classpath prefix; the id defaults to the filename (`0001-add-users`).

A migration spec is a map:

```clojure
{:id           "0002-split-names"   ; optional; defaults to the filename
 :up           <step | [step ...]>
 :down         <step | [step ...]>  ; optional if :up is purely additive
 :transaction? true                 ; optional, default true
 :irreversible? false}              ; optional
```

A **step** is one of:

| Step                          | Meaning                                            |
|-------------------------------|----------------------------------------------------|
| `{:schema {attr def ...}}`    | add/update attributes (via `update-schema`)        |
| `{:schema/remove [attr ...]}` | retract every datom of the attrs, then drop them   |
| `{:tx [tx-data ...]}`         | raw `transact!` data                               |
| `a.namespace/fn-symbol`       | resolved to `(fn [conn] ...)` and called           |
| an actual `(fn [conn] ...)`   | only in `.clj` migration files                     |

### Sugar for elision

- `:up`/`:down` may be a single step instead of a vector.
- **Omit `:down`** and Syncopate derives it automatically — *iff* every `:up`
  step is a purely additive schema delta (adding attrs inverts to removing them).
  If `:up` contains a removal, a `:tx`, or a function, supply `:down` explicitly
  or set `:irreversible? true`; otherwise you get a clear build-time error.

### Examples

Purely additive — `:down` is derived (`resources/migrations/0001-add-users.edn`):

```clojure
{:up {:schema {:user/id   {:db/valueType :db.type/long
                           :db/unique    :db.unique/identity}
               :user/name {:db/valueType :db.type/string}}}}
```

Schema change plus a data transform referenced by symbol
(`resources/migrations/0002-split-names.edn`):

```clojure
{:up   [{:schema {:user/given-name  {:db/valueType :db.type/string}
                  :user/family-name {:db/valueType :db.type/string}}}
        my-app.migrations/split-user-names
        {:schema/remove [:user/name]}]
 :down [{:schema {:user/name {:db/valueType :db.type/string}}}
        my-app.migrations/join-user-names
        {:schema/remove [:user/given-name :user/family-name]}]}
```

A `.clj` migration, whose steps may be real functions
(`resources/migrations/0003-seed-departments.clj`):

```clojure
{:up   [{:schema {:dept/name {:db/valueType :db.type/string
                              :db/unique    :db.unique/identity}}}
        (fn [conn]
          (datalevin.core/transact! conn [{:dept/name "Engineering"}]))]
 :down [(fn [conn] ...)
        {:schema/remove [:dept/name]}]}
```

## Usage

```clojure
(require '[datalevin.core :as d]
         '[ragtime.core :as ragtime]
         '[syncopate.core :as syncopate])

(def conn  (d/get-conn "/path/to/db"))
(def store (syncopate/store conn))                 ; the ragtime DataStore
(def migs  (syncopate/load-resources "migrations"))

;; What's pending?
(syncopate/status store migs)
;; => {:applied [] :pending ["0001-add-users" "0002-split-names" ...]}

;; Apply everything outstanding, then roll the last one back:
(ragtime/migrate-all store {} migs)
(ragtime/rollback-last store {})
```

### Atomic helpers (recommended)

`ragtime.core/migrate` runs the migration body and records the applied-id as two
separate transactions. Syncopate's own helpers fold both writes into a single
`d/with-transaction`, so the body and its bookkeeping commit — or roll back — as
one unit (a mid-migration failure leaves *neither* the schema/data change nor the
applied-id behind):

```clojure
(syncopate/migrate! store migration)        ; atomic :up + record
(syncopate/rollback! store migration)       ; atomic :down + un-record
(syncopate/migrate-all! store migs)         ; apply all pending, each atomically (idempotent)
(syncopate/rollback-last! store migs)       ; atomically roll back the most recent
```

This is possible because the applied-id lives in a KV DBI on the *same* LMDB
environment as the data. It applies to transactional migrations (the default);
`:transaction? false` migrations fall back to the two-step path.

`syncopate/store` options:

| Option      | Default                     | Meaning                              |
|-------------|-----------------------------|--------------------------------------|
| `:dbi-name` | `"__syncopate_migrations"`  | KV DBI holding applied-migration state |

## Logging

Syncopate logs through [trove](https://github.com/taoensso/trove), a tiny facade
that lets a *library* emit rich logs without forcing a backend on you. **Calls are
noop until you install a backend**, so nothing is printed by default. In your app,
wire trove to whatever you already use:

```clojure
(require '[taoensso.trove :as trove]
         '[taoensso.trove.console])           ; or .tools-logging / .slf4j / .telemere / .timbre / .mulog

;; each integration ns exposes get-log-fn:
(trove/set-log-fn! (taoensso.trove.console/get-log-fn))
;; (trove/set-log-fn! nil)  ; noop / silence
```

See trove's docs for the full list of backend integrations and their options.

Every event has a stable `:id`, a human `:msg`, and structured `:data`
(migration id, direction, step counts, elapsed ms). Levels:

| Level  | Events                                                                                   |
|--------|------------------------------------------------------------------------------------------|
| info   | `:syncopate/migrate-all`, `:migrate-all-done`, `:migrated`, `:rolled-back`, `:rollback-last`, `:nothing-pending`, `:nothing-applied`, `:loaded` |
| warn   | `:syncopate/no-migrations` (nothing found at the given path/prefix)                       |
| error  | `:syncopate/migration-failed` (carries the causing throwable and the failing step)       |
| debug  | `:syncopate/migrating`, `:rolling-back`, `:step`, `:schema-delta`, `:schema-retract`, `:recorded`, `:unrecorded`, `:auto-down` |

Filtering by level/namespace/id is the backend's job (e.g. via tools.logging/slf4j
config). Building `:data` is deferred, so disabled log levels cost effectively nothing.

## Testing

```
clojure -T:build test
```

The suite runs against a temporary Datalevin database and covers loading/ordering,
auto-`:down` derivation, the reversibility guard, a full migrate/rollback
round-trip (schema *and* data, including a symbol-referenced transform and a
`.clj` migration), applied-id ordering, the no-schema-pollution invariant,
error-context propagation, the atomic helpers, the key atomic-rollback
guarantee (a failing transactional `:up` leaves neither schema change nor
applied-id behind), and the logging events (captured via a test trove backend).

## Notes & limitations

- Prefer the atomic helpers (`migrate!`/`rollback!`/`migrate-all!`/
  `rollback-last!`) — they commit the migration body and its applied-id in one
  transaction. The two-transaction seam (body committed, applied-id recorded
  after) remains only for `ragtime.core/migrate` and for `:transaction? false`
  migrations, which have no transaction to fold into; keep those idempotent-friendly.
- Run migrations from a **single, serial writer** — the normal deploy/startup
  path. Syncopate does not coordinate concurrent migration runs, so applying
  migrations from two processes (or threads) against the same store at the same
  time is unsupported and can corrupt the applied-migration bookkeeping.
- Auto-derived `:down` only covers additive schema deltas; anything that removes
  attributes, transacts data, or runs a function needs an explicit `:down` (the
  prior state can't be inferred).
- Reaching the connection's LMDB handle uses Datalevin internals
  (`(.lmdb (:store @conn))`); verified against Datalevin 1.0.0.
- Datalevin needs some JVM flags (they suppress warnings / grant native access):
  `--add-opens=java.base/java.nio=ALL-UNNAMED`,
  `--add-opens=java.base/sun.nio.ch=ALL-UNNAMED`, and
  `--enable-native-access=ALL-UNNAMED`; on JDK 24+ also
  `--sun-misc-unsafe-memory-access=allow`. The test task sets these for you; add
  them to your app/REPL JVM opts when embedding Syncopate.

## License

Copyright © 2026 crinklywrappr.

Distributed under the [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0)
