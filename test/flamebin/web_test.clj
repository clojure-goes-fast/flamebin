(ns flamebin.web-test
  (:require [flamebin.web :as sut]
            [clojure.test :refer :all]
            [flamebin.util :refer [valid-id?]]
            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [org.httpkit.client :as http]
            [flamebin.processing :as proc]
            [flamebin.test-utils :refer :all]
            [hickory.core :as html]
            [jsonista.core :as json]
            [matcher-combinators.test]
            [matcher-combinators.matchers :as matchers :refer [via]]
            )
  (:import java.time.Instant))

(defn- url [url] (str "http://localhost:8086" url))

(defn- req
  ([type method url']
   (req type method url' {}))
  ([type method url' opts]
   (let [resp (update @(http/request (merge opts {:method method :url (url url')}))
                      :opts #(if (:body %)
                               (assoc % :body "<redacted>")
                               %))]
     (if (and (< (:status resp 999) 400) (#{:api :page} type))
       (case type
         :api (update resp :body #(json/read-value % (json/object-mapper {:decode-key-fn true})))
         :page (update resp :body #(html/as-hiccup (html/parse (if (string? %) % (some-> % slurp))))))
       (update resp :body #(if (string? %) % (some-> % slurp)))))))

(defn- find-elem
  ([html tag] (find-elem html tag nil))
  ([html tag id]
   (let [res (volatile! nil)]
     (walk/prewalk #(if (and (vector? %) (= (first %) tag)
                             (or (nil? id) (= (:id (second %)) id)))
                      (do (vreset! res %) nil)
                      %)
                   html)
     @res)))

(defn- gzip-content [content]
  (let [baos (java.io.ByteArrayOutputStream.)]
    (with-open [s (java.util.zip.GZIPOutputStream. baos)]
      (io/copy content s))
    (.toByteArray baos)))

#_(gzip-content (io/file "test/res/small.txt"))

(defn- serialized-edn [edn]
  (binding [*print-length* nil
            *print-level* nil]
    (.getBytes (pr-str edn))))

;; TODO: test unprocessable entity

(deftest basic-usage-test
  (with-temp :all
    (testing "open empty index page"
      (is (match? {:status 200
                   :body (via #(find-elem % :ul "flamegraph-list")
                              [:ul {:id "flamegraph-list"}])}
                  (req :page :get "/"))))

    (testing "upload test flamegraph"
      (let [resp (req :api :post "/api/v1/upload-profile?format=collapsed&type=cpu" {:body (io/file "test/res/small.txt")})]
        (is (match? {:status 201
                     :headers {:x-read-token string?
                               :x-edit-token string?
                               :x-created-id valid-id?
                               :location string?}
                     :body {:upload_ts #(instance? Instant (Instant/parse %))
                            :read-token string?
                            :edit_token string?
                            :is_public false
                            :profile_type "cpu"
                            :id valid-id?
                            :file_path string?
                            :owner "127.0.0.1"
                            :sample_count 10050}}
                    resp))

        (testing "view flamegraph"
          (is (match? {:status 200
                       :headers {:content-length (via parse-long #(> % 50000))}}
                      (req nil :get (format "/%s?read-token=%s" (:id (:body resp)) (:read-token (:body resp)))))))

        (testing "delete flamegraph"
          (let [resp3 (req :api :get (format "/api/v1/delete-profile?id=%s&edit-token=%s" (:id (:body resp)) (:edit_token (:body resp))))]
            (is (match? {:status 200
                         :body {:message #"^Successfully deleted profile"}}
                        resp3))))

        (testing "index is still empty"
          (is (match? {:status 200
                       :body (via #(find-elem % :ul "flamegraph-list")
                                  [:ul {:id "flamegraph-list"}])}
                      (req :page :get "/"))))))))

(deftest front-page-visibility-test
  (with-temp :all
    (dotimes [_ 5]
      ;; Upload one public and one private on each iteration
      (is (match? {:status 201}
                  (req :api :post "/api/v1/upload-profile?format=collapsed&type=cpu&public=true"
                       {:body (io/file "test/res/small.txt")})))
      (is (match? {:status 201}
                  (req :api :post "/api/v1/upload-profile?format=collapsed&type=cpu"
                       {:body (io/file "test/res/small.txt")}))))

    (testing "front page should only show five public flamegraph"
      (let [front-page (req :page :get "/")
            flamegraph-list (-> front-page :body (find-elem :ul "flamegraph-list"))
            url-no-read-token (via #(-> (find-elem % :a) second :href)
                                   #"^/\w+$")]
        (is (match? {:status 200
                     :body (via #(find-elem % :ul "flamegraph-list")
                                (into [:ul {:id "flamegraph-list"}]
                                      (repeat 5 url-no-read-token)))}
                    (req :page :get "/")))
        (testing "links on the frontpage lead to flamegraphs"
          (let [u (-> (find-elem flamegraph-list :a) second :href)]
            (is (match? {:status 200
                         :headers {:content-length (via parse-long #(> % 50000))}}
                        (req nil :get u)))))))))

(deftest different-upload-formats-test
  (with-temp :all
    (doseq [file ["small.txt" "normal.txt" "huge.txt"]
            frmt [:collapsed :dense-edn]
            gzip? [false true]
            ;; Exclusions
            :when (not (or (= [file frmt gzip?] ["normal.txt" :collapsed false])
                           (= [file frmt]       ["huge.txt" :collapsed])))
            :let [file (io/file "test/res" file)]]
      (testing (format "upload: file=%s gzip?=%s format=%s" (str file) gzip? frmt)
        (let [resp (req :api :post (format "/api/v1/upload-profile?format=%s&type=cpu&public=true"
                                           (name frmt))
                        {:headers (if gzip? {"Content-encoding" "gzip"} nil)
                         :body (cond-> file
                                 (= frmt :dense-edn) (-> proc/collapsed-stacks-stream->dense-profile
                                                         serialized-edn)
                                 gzip? gzip-content)})]
          (is (match? {:status 201} (dissoc resp :opts)))
          (is (match? {:status 200} (req nil :get (str "/" (:id (:body resp)))))))))

    (testing "big files are rejected by the webserver"
      (is (match? {:error any?}
                  (req :api :post "/api/v1/upload-profile?format=collapsed&type=cpu&public=true"
                        {:body (io/file "test/res/huge.txt")}))))))

(deftest save-profile-config-test
  (with-temp :all
    (let [resp (req :api :post "/api/v1/upload-profile?format=collapsed&type=cpu&public=true"
                    {:body (io/file "test/res/small.txt")})
          {:keys [id edit_token]} (:body resp)]
      (let [conf "H4sIAAAAAAAAE6tWyshMz8jJTM8oUbJSSjJX0lEqKUrMK07LL8otVrKKjq0FALrNy6siAAAA"
            resp (req :api :post (format "/api/v1/save-profile-config?id=%s&edit-token=%s&config=%s"
                                         id "bad-token" conf))]
        (testing "requires edit-token"
          (is (match? {:status 403}
                      (req :api :post (format "/api/v1/save-profile-config?id=%s&edit-token=%s&config=%s"
                                              id "bad-token" conf)))))
        (testing "rejects big config"
          (is (match? {:status 413}
                      (req :api :post (format "/api/v1/save-profile-config?id=%s&edit-token=%s&config=%s"
                                              id edit_token (apply str (repeat 2001 \a)))))))

        (let [conf1 "H4sIAAAAAAAAE6tWyshMz8jJTM8oUbJSykjNyclX0lEqzi8q0U2qVLJSykvMTVXSUSopSswrTssvyi1WsoqOrQUA1WAM1jYAAAA="]
          (testing "accepts valid config"
            (is (match? {:status 204}
                        (req nil :post (format "/api/v1/save-profile-config?id=%s&edit-token=%s&config=%s"
                                               id edit_token conf1))))

            (testing "which is then getting baked into flamegraph"
              (is (match? {:status 200
                           :body (re-pattern (format "\nconst bakedPackedConfig = \"%s\"" conf1))}
                          (req nil :get (format "/%s" id)))))))

        (let [conf2 "H4sIAAAAAAAAE6tWKkotSy0qTlWyKikqTdVRKilKzCtOyy_KLVayio6tBQBhhHuhIAAAAA=="]
          (testing "swap to another config"
            (is (match? {:status 204}
                        (req nil :post (format "/api/v1/save-profile-config?id=%s&edit-token=%s&config=%s"
                                               id edit_token conf2))))

            (is (match? {:status 200
                         :body (re-pattern (format "\nconst bakedPackedConfig = \"%s\"" conf2))}
                        (req nil :get (format "/%s" id))))))))))
