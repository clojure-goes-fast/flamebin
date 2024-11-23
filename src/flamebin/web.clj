(ns flamebin.web
  (:require [flamebin.config :refer [config]]
            [flamebin.core :as core]
            [flamebin.dto :refer [UploadProfileRequestParams]]
            [flamebin.infra.metrics :as ms]
            [flamebin.rate-limiter :as rl]
            [flamebin.util :refer [raise valid-id?]]
            [flamebin.web.middleware :refer :all]
            [flamebin.web.pages :as pages]
            [mount.lite :as mount]
            [muuntaja.middleware :as content.mw]
            [org.httpkit.server :as server]
            [reitit.coercion.malli]
            [reitit.core :as r]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as ring-coercion]
            [reitit.ring.middleware.parameters :refer [parameters-middleware]]
            [ring.middleware.head]
            [ring.middleware.resource]
            [taoensso.timbre :as log]))

;; Endpoints: API

(defn- ensure-processed-limits [ip length-kb]
  (when-not (and ((rl/per-ip-processed-kbytes-limiter ip) length-kb)
                 (@rl/global-processed-kbytes-limiter length-kb))
    (raise 429 "Upload processed bytes limit reached.")))

(defn- profile-url [{:keys [scheme headers ::r/router]} profile-id read-token]
  (format "%s://%s%s" (name scheme) (get headers "host")
          (-> (r/match-by-name router ::profile-page {:profile-id profile-id})
              (r/match->path (when read-token
                               {:read-token read-token})))))
(defn- deletion-url [{:keys [scheme headers ::r/router]} profile-id edit-token]
  (format "%s://%s%s" (name scheme) (get headers "host")
          (-> (r/match-by-name router ::api-delete-profile)
              (r/match->path {:id profile-id :edit-token edit-token}))))

#_(deletion-url {:scheme :https, :headers {"host" "localhost:8086"},
                 ::r/router (::r/router (meta app))} "abcdef" "123")

(defn $upload-profile [{:keys [remote-addr body query-params] :as req}]
  (let [length-kb (quot (ensure-content-length req) 1024)]
    (ensure-processed-limits remote-addr length-kb)
    (let [{:keys [id read-token edit_token] :as profile}
          (core/save-profile body remote-addr query-params)]
      {:status 201
       :headers (cond-> {"Location" (profile-url req id read-token)
                         "X-Created-ID" (str id)}
                  read-token (assoc "X-Read-Token" read-token)
                  edit_token (assoc "X-Edit-Token" edit_token)
                  edit_token (assoc "X-Deletion-Link" (deletion-url req id edit_token)))
       :body profile})))

(defn $delete-profile [{:keys [remote-addr query-params] :as req}]
  (let [{:keys [id edit-token]} query-params]
    (core/delete-profile id edit-token)
    (resp 200 {:message (str "Successfully deleted profile: " id)})))

;; Endpoints: web pages

(defn $page-upload-file [req]
  (resp (pages/upload-page)))

(defn $page-list-profiles [req]
  (resp (pages/index-page)))

(defn $render-profile [{:keys [path-params query-params] :as req}]
  (let [{:keys [profile-id]} path-params
        {:keys [read-token]} query-params]
    (when-not (valid-id? profile-id)
      (raise 404 (str "Invalid profile ID: " profile-id)))
    {:status 200
     :headers {"content-type" "text/html"}
     :body (core/render-profile profile-id read-token)}))

(defn $public-resource [req]
  (ring.middleware.resource/resource-request req ""))

(def app
  (ring/ring-handler
   (ring/router
    ["" {:middleware [;; ↓↓↓ REQUEST ↓↓↓
                      [wrap-measure-response-time]
                      [wrap-exceptions]
                      [wrap-restore-remote-addr]
                      [wrap-log-request]
                      [wrap-parse-content-length]
                      [content.mw/wrap-format content-middleware-config]
                      [wrap-really-coerce-query-params]
                      ;; ↑↑↑ RESPONSE ↑↑↑
                      ]}
     ;; HTML
     ["" {}
      ["/" {:get {:handler #'$page-list-profiles}}]
      ["/:profile-id" {:name ::profile-page
                       :get {:handler #'$render-profile
                             :coercion reitit.coercion.malli/coercion
                             :parameters {:path {:profile-id :nano-id}
                                          :query' [:map
                                                   [:read-token {:optional true} :string]]}}}]
      ["/profiles/upload" {:get {:handler #'$page-upload-file}}]
      ["/public/*path" {:get {:handler #'$public-resource}}]]

     ;; API
     ["/api/v1" {}
      ["/upload-profile" {:middleware [wrap-gzip-request]
                          :post {:handler #'$upload-profile
                                 :parameters {:query' UploadProfileRequestParams}}}]
      ;; GET so that user can easily do it in the browser.
      ["/delete-profile" {:name ::api-delete-profile
                          :get {:handler #'$delete-profile
                                :parameters {:query' {:id :nano-id
                                                      :edit-token :string}}}}]
      #_["/profiles" {:get {:handler #'$list-profiles}}]]]
    {:data {:middleware [parameters-middleware
                         ;; Needed for coercion to work.
                         ring-coercion/coerce-exceptions-middleware
                         ;; Deliberately putting wrap-exceptions twice to catch
                         ;; coercion requests too.
                         wrap-exceptions
                         ring-coercion/coerce-request-middleware]}})
   (ring/redirect-trailing-slash-handler)
   ;; This middleware should live outside of router because it impacts matching.
   {:middleware [ring.middleware.head/wrap-head]}))

(mount/defstate server
  :start (let [port (@config :server :port)]
           (log/infof "Starting web server on port %d" port)
           (server/run-server #'app {:port port}))
  :stop (@server))

#_(do (mount/stop #'server) (mount/start #'server))

;;;; Prometheus

(def prom-app
  (ring/ring-handler
   (ring/router
    ["/" {:get {:handler (fn [_] {:body (ms/scrape)})}}])
   (ring/redirect-trailing-slash-handler)))

(mount/defstate prom-server
  :start (let [port (@config :metrics :port)]
           (log/infof "Starting Prometheus exporter on port %d" port)
           (server/run-server #'prom-app {:port port}))
  :stop (@prom-server))

#_(do (mount/stop #'prom-server) (mount/start #'prom-server))
