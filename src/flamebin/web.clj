(ns flamebin.web
  (:require [flamebin.config :refer [config]]
            [flamebin.core :as core]
            [flamebin.dto :refer [->UploadProfileRequest]]
            [flamebin.infra.metrics :as ms]
            [flamebin.rate-limiter :as rl]
            [flamebin.util :refer [raise valid-id?]]
            [flamebin.util.streams :as streams]
            [flamebin.web.pages :as pages]
            [malli.core :as m]
            malli.error
            [mount.lite :as mount]
            [muuntaja.core :as content]
            [muuntaja.middleware :as content.mw]
            [org.httpkit.server :as server]
            [reitit.coercion.malli]
            [reitit.core :as r]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as ring-coercion]
            [reitit.ring.middleware.parameters :refer [parameters-middleware]]
            [ring.middleware.resource]
            [taoensso.timbre :as log])
  (:import clojure.lang.ExceptionInfo))

;; Middleware

(defn resp
  ([body] (resp 200 body))
  ([status body] {:status status, :body body}))

(def ^:private content-middleware-config
  (content/create
   (update content/default-options :formats select-keys ["application/json"])))

(defn- ensure-content-length [request]
  (or (get-in request [:headers "content-length"])
      (raise 411 "Content-Length header is required.")))

(defn wrap-parse-content-length [handler]
  (fn [request]
    (if-let [length (some-> (get-in request [:headers "content-length"])
                            parse-long)]
      (do (ms/mark "app.http.content_length_bytes" {} length)
          (handler (assoc-in request [:headers "content-length"] length)))
      (handler request))))

(defn wrap-gzip-request [handler]
  (fn [request]
    (if (= (get-in request [:headers "content-encoding"]) "gzip")
      (let [length (ensure-content-length request)
            stream (streams/safe-gzip-input-stream (:body request) length)
            response (handler (assoc request :gzipped? true, :body stream))
            read (streams/get-byte-count stream)]
        (ms/mark "app.http.gzip_expanded_bytes" {} (double read))
        response)
      (handler request))))

(defn wrap-ignore-form-params [handler]
  (fn [request]
    ;; Hack for wrap-params to ignore :body and not parse it to get form-params.
    (handler (update request :form-params #(or % {})))))

(defn wrap-exceptions [handler]
  (letfn [(ISE [ex]
            (log/error ex "Unhandled error while processing request.")
            (resp 500 "Internal server error."))]
    (fn [request]
      (try (handler request)
           (catch ExceptionInfo ex
             (let [{:keys [http-code type data]} (ex-data ex)]
               (cond http-code (resp http-code (ex-message ex))

                     (= type ::m/coercion)
                     (do (log/warn "Malli error" data)
                         (resp 400 (str "Validation error: "
                                        (malli.error/humanize (:explain data)))))

                     :else (ISE ex))))
           (catch Exception ex (ISE ex))))))

(defn wrap-measure-response-time [handler]
  (fn [request]
    (let [sw (ms/stopwatch)
          {:keys [status] :as response} (handler request)
          path (or (:template (::r/match request)) "unknown")]
      (sw "app.http.response_time" {:path path, :code status})
      response)))

(defn wrap-restore-remote-addr
  "Restore the correct :remote-addr from Cloudflare headers if present."
  [handler]
  (fn [request]
    (let [{:strs [cf-connecting-ip]} (:headers request)]
      (handler (update request :remote-addr #(or cf-connecting-ip %))))))

(defn wrap-log-request [handler]
  (fn [{:keys [remote-addr request-method uri] :as request}]
    (log/infof "HTTP [%s] %s %s" remote-addr request-method uri)
    (handler request)))

;; Endpoints: API

(defn- ensure-processed-limits [ip length-kb]
  (when-not (and ((rl/per-ip-processed-kbytes-limiter ip) length-kb)
                 (@rl/global-processed-kbytes-limiter length-kb))
    (raise 429 "Upload processed bytes limit reached.")))

(defn- profile-url [router profile-id read-password]
  (format "https://%s%s%s"
          (@config :server :host)
          (-> (r/match-by-name router ::profile-page {:profile-id profile-id})
              r/match->path)
          (if read-password
            (str "?read_password=" read-password)
            "")))

(defn $upload-profile [{:keys [remote-addr body query-params], router ::r/router
                        :as req}]
  (let [length-kb (quot (ensure-content-length req) 1024)]
    (ensure-processed-limits remote-addr length-kb)
    ;; TODO: probably better validate in routes coercion.
    (let [{:strs [kind type private], pformat "format"} query-params
          req' (->UploadProfileRequest pformat (or kind :flamegraph) type
                                       (= private "true"))

          {:keys [id read_password] :as profile} (core/save-profile body remote-addr req')]
      {:status 201
       :headers (cond-> {"Location" (profile-url router id read_password)
                         "X-Created-ID" (str id)}
                    read_password (assoc "X-Read-Password" read_password))
       :body profile})))

;; Endpoints: web pages

(defn $page-upload-file [req]
  (resp (pages/upload-page)))

(defn $page-list-profiles [req]
  (resp (pages/index-page)))

(defn $render-profile [{:keys [path-params query-params] :as req}]
  ;; TODO: define read_password in routes.
  (let [{:keys [profile-id]} path-params]
    (when-not (valid-id? profile-id)
      (raise 404 (str "Invalid profile ID: " profile-id)))
    {:headers {"content-type" "text/html"}
     :body (core/render-profile profile-id (query-params "read_password"))}))

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
                      [wrap-ignore-form-params]
                      [parameters-middleware]
                      ;; ↑↑↑ RESPONSE ↑↑↑
                      ]}
     ;; HTML
     ["" {}
      ["/" {:get {:handler #'$page-list-profiles}}]
      ["/public/*path" {:get {:handler #'$public-resource}}]
      ["/profiles/upload" {:get {:handler #'$page-upload-file}}]
      ["/:profile-id" {:name ::profile-page
                       :get {:handler #'$render-profile
                             :coercion reitit.coercion.malli/coercion
                             :parameters {:path {:profile-id string?}}}}]]

     ;; API
     ["/api/v1" {}
      ["/upload-profile" {:middleware [[wrap-gzip-request]]
                          :post {:handler #'$upload-profile}}]
      #_["/profiles" {:get {:handler #'$list-profiles}}]]

     ;; Infra
     ["/infra" {}
      ["/health" {:get {:handler (fn [_] (resp (str (java.util.Date.))))}}]]]
    {:data {:middleware [;; Needed for coercion to work.
                         ring-coercion/coerce-exceptions-middleware
                         ring-coercion/coerce-request-middleware]}})
   (ring/redirect-trailing-slash-handler)))

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
