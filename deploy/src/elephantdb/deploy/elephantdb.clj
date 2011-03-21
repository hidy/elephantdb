(ns elephantdb.deploy.elephantdb
  (:import org.antlr.stringtemplate.StringTemplate)
  (:use 
   [pallet thread-expr]
   [pallet.resource
    [package :only [package]]
    [exec-script :only [exec-script]]
    [remote-file :only [remote-file]]
    [directory :only [directory, directories]]]
   [pallet.execute :only [local-script]]
   [pallet.crate
    [java :only [java]]]))

(def local-template-dir "templates/")

(def service-dir "/service/elephantdb/")
(def s3-configs-dir "/configs/elephantdb/")
(def service-subdirs (map (partial str service-dir)
                          ["releases" "shared" "log"]))

;;/configs/elephantdb/dev/global-conf.clj 1300415760
(defn- template-context [req]
  {"GLOBALCONF" (format "%s/%s/global-conf.clj" s3-configs-dir (:ring req))
   "TOKEN" (int (/ (System/currentTimeMillis) 1000))})

(defn make-release! []
  (let [filename "../release.tar.gz"]
    (local-script
     (cd "dist/")
     (rm -f ~filename)
     (tar cvzf ~filename "."))))

(defn- render-template! [template-path context]
  (let [template (StringTemplate. (slurp template-path))]
    (doseq [[k v] context]
      (.setAttribute template k v))
    (str template)))

(defn render-remote-file! [req rel-path]
  (let [dst-path (str service-dir rel-path)
        src-path (str local-template-dir rel-path)
        render (render-template! src-path (template-context req))]
    (-> req
        (remote-file dst-path :content render :mode 744))))

(defn setup [req]
  (-> req
      (java :sun :jdk)
      (directories service-subdirs :action :create)
      (render-remote-file! "run")
      (render-remote-file! "log/run")))

(defn deploy [req {:keys [new-token] :or {:new-token true}}]
  (let [time (System/currentTimeMillis)
        releases-dir (str service-dir "/releases/")

        new-release-dir (str releases-dir time)
        current-sym-link (str releases-dir "current")
        new-release-file (str new-release-dir "/release.tar.gz")
        local-conf-file (str new-release-dir "/local-conf.clj")]
    (-> req
        (directory new-release-dir :action :create)
        (remote-file new-release-file :local-file "release.tar.gz")
        (if-> new-token
              (render-remote-file! "shared/run-elephant.sh"))
        (exec-script
         (cd ~new-release-dir)
         (tar xvzfop ~new-release-file)
         (rm -f "run-elephant.sh")
         (ln -sf "../../shared/run-elephant.sh" "."))
        (remote-file ~local-conf-file :content )
        (exec-script
         (rm -f ~current-sym-link)
         (ln -sf ~new-release-dir ~current-release-dir)))))

