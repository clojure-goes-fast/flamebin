(ns flamebin.rate-limiter
  (:require [flamebin.config :refer [config]]
            [flamebin.infra.metrics :refer [registry]]
            [mount.lite :as mount])
  (:import (java.util.concurrent.atomic AtomicLong)))

;; Store bucket state as two numbers packed into unsigned long:
;; - first 22 bits - available tokens.
;; - last 42 bits - timestamp of last refill in milliseconds.

(def ^:private ^:const token-bits 22)
(def ^:private ^:const timestamp-bits 42)
(def ^:private ^:const max-supported-tokens (int (Math/pow 2 token-bits)))
(def ^:private ^:const token-mask (dec (long (Math/pow 2 token-bits))))
(def ^:private ^:const timestamp-mask (dec (long (Math/pow 2 timestamp-bits))))
(assert (= timestamp-mask 16r3FFFFFFFFFF))

(set! *unchecked-math* true)

(defmacro pack-state [tokens last-refill]
  `(bit-or (bit-shift-left (bit-and ~tokens token-mask) timestamp-bits)
           (bit-and ~last-refill timestamp-mask)))

(defn- take-token [^AtomicLong state, ^long max-tokens, ^long token-msec-price
                   ^long tokens-to-take]
  (loop []
    (let [*state (.get state)
          last-refill (bit-and *state timestamp-mask)
          tokens (unsigned-bit-shift-right *state timestamp-bits)
          now (System/currentTimeMillis)
          elapsed-ms (- now last-refill)
          tokens-to-add (quot elapsed-ms token-msec-price)
          last-refill' (+ last-refill (* tokens-to-add token-msec-price))
          tokens' (min max-tokens (+ tokens tokens-to-add))
          can-take? (>= tokens' tokens-to-take)
          *state' (pack-state (if can-take? (- tokens' tokens-to-take) tokens')
                              last-refill')]
      #_(println "   last-refill" last-refill
                 "tokens" tokens
                 "now" now
                 "elapsed-ms" elapsed-ms
                 "can-take?" can-take?)
      (if (.compareAndSet state *state *state')
        can-take?
        (recur)))))

(defrecord RateLimiter [^AtomicLong state, ^long max-tokens, ^long token-msec-price]
  clojure.lang.IFn
  (invoke [_] (take-token state max-tokens token-msec-price 1))
  (invoke [_ tokens-to-take] (take-token state max-tokens token-msec-price tokens-to-take)))

(defn rate-limiter
  ([limits-config-map]
   (rate-limiter (:buffer limits-config-map) (:rps limits-config-map)))
  ([^long max-tokens, ^double refill-tps]
   {:pre [(<= max-tokens max-supported-tokens)
          (<= refill-tps 1000)]}
   (let [token-price (max 1 (long (/ 1000 refill-tps)))
         ^AtomicLong state (AtomicLong. (pack-state max-tokens
                                                    (System/currentTimeMillis)))]
     (->RateLimiter state max-tokens token-price))))

(defn current-tokens [^RateLimiter rate-limiter]
  (rate-limiter 0)
  (unsigned-bit-shift-right (.get ^AtomicLong (:state rate-limiter)) timestamp-bits))

;;;; Actual rate-limiters.

(mount/defstate global-processed-kbytes-limiter
  :start (let [rl (rate-limiter (@config :limits :global-processed-kbytes))]
           (.gauge @registry "app.global_processed_limiter_kbytes"
                   rl #(double (current-tokens %)))
           rl))

(mount/defstate global-saved-kbytes-limiter
  :start (let [rl (rate-limiter (@config :limits :global-saved-kbytes))]
           (.gauge @registry "app.global_processed_limiter_kbytes"
                   rl #(double (current-tokens %)))
           rl))

(mount/defstate ^:private per-ip-limiters
  :start (atom {}))

(defn- per-ip-limiter [ip]
  (letfn [(upd [m ip]
            (cond-> m
              (not (m ip))
              (assoc ip {:processed (rate-limiter
                                     (@config :limits :per-ip-processed-kbytes))
                         :saved (rate-limiter
                                 (@config :limits :per-ip-saved-kbytes))})))]
   (or (@@per-ip-limiters ip)
       ((swap! @per-ip-limiters upd ip) ip))))

#_(per-ip-limiter "127.0.0.1")

(defn per-ip-processed-kbytes-limiter [ip]
  (:processed (per-ip-limiter ip)))

(defn per-ip-saved-kbytes-limiter [ip]
  (:saved (per-ip-limiter ip)))

#_(per-ip-saved-kbytes-limiter "127.0.0.1")


(comment
  (let [start (System/currentTimeMillis)
        rl (rate-limiter 5 1)]
    (while true
      (if (rl)
        (println "Allowed on" (- (System/currentTimeMillis) start) "ms")
        (Thread/sleep 1))))
  )
