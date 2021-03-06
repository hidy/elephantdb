(ns elephantdb.deploy.crate.edb-configs
  (:require [pallet.request-map :as rm])
  (:require [pallet.session :as ss])
  (:use
   [pallet.compute]
   [org.jclouds.blobstore :only [upload-blob]]
   [pallet.session :only [nodes-in-group]]
   [pallet.configure :only [pallet-config]]
   [pallet.resource.remote-file :only [remote-file]]
   [clojure.contrib.map-utils :only [deep-merge-with]]))

(defn read-global-conf! [ring]
  (let [path (format "conf/%s/global-conf.clj" ring)]
    (read-string (slurp path))))

(defn read-local-conf! [ring]
  (let [path (format "conf/%s/local-conf.clj" ring)]
    (read-string (slurp path))))

;; HACK: Construct ec2 internal hostname from internal ip.
;;       Needed until Nathan allows internal ip in global-conf.clj
(defn internal-hostname [node]
  (let [ip (private-ip node)]
    (str "ip-" (apply str (interpose "-" (re-seq #"\d+" ip)))
         ".ec2.internal"
         )))

(defn- global-conf-with-hosts [req local-config]
  (let [hosts (map internal-hostname (ss/nodes-in-group req))]
    (prn-str (assoc local-config :hosts hosts))))

(defn upload-global-conf! [req]
  (let [local-conf (read-global-conf! (:ring req))
        s3-conf (global-conf-with-hosts req local-conf)
        s3-key (format "configs/elephantdb/%s/global-conf.clj" (:ring req))]
    (upload-blob "hdfs2" s3-key s3-conf (:blobstore req))
    req))

(defn local-conf-with-keys [req local-conf]
  (let [{:keys [edb-s3-keys]} req]
    (deep-merge-with #(identity %2)
     {:hdfs-conf 
      {"fs.s3n.awsAccessKeyId" (:identity edb-s3-keys)
       "fs.s3n.awsSecretAccessKey" (:credential edb-s3-keys)}}
     local-conf)))

(defn remote-file-local-conf! [req dst-path]
  (let [conf (read-local-conf! (:ring req))
        conf-with-keys (local-conf-with-keys req conf)]
    (-> req
        (remote-file dst-path :content (prn-str conf-with-keys)))))

