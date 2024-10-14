(ns flamebin.render
  (:require [clj-async-profiler.render :as cljap.render]
            [clojure.java.io :as io]))

;;;; Flamegraph rendering

(defn render-html-flamegraph [dense-profile options]
  (let [{:keys [stacks id->frame]} dense-profile
        idToFrame (#'cljap.render/print-id-to-frame id->frame)
        data (#'cljap.render/print-add-stacks stacks false)
        user-transforms nil
        full-js (-> (slurp (io/resource "flamegraph-rendering/script.js"))
                    (cljap.render/render-template
                     {:graphTitle     (pr-str (or (:title options) ""))
                      :isDiffgraph    false
                      :userTransforms ""
                      :idToFrame      idToFrame
                      :stacks         data}))]
    (-> (slurp (io/resource "flamegraph-rendering/template.html"))
        (cljap.render/render-template {:script full-js}))))
