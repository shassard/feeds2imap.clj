(ns feeds2imap.settings
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.core.typed :refer [ann Any Set HMap U IFn]]
            [feeds2imap.gpg :refer [gpg]]
            [feeds2imap.logging :refer [info error]]
            [feeds2imap.types :refer :all]
            [feeds2imap.annotations :refer :all]
            [clojure.pprint :refer [pprint]])
  (:import  [java.io File]))

(ann default-config-dir [-> String])
(defn ^:private default-config-dir []
  (str (System/getenv "HOME") "/.config/feeds2imap.clj/"))

(ann config-dir [-> String])
(defn ^:private config-dir []
  (str (or (System/getenv "FEEDS2IMAP_HOME")
           (default-config-dir))))

(ann file [String -> File])
(defn ^File file [^String path]
  (File. path))

(ann bootstrap-config-dir [-> Any])
(defn ^:private bootstrap-config-dir []
  (let [file (file (config-dir))]
    (when-not (.exists file)
      (.mkdirs file))))

(ann bootstrap-file [String Any & :optional {:force Boolean} -> Any])
(defn ^:private bootstrap-file
  [path initial & {:keys [force] :or {force false}}]
  (let [file (file path)]
    (when (or force (not (.exists file)))
      (.createNewFile file)
      (spit path (str initial)))))

(ann ^:no-check read-or-create-file (IFn [String (Set String) -> Cache]
                                         [String (HMap) -> (Folder Urls)]
                                         [String (HMap) -> ImapConfiguration]
                                         [String String -> ImapConfiguration]))
(defn ^:private read-or-create-file [path initial]
  (let [path (str (config-dir) path)]
    (bootstrap-config-dir)
    (bootstrap-file path initial)
    (edn/read-string (slurp path))))

(ann read-encrypted-file [String -> (U ImapConfiguration Boolean)])
(defn ^:private read-encrypted-file [path]
  (bootstrap-config-dir)
  (let [path (str (config-dir) path)
        {:keys [out err exit]} (gpg "--quiet" "--batch"
                                    "--decrypt" "--" path)]
    (if (pos? exit)
      (do
        (error "Could not decrypt credentials from" path)
        (error err)
        (error "Make sure gpg is installed and works.")
        false)
      (edn/read-string out))))

(ann write-file [String (U String Cache (Folder Urls)) -> Any])
(defn ^:private write-file [path data]
  (bootstrap-config-dir)
  (bootstrap-file (str (config-dir) path) data :force true))

(ann read-items [-> (Set String)])
(defn read-items []
  (read-or-create-file "read-items.clj" (hash-set)))

(ann write-items [Cache -> Any])
(defn write-items [data]
  (info "Writing" (count data) "items to cache.")
  (write-file "read-items.clj" data))

(ann encrypted-imap [-> (U ImapConfiguration Boolean)])
(defn encrypted-imap []
  (read-encrypted-file "imap.clj.gpg"))

(ann unencrypted-imap [-> ImapConfiguration])
(defn unencrypted-imap []
  (read-or-create-file "imap.clj" (hash-map)))

(ann ^:no-check imap [-> ImapConfiguration])
(defn imap []
  (or (encrypted-imap)
      (unencrypted-imap)))

(ann urls (IFn [-> (Folder Urls)]
               [(Folder Urls) -> Any]))
(defn urls
  ([] (read-or-create-file "urls.clj" (hash-map)))
  ([data] (write-file "urls.clj" (with-out-str (pprint data)))))
