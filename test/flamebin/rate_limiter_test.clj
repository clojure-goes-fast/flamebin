(ns flamebin.rate-limiter-test
  (:require [flamebin.rate-limiter :as sut]
            [clojure.test :refer :all]))

(deftest rate-limiter-test
  (let [limiter (sut/rate-limiter 5 10.0)]
    (Thread/sleep 100)
    (every? true? (repeatedly 5 limiter))
    (is (not (limiter)))
    (is (not (limiter)))
    (is (not (limiter)))

    (Thread/sleep 100)
    (is (limiter))
    (is (not (limiter)))

    (Thread/sleep 700)
    (every? true? (repeatedly 5 limiter))
    (is (not (limiter)))))
