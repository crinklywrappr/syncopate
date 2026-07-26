# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]
### Added
- Structured logging via [trove](https://github.com/taoensso/trove): migration
  lifecycle, per-step, schema-delta, and load events with stable `:id`s, human
  messages, and structured `:data` (ids, direction, step counts, elapsed ms).
  Logging is noop until the application installs a trove backend, so no backend
  is imposed on users. See the "Logging" section of the README for the event
  catalog and how to wire a backend.

### Changed
- Documented the JVM flags Datalevin needs (`--add-opens` for `java.nio`/`sun.nio.ch`,
  `--enable-native-access`, and `--sun-misc-unsafe-memory-access=allow` on JDK 24+).
  The test task now sets these automatically (portable across JDK versions), so
  `clojure -T:build test` runs without native-access / Unsafe / SLF4J / kaocha-config
  warning noise.
