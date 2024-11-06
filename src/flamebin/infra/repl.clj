(ns flamebin.infra.repl
  (:require [flamebin.config :refer [config]]
            [mount.lite :as mount]
            nrepl.server
            [taoensso.timbre :as log]))

(mount/defstate nrepl-server
  :start (when (@config :repl :enabled)
           (log/info "Starting nREPL server on port" (@config :repl :port))
           (nrepl.server/start-server :port (@config :repl :port)
                                      :bind "0.0.0.0"))
  :stop (some-> @nrepl-server nrepl.server/stop-server))
