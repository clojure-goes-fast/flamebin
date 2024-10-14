(ns flamebin.main
  (:require [flamebin.db :as db]
            [flamebin.web :as web]
            [mount.extensions.basic :as mount.ext]
            [mount.lite :as mount]))

(defn -main [& args]
  ;; Start internal machinery first.
  (mount.ext/with-except [#'web/server #'db/db]
    (mount/start))
  ;; Load and migrate DB.
  (mount.ext/with-only [#'db/db]
    (mount/start))
  ;; Only then start the main server.
  (mount.extensions.basic/with-only [#'web/server]
    (mount/start)))
