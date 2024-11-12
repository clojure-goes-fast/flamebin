(ns flamebin.web.middleware
  (:require [flamebin.infra.metrics :as ms]
            [flamebin.util :refer [raise]]
            [flamebin.util.streams :as streams]
            [malli.core :as m]
            [malli.experimental.lite]
            [malli.error]
            [muuntaja.core :as content]
            [reitit.ring :as ring]
            [taoensso.timbre :as log]
            [flamebin.dto :as dto])
  (:import (clojure.lang ExceptionInfo)))

(defn resp
  ([body] (resp 200 body))
  ([status body] {:status status, :body body}))

(def content-middleware-config
  (content/create
   (update content/default-options :formats select-keys ["application/json"])))

(defn ensure-content-length [request]
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
                     (let [humanized (malli.error/humanize (:explain data))]
                       (log/warn "Malli error" humanized)
                       (resp 400 {:message (str "Validation error: " humanized)}))

                     (= type :reitit.coercion/request-coercion)
                     (let [humanized (malli.error/humanize (ex-data ex))]
                       (log/warn "Reitit error" humanized)
                       (resp 400 {:message (str "Coercion error: " humanized)}))

                     :else (ISE ex))))
           (catch Exception ex (ISE ex))))))

(defn wrap-measure-response-time [handler]
  (fn [request]
    (let [sw (ms/stopwatch)
          {:keys [status] :as response} (handler request)
          path (or (:template (ring/get-match request)) "unknown")]
      (sw "app.http.response_time" {:path path, :code (or status 200)})
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

(defn wrap-really-coerce-query-params [handler]
  (fn [request]
    (let [method (:request-method request)
          [schema-data] (some-> request ring/get-match :data (get method) :parameters :query')
          query-params (not-empty (:query-params request))]
      (if (and schema-data query-params)
        ;; TODO: prevent interning attack?
        (let [schema (if (satisfies? m/Schema schema-data)
                       schema-data
                       (malli.experimental.lite/schema schema-data))
              coerced (dto/coerce query-params schema)]
          (handler (assoc request :query-params coerced)))
        (handler request)))))
