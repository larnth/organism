(ns organism.registration-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [buddy.hashers :as hashers]
   [organism.layout :as layout]
   [organism.persist :as persist]
   [organism.routes.home :as home]))

(def valid-params
  {:player "Moss And Moon"
   :password "a-disposable-test-passphrase"
   :password-confirm "a-disposable-test-passphrase"
   :redirect "/organism/play?table=Moss%20Garden&mode=join"})

(deftest organism-registration-renders-the-approved-progressive-form
  (let [html (:body (home/register-page {:query-params {"redirect" "/organism/play"}}))]
    (doseq [text ["class=\"organism-registration\"" "Create your account."
                  "id=\"registration-form\"" "method=\"POST\"" "action=\"/register\""
                  "__anti-forgery-token" "autocomplete=\"username\""
                  "autocomplete=\"new-password\"" "minlength=\"12\""
                  "data-max-length=\"200\"" "12–200 characters"
                  "aria-describedby=\"password-rules password-feedback password-caps\""
                  "/css/registration.css?v=20260915-player-dock"
                  "/js/registration.js?v=20260915-player-dock"]]
      (is (str/includes? html text) text))
    (doseq [text ["novalidate" "bulma.min.css" "Math.random" "Preview controls" "demo-tools"]]
      (is (not (str/includes? html text)) text))))

(deftest rendered-policy-agrees-with-server-validation
  (let [html (:body (home/register-page {}))
        minimum (some-> (re-find #"minlength=\"(\d+)\"" html) second parse-long)
        maximum (some-> (re-find #"id=\"password\"[^>]*data-max-length=\"(\d+)\"" html) second parse-long)
        pattern (some-> (re-find #"pattern=\"([^\"]+)\"" html) second re-pattern)]
    (is (= 12 minimum))
    (is (= 200 maximum))
    (is (some? pattern))
    (when (and minimum maximum pattern)
      (doseq [length [11 12 200 201]]
        (is (= (<= minimum length maximum)
               (boolean (home/valid-registration-password? (apply str (repeat length "x")))))))
      (doseq [name ["A" "Moss And Moon" "ゲーム" "Élan-2" "_Moss" "<bad>"
                    (apply str (repeat 32 "𐐀")) (apply str (repeat 33 "a"))]]
        (is (= (boolean (re-matches pattern name)) (home/valid-player-name? name)))))))

(deftest failed-submissions-preserve-only-safe-form-context
  (doseq [[label updates taken? claimed? expected-field]
          [["missing" {:password ""} false true "password"]
           ["name" {:player "_Moss"} false true "player"]
           ["short" {:password "short"} false true "password"]
           ["long" {:password (apply str (repeat 201 "x"))} false true "password"]
           ["blank" {:password (apply str (repeat 12 " "))} false true "password"]
           ["mismatch" {:password-confirm "different-test-passphrase"} false true "password-confirm"]
           ["duplicate" {} true true "player"]
           ["claim race" {} false false "player"]]]
    (testing label
      (let [params (merge valid-params updates)]
        (with-redefs [persist/player-has-password? (constantly taken?)
                      persist/claim-player-name! (constantly claimed?)
                      hashers/derive (constantly "test-hash")
                      layout/render (fn [_ template context] (assoc context :template template))]
          (let [result (home/register-submit ::db {:params params})]
            (is (= "organism/register.html" (:template result)))
            (is (= (:player params) (:player result)))
            (is (= (:redirect params) (:redirect result)))
            (is (= expected-field (:error-field result)))
            (is (string? (:error result)))
            (is (not (contains? result :password)))
            (is (not (contains? result :password-confirm)))
            (is (nil? (:session result)))))))))

(deftest failure-html-escapes-input-and-never-reflects-passwords
  (let [params (assoc valid-params :player "\"><script>alert(1)</script>")
        html (:body (home/register-submit ::db {:params params}))]
    (is (str/includes? html "&lt;script&gt;"))
    (is (not (str/includes? html "<script>alert(1)</script>")))
    (is (not (str/includes? html (:password params))))
    (is (str/includes? html "id=\"registration-error\""))
    (is (str/includes? html "role=\"alert\""))))

(deftest registration-links-encode-the-entire-safe-return-destination
  (let [target (:redirect valid-params)
        encoded (java.net.URLEncoder/encode target "UTF-8")
        html (:body (home/register-page {:query-params {"redirect" target}}))]
    (is (str/includes? html (str "href=\"/login?redirect=" encoded "\"")))
    (is (str/includes? html "value=\"/organism/play?table=Moss%20Garden&amp;mode=join\""))))

(deftest browser-normalization-cannot-turn-return-paths-into-external-urls
  (doseq [target [nil "https://example.invalid" "//example.invalid" "/\\example.invalid"
                  "/\t/example.invalid" "/\n/example.invalid" ["/organism"]]]
    (is (= "/" (home/safe-redirect target "Moss")) (pr-str target))))

(deftest legacy-other-game-registration-remains-unchanged
  (doseq [[target title] [["/journey/play" "JOURNEY"] ["/oroboros" "UNIVERSAL"]]]
    (let [html (:body (home/register-page {:query-params {"redirect" target}}))]
      (is (str/includes? html title))
      (is (str/includes? html "class=\"oval\""))
      (is (not (str/includes? html "/js/registration.js"))))))

(deftest valid-submission-still-hashes-claims-and-signs-in-canonical-account
  (let [claims (atom [])]
    (with-redefs [persist/player-has-password? (constantly false)
                  hashers/derive (fn [value] (is (= (:password valid-params) value)) "test-hash")
                  persist/claim-player-name! (fn [db player hash _]
                                              (swap! claims conj [db player hash]) true)
                  persist/canonical-player-name (constantly "Moss And Moon")]
      (let [result (home/register-submit ::db {:params valid-params})]
        (is (= 302 (:status result)))
        (is (= (:redirect valid-params) (get-in result [:headers "Location"])))
        (is (= {:player "Moss And Moon"} (:session result)))
        (is (= [[::db "Moss And Moon" "test-hash"]] @claims))))))
