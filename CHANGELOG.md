# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]
### Changed
- **BREAKING: the `:schema` step is replaced by `:schema/create` and
  `:schema/alter`.** The old `:schema` step was ambiguous — it could add new
  attributes or modify existing ones — and Syncopate auto-derived a `:down` that
  *dropped* the attributes and retracted their data, silently destroying data
  when the step had actually modified an existing attribute. Datalevin 1.0.1+ makes
  `update-schema` patch (merge) existing attribute definitions, turning attribute
  updates into a first-class operation and making that footgun reachable in normal
  use. Migrations now declare intent: `:schema/create` (new attributes,
  auto-invertible) vs `:schema/alter` (modify existing attributes, requires an
  explicit `:down`); `:schema/remove` is unchanged. Bare `:schema` is rejected at
  `->migration` with an actionable error that forks create-vs-alter and, for a
  purely-additive `:up`, prints the `:down` to copy-paste. Each step performs
  exactly one operation (a `:tx` or a single schema op); a map naming more than
  one is rejected with a clear error rather than silently keeping one and dropping
  the rest. See `doc/schema-migrations.md`.
- **Datalevin dependency now pinned to the supported floor, 1.0.1** (was a hard
  pin at 1.1.0, previously 1.0.0). A plain `:mvn/version` is a soft/minimum
  constraint, so pinning the floor — rather than the newest — lets consumers pick
  their own Datalevin (top-level dep wins in tools.deps; nearest-wins in
  Maven/Leiningen) without Syncopate forcing an upgrade. 1.0.1 is the floor
  because `update-schema`'s patch semantics (required by `:schema/alter`) land in
  1.0.1; 1.0.0 replaces instead. A `:dl-*` alias matrix in `deps.edn` and CI run
  the embedded suite against 1.0.1, 1.0.2, and 1.1.0, and the client/server suite
  (matched server+client) against the floor and newest (1.0.1, 1.1.0).
- `store`'s embedded KV handle is now obtained via Datalevin's supported
  `datalog-kv` rather than reaching into `datalevin.storage.Store` internals.

### Added
- **Client/server (`dtlv://`) support.** `store` now works over a networked
  Datalevin connection as well as an embedded one, keeping the core promise:
  applied-migration state still lives in a dedicated KV DBI on the *same*
  environment the connection holds (a KV client to the same server database),
  never as datoms. New `close!` releases that client (no-op for embedded).
  Client/server migrations use the two-transaction seam (the KV client can't join
  the remote datalog transaction); embedded keeps the single-transaction fold.

### Fixed
- Applied-migration ordering (and therefore `rollback-last!`) now tracks true
  application order via a persisted monotonic `:seq`, instead of `:applied-at`
  wall-clock time. Previously, migrations recorded within the same millisecond and
  applied out of id order could be returned id-sorted, so `rollback-last!` might
  roll back the wrong one.

### Added
- Structured logging via [trove](https://github.com/taoensso/trove): migration
  lifecycle, per-step, schema-delta, and load events with stable `:id`s, human
  messages, and structured `:data` (ids, direction, step counts, elapsed ms).
  Logging is noop until the application installs a trove backend, so no backend
  is imposed on users. See the "Logging" section of the README for the event
  catalog and how to wire a backend.

### Changed
- Hardened the `syncopate.schema/schema-delta?` and `additive?` predicates: a
  schema delta's `:schema` must be a map of attribute keyword → definition map, and
  `:schema/remove` must be a collection of attribute keywords. Malformed shapes
  (e.g. `{:schema 42}`) are now rejected up front — surfacing as a clear
  "unrecognised step" error rather than a cryptic Datalevin failure. This tightens
  the (public) predicate contract, which previously accepted any `:schema` value.
- Documented the JVM flags Datalevin needs (`--add-opens` for `java.nio`/`sun.nio.ch`,
  `--enable-native-access`, and `--sun-misc-unsafe-memory-access=allow` on JDK 24+).
  The test task now sets these automatically (portable across JDK versions), so
  `clojure -T:build test` runs without native-access / Unsafe / SLF4J / kaocha-config
  warning noise.
