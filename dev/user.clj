(ns user)

;; Load all user.clj files (including the system-wide one).
(when *file*
  (->> (.getResources (.getContextClassLoader (Thread/currentThread)) "user.clj")
       enumeration-seq
       rest ; First file in the enumeration will be this file, so skip it.
       (run! #(do (println "Loading" (str %))
                  (clojure.lang.Compiler/load (clojure.java.io/reader %))))))

(defn dev []
  (require 'flamebin.main)
  (flamebin.main/-main))
