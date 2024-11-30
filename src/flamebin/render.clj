(ns flamebin.render
  (:require [clj-async-profiler.render :as cljap.render]
            [clojure.java.io :as io]))

;;;; Flamegraph rendering

(defn render-html-flamegraph [dense-profile profile-dto options]
  (let [{:keys [stacks id->frame]} dense-profile
        {:keys [config]} profile-dto
        config (if config
                 (str "\"" config "\"")
                 "null")
        idToFrame (#'cljap.render/print-id-to-frame id->frame)
        data (#'cljap.render/print-add-stacks stacks false)
        user-transforms nil
        full-js (-> (slurp (io/resource "flamegraph/script.js"))
                    (cljap.render/render-template
                     {:graphTitle     (pr-str (or (:title options) ""))
                      :profileId      (:id profile-dto)
                      :isDiffgraph    false
                      :userTransforms ""
                      :idToFrame      idToFrame
                      :config         config
                      :stacks         data}))]
    (-> (slurp (io/resource "flamegraph/template.html"))
        (cljap.render/render-template {:script full-js}))))
