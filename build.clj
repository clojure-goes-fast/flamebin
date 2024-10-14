(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]))

(defn copy-deps [{:keys [target-dir]}]
  (assert target-dir)
  (let [basis (b/create-basis {})
        lib-map (:libs basis)
        target-dir (doto (io/file target-dir)
                     .mkdirs)
        total-size (volatile! 0)]
    (doseq [[lib {:keys [paths]}] lib-map]
      (let [jar-path (first paths)
            jar-file (io/file jar-path)
            target-file (io/file target-dir (.getName jar-file))
            size (.length jar-file)]
        (println (format "Writing %s (%,d b)" target-file size))
        (io/copy jar-file target-file)
        (vswap! total-size + size)))
    (println (format "---\nWritten %d files (%,d b)" (count lib-map) @total-size))))
