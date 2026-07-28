(ns syncopate.test-db
  "Test-connection factory shared by the deterministic and generative suites.

  The same tests run against two Datalevin topologies, selected by the JVM
  property `syncopate.test.mode`:

  - **embedded** (default): a fresh local LMDB directory per test.
  - **remote** (`=remote`): a fresh database per test on a REAL, separate
    datalevin server — the tests are a pure client (`dtlv://…`); they never
    start a server in this JVM (see README: `clojure -M:server`). The base URI
    defaults to localhost:8898 and is overridable with
    `syncopate.test.server-uri` (e.g. a service container)."
  (:require [datalevin.core :as d]))

(defn remote?
  "Are we running the suite against a client/server datalevin?"
  []
  (= "remote" (System/getProperty "syncopate.test.mode")))

(defn remote-base
  "Base `dtlv://` URI of the server the remote suite talks to (no db name)."
  []
  (or (System/getProperty "syncopate.test.server-uri")
      "dtlv://datalevin:datalevin@localhost:8898"))

(defn assert-server!
  "In remote mode, fail fast with a helpful message if no server is reachable."
  []
  (when (remote?)
    (try
      (d/close (d/get-conn (str (remote-base) "/t" (random-uuid))))
      (catch Exception e
        (throw (ex-info
                (str "Remote test mode, but no datalevin server reachable at "
                     (remote-base) ". Start one first: `clojure -M:server` "
                     "(or set -Dsyncopate.test.server-uri=dtlv://…).")
                {:base (remote-base)} e))))))

(defn fresh-conn
  "A fresh, isolated Datalevin connection for one test — a new embedded temp
  directory, or a new database on the remote server (auto-created per name)."
  []
  (if (remote?)
    (d/get-conn (str (remote-base) "/t" (random-uuid)))
    (d/get-conn (str "/tmp/syncopate-test-" (random-uuid)) {})))

(defn gen-count
  "Iteration count for a conn-hitting generative spec: full embedded, reduced
  (~1/5) remote, where every iteration is a round-trip to the server."
  [n]
  (if (remote?) (max 1 (quot n 5)) n))
