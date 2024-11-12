(ns flamebin.util
  (:require [clojure.test.check.generators :as gen]
            [clojure.test.check.rose-tree :as rose]
            [taoensso.encore :as encore])
  (:import java.util.concurrent.locks.Lock))

(defmacro with-locking [lock & body]
  (let [l (with-meta (gensym "lock") {:tag `Lock})]
    `(let [~l ~lock]
       (.lock ~l)
       (try ~@body
            (finally (.unlock ~l))))))

;;;; IDs and secrets

(def ^:private id-size 6) ;; Small, may raise later. We check for duplicates.
(def ^:private secret-token-bytes 18)

(def ^:private alphabet
  "Only alphanumeric, no ambiguous characters."
  "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")

(let [generator (encore/rand-id-fn {:chars :nanoid-readable, :len id-size})]
  (defn new-id [] (generator)))

(let [validation-rx (re-pattern (format "\\w{%d}" id-size))]
  (defn valid-id?
    "Check if the provided object looks like a valid nano-id in our system."
    [id]
    (and (string? id) (re-matches validation-rx id))))

#_(every? valid-id? (repeatedly 10000 new-id))

(defn- gen-invoke
  "Given a 0-arg function `f`, return a generator that invokes it whenever a value
  needs to be generated."
  [f]
  (#'gen/make-gen (fn [& _] (rose/pure (f)))))

(def nano-id-registry
  {:nano-id [:and {:gen/gen (gen-invoke new-id)}
             :string
             [:fn {:error/message "Not valid ID"} valid-id?]]})

(let [generator (encore/rand-id-fn {:chars :alphanumeric, :len secret-token-bytes})]
  (defn secret-token [] (generator)))

#_(secret-token)

;;;; Error propagation and handling

(defn raise
  ([msg] (raise 500 msg {}))
  ([http-code msg] (raise http-code msg {}))
  ([http-code msg data]
   (throw (ex-info msg (assoc data :http-code http-code)))))
