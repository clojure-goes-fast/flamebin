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
         [:body {:style "display:flex; justify-content:center;"}
          [:div {:style "display:flex; flex-direction:column; justify-content: space-between;  width: 1000px; max-width: 100%;"}
           content
           [:footer {:style "text-align:right; padding-botton: 20px"}
            [:p (format "Build: %s (%s)" (@config :build :version)
                        (@config :build :git-sha))]]]]
         rst)])))

(defn header [upload?]
  [:div {:style {:display "flex"
                 :justify-content "space-between"
                 :width "100%"
                 ;; :max-width "100%"
                 :align-items "center"
                 :margin-bottom "40px"}}
   ;; Logo container
   [:a {:href "/"
        :style "text-decoration:none; color:inherit;"}
    [:div {:style {:display "flex"
                   :align-items "center"
                   :gap "10px"}}
     [:img {:src "/public/img/icon.png"
            :alt "Flamebin logo"
            :style {:width "32px"
                    :height "32px"}}]
     [:span {:style {:font-size "24px"
                     :font-weight "500"}}
      "Flamebin"]]]

   ;; Upload button
   [:a {:href "/profiles/upload"
        :style {:visibility (if upload?
                              "visible" "hidden")}}
    [:button {:href "/profiles/upload"
              :style {:background-color "#2F4F4F"
                      :color "white"
                      :padding "8px 16px"
                      :border "none"
                      :border-radius "6px"
                      :cursor "pointer"
                      :font-size "15px"}}
     "UPLOAD"]]])

(defn upload-page []
  (base
   "Upload profile - Flamebin"
   [:div {:style {:font-family "system-ui, -apple-system, sans-serif"
                  :padding "20px"}}
    (header false)
    [:div {:style "flex:1;"}
     [:div {:style "display:flex; justify-content:center"}
      [:div
       [:h1 "Upload collapsed stacks file (.txt)"]
       (form/form-to {:enctype "multipart/form-data"
                      :id "uploadForm"}
                     [:post "this-is-ignored-the-url-is-set-in-javascript"]
                     (form/file-upload {:id "fileInput"} "file")
                     (form/submit-button "Upload"))
       [:div#status]]]]]
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
   [:div {:style {:font-family "system-ui, -apple-system, sans-serif"
                  ;; :max-width "1200px"
                  ;; :margin "0 auto"
                  :padding "20px"}}
    (header true)

    ;; Main heading
    [:h1 {:style {:font-size "48px"
                  :font-weight "300"
                  :text-align "center"
                  :margin "100px 0"
                  :color "#000000"}}
     "Pastebin for your flamegraphs 🔥📈"]

    [:div
     [:h2 {:style {:font-size "24px"
                   :font-weight "600"
                   :margin-bottom "30px"}}
      "EXAMPLES"]

     ;; Grid of uploads - hardcode for now
     [:div {:style {:display "grid"
                    :grid-template-columns "repeat(3, 1fr)"
                    :gap "20px"
                    :margin-bottom "30px"}}
      (for [{:keys [i date url desc]}
            [{:i 1
              :date "01.11.2024"
              :desc "Aleph client and server"
              :url "/Rmxt9P"}
             {:i 2
              :date "09.11.2024"
              :desc "Recursive functions"
              :url "/3anDLN"}
             {:i 3
              :date "21.11.2024"
              :desc "Clojure start-up"
              :url "/8mVw7b"}]]
        [:a {:href url
             :style "text-decoration:none; color:inherit;"}
         [:div
          [:div {:key i
                 :style {:border "1px solid #eee"
                         :border-radius "8px"
                         :overflow "hidden"}}
           [:div {:style {:padding "15px"}}
            [:div {:style {:font-size "18px"
                           :font-weight "500"}}
             date]
            [:div {:style {:color "#666"
                           :margin-top "5px"}}
             desc]]
           [:img {:src (format "/public/img/thumbnail%d.png" i)
                  :style {:width "300px"
                          :height "auto"}}]]]])]]

    [:h2 {:style {:font-size "24px"
                  :font-weight "600"
                  :margin-bottom "30px"}}
     "PUBLIC UPLOADS"]

    [:div {:style "display:flex; justify-content:center"}
     [:ul {:id "flamegraph-list"}
      (for [p (core/list-public-profiles)]
        [:li (format-ts (:upload_ts p)) " - " [:a {:href (format "/%s" (:id p))} (:id p)] ])]]]))
