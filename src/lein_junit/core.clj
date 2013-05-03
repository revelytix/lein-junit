(ns lein-junit.core
  (:refer-clojure :exclude [replace])
  (:require [clojure.java.io :refer [file]]
            [clojure.string :refer [join replace]]
            [lancet.core :as lancet]
            [leiningen.core.classpath :refer [get-classpath]]
            [leiningen.core.main :as main]
            [leiningen.javac :refer [javac]]
            [leiningen.core.eval :as eval])
  (:import [java.io File]
           [org.apache.tools.ant.types FileSet Path]
           [org.apache.tools.ant.taskdefs.optional.junit
            BriefJUnitResultFormatter FormatterElement SummaryJUnitResultFormatter
            PlainJUnitResultFormatter XMLJUnitResultFormatter]))

(def ^{:dynamic true} *junit-options*
  {:fork "on" :haltonerror "off" :haltonfailure "off"})

(defn junit-compile-path
  [project]
  (or (:junit-compile-path project)
      (:compile-path project)))

(defmethod lancet/coerce [Path String] [_ str]
  (Path. lancet/ant-project str))

(defn selector-pattern [selector]
  (re-pattern (str (replace selector "." File/separator) ".*")))

(defn relativize [root path]
  (.getPath (.relativize (.toURI (file root)) (.toURI (file path)))))

(defn find-testcases
  "Returns the class filesnames of the project's Junit test cases."
  [project]
  (for [path (:junit project)
        file (file-seq (file path))
        :when (and (not (.isDirectory file))
                   (re-matches #".*Test\.java" (str file)))]
    (-> (relativize path file)
        (replace (re-pattern (str ".*" File/separator path File/separator)) "")
        (replace #"\.java" ".class"))))

(defn select-testcases
  "Returns the class filesnames of the project's Junit test cases matching the selectors."
  [project & selectors]
  (filter (fn [testcase] (or (empty? selectors)
                            (some #(re-matches (selector-pattern %1) (str testcase)) selectors)))
          (find-testcases project)))

(defn testcase-fileset [project & selectors]
  (let [tests (map str (apply select-testcases project selectors))
        fileset (FileSet.)]
    (.setProject fileset lancet/ant-project)
    (.setDir fileset (file (junit-compile-path project)))
    (if (empty? tests)
      (.setExcludes fileset "**/*.class")
      (.setIncludes fileset (join " " (map str tests))))
    fileset))

(defn junit-formatter-class
  "Returns a JUnit formatter for the given type. Type can be a string
  or a keyword."
  [type]
  (case (keyword type)
    :brief BriefJUnitResultFormatter
    :plain PlainJUnitResultFormatter
    :xml XMLJUnitResultFormatter
    :summary SummaryJUnitResultFormatter))

(defn junit-formatter-element
  "Returns a JUnit formatter element for the given type. Type can be a
  string or a keyword."
  [type & [use-file]]
  (doto (FormatterElement.)
    (.setClassname (.getName (junit-formatter-class type)))
    (.setUseFile false)))

(defn extract-formatter
  "Extract the Junit formatter element from the project."
  [project] (junit-formatter-element (or (:junit-formatter project) :brief)))

(defn junit-options
  "Returns the JUnit options of the project."
  [project] (merge *junit-options* (:junit-options project)))

(defn configure-batch-test
  "Configure the JUnit batch test."
  [project junit-task & filesets]
  (let [batch-task (.createBatchTest junit-task)
        junit-options (junit-options project)]
    (doseq [fileset filesets] (.addFileSet batch-task fileset))
    (doto batch-task
      (.addFormatter (extract-formatter project))
      (.setFork (lancet/coerce Boolean/TYPE (:fork junit-options)))
      (.setHaltonerror (lancet/coerce Boolean/TYPE (:haltonerror junit-options)))
      (.setHaltonfailure (lancet/coerce Boolean/TYPE (:haltonfailure junit-options))))))

(defn configure-classpath
  "Configure the classpath for the JUnit task."
  [project junit-task]
  (let [classpath (.createClasspath junit-task)]
    (doseq [path (concat (get-classpath project) (when-let [p (:junit-compile-path project)] [p]))]
      (.addExisting classpath (Path. lancet/ant-project (str path))))
    classpath))

(defn configure-jvm-args
  "Configure the JVM arguments for the JUnit task."
  [project junit-task]
  (doseq [arg (@#'leiningen.core.eval/get-jvm-args project)]
    (when-not (re-matches #"^-Xbootclasspath.+" arg)
      (.setValue (.createJvmarg junit-task) arg))))

(defn extract-task [project & selectors]
  (let [junit-task (lancet/junit (junit-options project))]
    (.setErrorProperty junit-task "lein-junit.errors")
    (.setFailureProperty junit-task "lein-junit.failures")
    (configure-batch-test project junit-task (apply testcase-fileset project selectors))
    (configure-classpath project junit-task)
    (configure-jvm-args project junit-task)
    junit-task))

(defn junit
  "Run the Java test via JUnit."
  [project & selectors]
  (eval/run-prep-tasks project)
  (when (some (complement (set (:java-source-paths project))) (:junit project))
    (javac (assoc project
             :java-source-paths (:junit project)
             :compile-path (junit-compile-path project)
             :resource-paths (concat [(:compile-path project)] (:resource-paths project)))))
  (let [junit-task (apply extract-task project selectors)]
    (.execute junit-task)
    (when (or (.getProperty lancet/ant-project "lein-junit.errors")
              (.getProperty lancet/ant-project "lein-junit.failures"))
      (main/abort "JUnit tests failed."))))
