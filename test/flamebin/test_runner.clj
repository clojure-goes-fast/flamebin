(ns flamebin.test-runner
  (:require cognitect.test-runner.api
            mount.lite))

(defn test [opts]
  (cognitect.test-runner.api/test opts)
  (mount.lite/stop)
  (shutdown-agents))
