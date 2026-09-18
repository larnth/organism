(ns organism.learning-media-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [organism.routes.shared :as shared]
   [organism.routes.organism :as routes]))

(deftest every-advertised-learning-video-is-a-packaged-resource
  (doseq [file (conj (mapv :file routes/action-clips) "zach-dan-ryan-play.mp4")]
    (is (some? (io/resource (str "public/video/" file)))
        (str "Missing learning media: " file))))

(deftest learning-clips-have-posters-and-accessible-playback
  (let [html (:body (shared/learn-page routes/organism-spec {}))]
    (doseq [{:keys [file title]} routes/action-clips
            :let [poster (str/replace file #"\.mp4$" ".jpg")]]
      (is (some? (io/resource (str "public/video/" poster))))
      (is (str/includes? html (str "poster=\"/video/" poster "\"")))
      (is (str/includes? html (str "aria-label=\"" title " example\""))))
    (is (str/includes? html "prefers-reduced-motion"))
    (is (str/includes? html "Video unavailable"))))
