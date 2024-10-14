(ns flamebin.util
  (:require [malli.core :as m]
            [nano-id.core :as nano-id]
            [nano-id.random]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.rose-tree :as rose])
  (:import java.util.Base64
           java.util.concurrent.locks.Lock))

(defmacro with-locking [lock & body]
  (let [l (with-meta (gensym "lock") {:tag `Lock})]
    `(let [~l ~lock]
       (.lock ~l)
       (try ~@body
            (finally (.unlock ~l))))))

;;;; NanoID

(def ^:private id-size 10) ;; On the smaller side, may raise later.
(def ^:private secret-token-bytes 18)

(def ^:private alphabet
  "Only alphanumeric, no ambiguous characters."
  "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")

(let [generator (nano-id/custom alphabet id-size)]
  (defn new-id [] (generator)))

(let [validation-rx (re-pattern (format "[%s]{%d}" alphabet id-size))]
  (defn valid-id?
    "Check if the provided object looks like a valid nano-id in our system."
    [id]
    (and (string? id)
         (= (count id) id-size)
         (re-matches validation-rx id))))

;#_(every? valid-id? (repeatedly 10000 new-id))

(defn- gen-invoke
  "Given a 0-arg function `f`, return a generator that invokes it whenever a value
  needs to be generated."
  [f]
  (#'gen/make-gen (fn [& _] (rose/pure (f)))))

(def nano-id-registry
  {:nano-id [:and {:gen/gen (gen-invoke new-id)}
             :string
             [:fn valid-id?]]})

(defn secret-token []
  (.encodeToString (Base64/getUrlEncoder)
                   (nano-id.random/random-bytes secret-token-bytes)))

#_(secret-token)

;;;; Error propagation and handling

(defn raise
  ([msg] (raise 500 msg {}))
  ([http-code msg] (raise http-code msg {}))
  ([http-code msg data]
   (throw (ex-info msg (assoc data :http-code http-code)))))
