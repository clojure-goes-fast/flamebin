(ns flamebin.util.malli
  (:require [clojure.test.check.generators :as gen]
            [clojure.test.check.rose-tree :as rose]
            [flamebin.util :as util]
            [malli.core :as m]
            [malli.experimental.time]
            [malli.experimental.time.transform]
            [malli.registry]
            [malli.transform :as mt]))

(defn- gen-invoke
  "Given a 0-arg function `f`, return a generator that invokes it whenever a value
  needs to be generated."
  [f]
  (#'gen/make-gen (fn [& _] (rose/pure (f)))))

(def nano-id-registry
  {:nano-id [:and {:gen/gen (gen-invoke util/new-id)}
             :string
             [:fn {:error/message "Not valid ID"} util/valid-id?]]})

(def ^:private int-to-boolean
  {:decoders
   {:boolean #(cond (= % 1) true
                    (= % 0) false
                    :else %)}})

(def global-transformer
  (mt/transformer
   mt/string-transformer
   (mt/key-transformer {:decode keyword})
   int-to-boolean
   malli.experimental.time.transform/time-transformer
   mt/default-value-transformer))

(defn coerce [value schema]
  (m/coerce schema value global-transformer))

(malli.registry/set-default-registry!
 (malli.registry/composite-registry
  (malli.core/default-schemas)
  (malli.experimental.time/schemas)
  nano-id-registry))
