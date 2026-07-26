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
  "Run kaocha in a spawned JVM (with the datalevin JVM flags) with extra `args`."
  [args]
  (let [basis (b/create-basis {:aliases [:test]})
        cmd   (b/java-command
               {:basis     basis
                :main      'clojure.main
                :java-opts (test-java-opts)
                :main-args (into ["-m" "kaocha.runner"] args)})
        {:keys [exit]} (b/process cmd)]
    (when-not (zero? exit) (throw (ex-info "Tests failed" {})))))

(defn test
  "Run all the tests."
  [{:keys [args] :or {args []} :as opts}]
  (run-kaocha args)
  opts)

(defn coverage
  "Run the tests with cloverage coverage reporting (target/coverage/index.html)."
  [{:keys [args] :or {args []} :as opts}]
  (run-kaocha (into ["--plugin" "cloverage"] args))
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
