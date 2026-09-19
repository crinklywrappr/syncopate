(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

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
  `extra-aliases` are layered onto `:test` when building the spawned JVM's basis
  — this is how a `:dl-*` datalevin-version override actually reaches the test
  classpath (the basis, not the outer -T:build JVM, is what tests run against)."
  [args java-opts extra-aliases]
  (let [basis (b/create-basis {:aliases (into [:test] extra-aliases)})
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
  datalevin version via the matching `:dl-<version>` alias (default = the floor
  pinned in deps.edn; see the support matrix there). `ci` forwards these through."
  [{:keys [args remote server-uri dl] :or {args []} :as opts}]
  (run-kaocha
   (cond-> args remote (into ["--skip-meta" ":embedded"]))
   (cond-> (test-java-opts)
     remote     (conj "-Dsyncopate.test.mode=remote")
     server-uri (conj (str "-Dsyncopate.test.server-uri=" server-uri)))
   (when dl [(keyword (str "dl-" dl))]))
  opts)

(defn coverage
  "Run the tests with cloverage coverage reporting (target/coverage/index.html)."
  [{:keys [args] :or {args []} :as opts}]
  (run-kaocha (into ["--plugin" "cloverage"] args) (test-java-opts) nil)
  opts)

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

(defn ci
  "Run the CI pipeline of tests (and build the JAR)."
  [opts]
  (test opts)
  (b/delete {:path "target"})
  (let [opts (jar-opts opts)]
    (println "\nWriting pom.xml...")
    (b/write-pom opts)
    (println "\nCopying source...")
    (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (println "\nBuilding JAR..." (:jar-file opts))
    (b/jar opts))
  opts)

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
