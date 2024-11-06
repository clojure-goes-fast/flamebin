(ns flamebin.main
  (:require [clojure.pprint :refer [pprint]]
            [flamebin.db :as db]
            flamebin.infra.repl
            [flamebin.web :as web]
            [mount.extensions.basic :as mount.ext]
            [mount.lite :as mount]
            [taoensso.timbre :as log]))

;; Don't remove
flamebin.infra.repl/nrepl-server

(defn -main [& args]
  ;; Start internal machinery first.
  (mount.ext/with-except [#'web/server #'db/db]
    (mount/start))
  ;; Load and migrate DB.
  (mount.ext/with-only [#'db/db]
    (mount/start))
  ;; Only then start the main server.
  (mount.extensions.basic/with-only [#'web/server]
    (mount/start))
  (log/info "Component status:\n" (with-out-str (pprint (mount/status)))))
