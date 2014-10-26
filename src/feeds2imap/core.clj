(ns feeds2imap.core
  (:gen-class)
  (:require [feeds2imap.feeds :as feeds]
            [feeds2imap.settings :as settings]
            [feeds2imap.imap :as imap]
            [feeds2imap.folder :as folder]
            [feeds2imap.macro :refer :all]
            [feeds2imap.opml :as ompl]
            [feeds2imap.logging :refer [info error]]
            [feeds2imap.annotations :refer :all]
            [clojure.pprint :refer [pprint]]
            [clojure.core.typed :refer [ann Any Keyword]]
            [clojure.core.match :refer [match]])
  (:import [java.net NoRouteToHostException UnknownHostException]
           [javax.mail MessagingException]
           [java.io File]
           [java.lang NullPointerException]))

(set! *warn-on-reflection* true)

(ann ^:no-check pull [-> Any])
(defn pull []
  (try*
    (let [{:keys [username password host port to from]} (settings/imap)
          cache         (settings/read-items)
          _             (info "Found" (count cache) "items in cache.")
          urls          (settings/urls)
          _             (info "Found" (count urls) "folders in urls.")
          {:keys [new-items cache]} (feeds/new-items cache urls)
          _             (info "Found" (count new-items)
                              "folder(s) with" (->> new-items (map second) flatten count)
                              "new item(s) in total.")
          imap-session  (imap/get-session (imap/get-props) nil)
          imap-store    (imap/get-store imap-session)
          emails        (feeds/to-emails imap-session from to new-items)]
      (when-not (empty? new-items)
        (with-open [store imap-store]
          (info "Connecting to imap host.")
          (imap/connect store host port username password)
          (info "Appending emails.")
          (folder/append-emails store emails)
          (info "Updating cache.")
          (settings/write-items cache))))
      (catch* [UnknownHostException NoRouteToHostException MessagingException] e
              (info "Exception in pull" e))))

(ann sleep [Long -> nil])
(defn sleep [ms]
  (Thread/sleep ms))

(ann ^:no-check pull-with-catch [-> Any])
(defn pull-with-catch []
  (info "Running pull in future")
  (try
    (pull)
    (catch Exception e
      (info "Exception in pull call inside auto" e))))

(ann ^:no-check auto [-> Any])
(defn auto []
  (loop [previous-task nil]
    (when (and (future? previous-task)
               (not (future-done? previous-task)))
      (info "Cancelling previous future")
      (future-cancel previous-task))
    (let [delay-str (System/getenv "DELAY")
          minutes (if delay-str (Integer. delay-str) 60)
          current-task (future-call pull-with-catch)]
      (info "Sleeping in auto for" minutes "minutes")
      (sleep (* minutes 60 1000))
      (recur current-task))))

(ann add [Keyword String -> Any])
(defn add [folder url]
  (let [folder (keyword folder)
        urls (settings/urls)
        folder-urls (or (get urls folder) [])]
    (settings/urls (assoc urls folder (conj folder-urls url)))))

(ann ^:no-check shutdown-agents-with-try [-> Any])
(defn shutdown-agents-with-try []
  (try*
    (shutdown-agents)
    (catch* [NullPointerException] e
            (info "Exception while shuting down agents" e))))

(ann show [-> nil])
(defn show [] (pprint (settings/urls)))

(ann ^:no-check -main [Any -> Any])
(defn -main
  [& args]
  (match args
    [([] :seq)] (pull)
    [(["pull"] :seq)] (pull)
    [(["show"] :seq)] (show)
    [(["auto"] :seq)] (auto)
    [(["imap" "encrypt"]   :seq)] (settings/encrypt-imap!)
    [(["imap" "decrypt"]   :seq)] (settings/decrypt-imap!)
    [(["opml2clj" file]    :seq)] (ompl/convert-and-print-from-file!)
    [(["add" folder url]   :seq)] (do (add folder url) (show))
    [(["ompl2clj" from to] :seq)] (ompl/convert-and-write-to-file!)
    :else (error "Can't handle arguments" args)))
