(ns flamebin.web.pages
  (:require [clj-async-profiler.render :refer [render-template]]
            [clojure.java.io :as io]
            [flamebin.core :as core]
            [flamebin.config :refer [config]]
            [hiccup.form :as form]
            [hiccup2.core :as h])
  (:import java.time.ZoneId
           java.time.format.DateTimeFormatter))

(defn base [title content & rst]
  (str
   (h/html
       [:html
        [:head
         [:meta {:charset "utf-8"}]
         [:link {:rel "icon" :href "/public/img/icon.png" :type "image/png"}]
         [:title title]]
        (into
         [:body {:style "display:flex; flex-direction:column; height:100%;"}
          content
          [:footer {:style "text-align:right; padding-botton: 20px"}
           [:p (format "Build: %s (%s)" (@config :build :version)
                       (@config :build :git-sha))]]]
         rst)])))

(defn upload-page []
  (base
   "Upload profile - Flamebin"
   [:div {:style "flex:1;"}
    [:div {:style "display:flex; justify-content:center"}
     [:div
      [:h1 "Upload collapsed stacks file (.txt)"]
      (form/form-to {:enctype "multipart/form-data"
                     :id "uploadForm"}
                    [:post "this-is-ignored-the-url-is-set-in-javascript"]
                    (form/file-upload {:id "fileInput"} "file")
                    (form/submit-button "Upload"))
      [:div#status]
      [:a {:href "/"} "↰ Home"]]]]
   [:script (h/raw (render-template
                    (slurp (io/resource "site/upload.js"))
                    {:upload-url "/api/v1/upload-profile?type=cpu&format=collapsed"}))]))

(def ^:private ^DateTimeFormatter date-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))

(defn- format-ts [timestamp]
  (let [local-date-time (.atZone timestamp (ZoneId/systemDefault))]
    (.format date-formatter local-date-time)))

(defn index-page []
  (base
   "Flamebin"
   [:div {:style "flex:1;"}
    [:center [:h1 "Flamebin"]]
    [:center [:h5 "Pastebin for your flamegraphs"]]
    [:div {:style "display:flex; justify-content:center"}
     [:ul {:id "flamegraph-list"}
      (for [p (core/list-public-profiles)]
        [:li (format-ts (:upload_ts p)) " - " [:a {:href (format "/%s" (:id p))} (:id p)] ])]]
    [:center [:p [:a {:href "/profiles/upload"} "Upload another"]]]]))
