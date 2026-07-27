# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]
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
