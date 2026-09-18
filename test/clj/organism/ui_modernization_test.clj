(ns organism.ui-modernization-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [organism.layout :as layout]
   [organism.routes.organism :as organism]))

(deftest bot-sandbox-get-only-renders-an-explicit-post-confirmation
  (with-redefs [layout/render (fn [_ template context]
                               {:template template :context context})]
    (is (= {:template "organism/generate.html" :context {}}
           (organism/generate-page {}))))
  (let [template (slurp (io/resource "html/organism/generate.html"))]
    (is (str/includes? template "method=\"POST\""))
    (is (str/includes? template "{% csrf-field %}"))
    (is (str/includes? template "This creates a new five-player bot game"))))

(deftest modernized-surfaces-load-their-shared-style-systems
  (testing "community surfaces"
    (doseq [resource ["html/organism/observe.html"
                      "html/organism/player.html"
                      "html/organism/players.html"
                      "html/organism/learn.html"]]
      (is (str/includes? (slurp (io/resource resource))
                         "/css/organism-community.css")
          resource)))
  (testing "bot surfaces"
    (doseq [resource ["html/organism/bots.html"
                      "html/organism/bot_editor.html"]]
      (is (str/includes? (slurp (io/resource resource))
                         "/css/organism-bots.css")
          resource))))

(deftest account-color-script-supports-persisted-hsl-and-save-feedback
  (let [source (slurp (io/resource "public/js/account.js"))]
    (is (str/includes? source "function colorToHsl"))
    (is (str/includes? source "Saving…"))
    (is (str/includes? source "Could not save. Try again."))))