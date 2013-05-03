(ns lein-junit.plugin
  (:require [clojure.java.io :as io]
            [leiningen.junit :refer [junit]]
            [leiningen.test :as test]
            [robert.hooke :refer [add-hook]]))

(defn junit-hook [task & args]
  (apply task args)
  (apply junit args))

(defn hooks []
  (add-hook #'test/test junit-hook))

;; cribbed from private leiningen.core.project/absolutize
(defn absolutize [root path]
  (str (if (.isAbsolute (io/file path))
         path
         (io/file root path))))

(defn middleware [{:keys [root] :as project}]
  (update-in project [:junit] (partial map (partial absolutize root))))
