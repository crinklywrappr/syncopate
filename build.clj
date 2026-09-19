(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]
            [rewrite-clj.zip :as z]))

(def lib 'com.github.crinklywrappr/syncopate)
;; TODO: sync README coordinates with this value
(def version (format "1.0.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")

(defn- jdk-major
  "Major version of the JVM running this build (== the java that spawns tests)."
  []
  (-> (System/getProperty "java.version") (str/split #"[.-]") first parse-long))

(defn- test-java-opts
  "JVM flags datalevin needs, kept portable across JDKs. `--add-opens` (java.nio)
  and `--enable-native-access` (javacpp's System::load) are valid on JDK 16+;
  `--sun-misc-unsafe-memory-access` exists only on JDK 24+ (added conditionally so
  older JDKs don't choke on an unrecognised option). `slf4j.internal.verbosity`
  quiets SLF4J's startup notice."
  []
  (cond-> ["--add-opens=java.base/java.nio=ALL-UNNAMED"
           "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
           "--enable-native-access=ALL-UNNAMED"
           "-Dslf4j.internal.verbosity=WARN"]
    (>= (jdk-major) 24) (conj "--sun-misc-unsafe-memory-access=allow")))

(defn- run-kaocha
  "Run kaocha in a spawned JVM (with `java-opts`) with extra kaocha `args`.
  When `dl` (a datalevin version string) is given, it's injected as an `:extra`
  dep on the spawned JVM's basis — merged last, so it overrides the floor pinned
  in deps.edn. The basis, not the outer -T:build JVM, is what tests run against,
  which is why the override has to be layered in here."
  [args java-opts dl]
  (let [basis (b/create-basis
               (cond-> {:aliases [:test]}
                 dl (assoc :extra {:deps {'datalevin/datalevin {:mvn/version dl}}})))
        cmd   (b/java-command
               {:basis     basis
                :main      'clojure.main
                :java-opts java-opts
                :main-args (into ["-m" "kaocha.runner"] args)})
        {:keys [exit]} (b/process cmd)]
    (when-not (zero? exit) (throw (ex-info "Tests failed" {})))))

(defn test
  "Run the tests. Embedded by default; `:remote true` runs the suite against a
  client/server datalevin — a SEPARATE server must already be running (see
  README: `clojure -M:server`), or point elsewhere with `:server-uri
  \"dtlv://…\"`. Remote runs skip the `^:embedded`-tagged atomicity test. Extra
  kaocha args via `:args`. `:dl \"1.0.2\"` runs against a specific supported
  datalevin version (injected as an :extra dep; default = the floor pinned in
  deps.edn — see the `:ci` alias there for the tested range). `ci` forwards these
  through, and `test-all` calls this once per version in the matrix."
  [{:keys [args remote server-uri dl] :or {args []} :as opts}]
  (run-kaocha
   (cond-> args remote (into ["--skip-meta" ":embedded"]))
   (cond-> (test-java-opts)
     remote     (conj "-Dsyncopate.test.mode=remote")
     server-uri (conj (str "-Dsyncopate.test.server-uri=" server-uri)))
   dl)
  opts)

(defn coverage
  "Run the tests with cloverage coverage reporting (target/coverage/index.html)."
  [{:keys [args] :or {args []} :as opts}]
  (run-kaocha (into ["--plugin" "cloverage"] args) (test-java-opts) nil)
  opts)

;; ── datalevin version matrix ────────────────────────────────────────────────
;; deps.edn's `:ci {:datalevin/versions [...]}` alias is the single source of
;; truth for the supported range. These tasks read it (CI matrix, local test-all)
;; and maintain it (bump-matrix, driven by the datalevin-watch workflow).

(defn- read-deps
  "The project deps.edn as a data structure."
  []
  (-> "deps.edn" slurp edn/read-string))

(defn- floor-version
  "The pinned floor (lowest supported) datalevin version, from :deps."
  [deps]
  (get-in deps [:deps 'datalevin/datalevin :mvn/version]))

(defn- matrix-versions
  "The supported datalevin versions (the source-of-truth vector)."
  [deps]
  (get-in deps [:aliases :ci :datalevin/versions]))

(defn- parse-version
  "\"1.2.3\" -> [1 2 3] for comparison (non-numeric/pre-release parts drop out)."
  [v]
  (->> (str/split (first (str/split v #"-")) #"\.")
       (keep parse-long)
       vec))

(defn- version-sort
  "Ascending semantic sort of version strings."
  [vs]
  (sort-by parse-version vs))

(defn- json-array
  "Print `vs` as a one-line JSON array (and nothing else) — for `$GITHUB_OUTPUT`
  consumption via `fromJSON`. A vector of strings is already valid JSON."
  [vs]
  (println (json/write-str (vec vs))))

(defn matrix
  "Print the datalevin test matrix as a JSON array for GitHub Actions to consume
  via fromJSON. `:which :embedded` (default) prints every supported version;
  `:which :remote` prints just [min max] (the client/server suite is slow, so it
  runs only the floor and newest). Prints ONLY the array to stdout."
  [{:keys [which] :or {which :embedded}}]
  (let [vs (version-sort (matrix-versions (read-deps)))]
    (case which
      :embedded (json-array vs)
      :remote   (json-array (distinct [(first vs) (last vs)])))))

(defn test-all
  "Run the embedded suite against every supported datalevin version in turn
  (the `:ci` matrix). Fails fast on the first red version. Remote runs are not
  looped (each needs a matching server); use `test :remote true :dl \"…\"`."
  [opts]
  (doseq [v (version-sort (matrix-versions (read-deps)))]
    (println (str "\n══ datalevin " v " (embedded) ══"))
    (test (assoc opts :dl v)))
  opts)

(def ^:private clojars-url
  "https://clojars.org/api/artifacts/datalevin/datalevin")

(defn- fetch-clojars
  "Latest datalevin release info from Clojars: {:latest \"x\" :recent [\"…\"]}
  (recent newest-first)."
  []
  (let [{:strs [latest_release recent_versions]} (json/read-str (slurp clojars-url))]
    {:latest latest_release
     :recent (mapv #(get % "version") recent_versions)}))

(defn- desired-matrix
  "Compute the target matrix + action from the floor, current matrix, newest
  release, and recent releases. Growth = floor + newest-3 (same major as the
  floor, no pre-releases). A new *major* is flagged, not applied."
  [floor current latest recent]
  (let [fmaj (first (parse-version floor))]
    (if (> (first (parse-version latest)) fmaj)
      {:action :major :version latest}
      (let [floor-v    (parse-version floor)
            candidates (->> recent
                            (remove #(str/includes? % "-"))            ; drop pre-releases
                            (filter #(= fmaj (first (parse-version %)))) ; same major
                            (filter #(>= (compare (parse-version %) floor-v) 0)))
            newest-3   (take 3 (reverse (version-sort candidates)))
            desired    (vec (version-sort (distinct (conj newest-3 floor))))]
        (if (= desired (vec (version-sort current)))
          {:action :none :versions desired}
          {:action  :bump
           :versions desired
           :added   (vec (remove (set current) desired))
           :removed (vec (remove (set desired) current))})))))

(defn- write-matrix!
  "Structurally replace deps.edn's :aliases :ci :datalevin/versions with
  `versions`, preserving all other formatting and comments (rewrite-clj)."
  [versions]
  (let [node (z/node (z/of-string (pr-str (vec versions))))]
    (-> (z/of-file "deps.edn")
        (z/get :aliases)
        (z/get :ci)
        (z/get :datalevin/versions)
        (z/replace node)
        z/root-string
        (->> (spit "deps.edn")))))

(defn bump-matrix
  "Watcher brain (run by the datalevin-watch workflow). Reads the floor + current
  matrix from deps.edn and the newest datalevin releases from Clojars, computes
  the desired matrix (floor + newest-3, same major, no pre-releases), and:
    :bump  -> rewrites deps.edn's :ci matrix in place, reports added/removed
    :major -> reports the new major (workflow opens an issue; no edit)
    :none  -> nothing to do
  Prints a one-line JSON status as the LAST line of stdout for the workflow to
  branch on (action + versions). `:latest`/`:recent` override the Clojars fetch
  for dry-run testing (`:recent` a vector of version strings, newest-first)."
  [{ov-latest :latest ov-recent :recent}]
  (let [deps    (read-deps)
        floor   (floor-version deps)
        current (matrix-versions deps)
        {:keys [latest recent]} (if ov-latest
                                  {:latest ov-latest :recent (or ov-recent [ov-latest])}
                                  (fetch-clojars))
        result  (desired-matrix floor current latest recent)]
    (when (= :bump (:action result))
      (write-matrix! (:versions result)))
    (println (json/write-str (update result :action name) :key-fn name))
    result))

(defn- pom-template [version]
  [[:description "ragtime adaptor for datalevin"]
   [:url "https://github.com/crinklywrappr/syncopate"]
   [:licenses
    [:license
     [:name "Eclipse Public License 2.0"]
     [:url "https://www.eclipse.org/legal/epl-2.0"]]]
   [:developers
    [:developer
     [:name "crinklywrappr"]]]
   [:scm
    [:url "https://github.com/crinklywrappr/syncopate"]
    [:connection "scm:git:https://github.com/crinklywrappr/syncopate.git"]
    [:developerConnection "scm:git:ssh:git@github.com:crinklywrappr/syncopate.git"]
    [:tag (str "v" version)]]])

(defn- jar-opts [opts]
  (assoc opts
          :lib lib   :version version
          :jar-file  (format "target/%s-%s.jar" lib version)
          :basis     (b/create-basis {})
          :class-dir class-dir
          :target    "target"
          :src-dirs  ["src"]
          :pom-data  (pom-template version)))

(defn jar
  "Build the JAR (pom + sources), no tests. In CI the `test-matrix` and `remote`
  jobs already cover the suite, so the deploy job only needs to package."
  [opts]
  (b/delete {:path "target"})
  (let [opts (jar-opts opts)]
    (println "\nWriting pom.xml...")
    (b/write-pom opts)
    (println "\nCopying source...")
    (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (println "\nBuilding JAR..." (:jar-file opts))
    (b/jar opts))
  opts)

(defn ci
  "Run the tests, then build the JAR (local convenience: `jar` gated by `test`)."
  [opts]
  (test opts)
  (jar opts))

(defn install
  "Install the JAR locally."
  [opts]
  (let [opts (jar-opts opts)]
    (b/install opts))
  opts)

(defn deploy
  "Deploy the JAR to Clojars."
  [opts]
  (let [{:keys [jar-file] :as opts} (jar-opts opts)]
    (dd/deploy {:installer :remote :artifact (b/resolve-path jar-file)
                :pom-file (b/pom-path (select-keys opts [:lib :class-dir]))}))
  opts)
