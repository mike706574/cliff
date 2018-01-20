(ns cliff.alpha.core
  (:require [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [com.stuartsierra.component :as component]
            [taoensso.timbre :as log]))

(defonce cmds (atom {}))

(defmacro defcmd
  [symbol description arg-list & body]
  `(do
     (def ~symbol
       (with-meta
         (fn ~arg-list
           ~(conj body `do))
         {:args ~(mapv name (rest arg-list))}))
     (swap! cmds #(assoc % ~(name symbol) {:description ~description :function ~symbol}))))

(defn pretty [value] (with-out-str (pprint value)))

(defn usage [opts] (:summary (cli/parse-opts [] opts)))

(defn cmd-listing [cmds]
  (let [max-cmd-len (apply max (map #(count (key %)) cmds))
                       command-list (fn [commands]
                                       (str/join "\n"
                                             (for [[id spec] (sort-by :key commands)]
                                               (format (str "   %-" max-cmd-len "s   %s") id (:description spec)))))
                       sorted-commands (into (sorted-map) cmds)]
                   (str "Available commands:\n"
                        (command-list sorted-commands))))

(defn help [label cmds opts]
  (str (usage opts) "\n\n"
       (cmd-listing cmds)
       "\n\n"
       "See '" label " help <command>' for command arguments and more detail."))

(defn cli
  [label system opts args]
  (try
    (let [cmds @cmds
          {:keys [options arguments errors]} (cli/parse-opts args opts :in-order true)
          path (first arguments)]
      (log/set-level! (if (:verbose options) :debug :info))
      (log/debug "All arguments:" args)
      (log/debug "Options:" options)
      (log/debug "Arguments:" arguments)
      (log/debug "Errors:" errors)
      (let [system (component/start-system (system options))]
        (log/debug (str "System:\n" (pretty system)))
        (try
          (let [cmd-usage (fn [cmd-id {:keys [function]}] (str "usage: " label " " cmd-id " " (str/join " " (map #(str "<" % ">") (:args (meta function))))))
                cmd-rejection (fn [cmd-id] (str label ": '" cmd-id "' is not a valid command. See '" label " --help'."))
                cmd-help (fn [cmd-id]
                           (if-let [spec (get cmds cmd-id)]
                             (cmd-usage cmd-id spec)
                             (cmd-rejection cmd-id)))]
            (cond
              (not (empty? errors)) (println (str (str/join \newline errors) "\n\n" usage))
              (empty? arguments) (println (help label cmds opts))
              (:help options) (println (if (empty? arguments)
                                         help
                                         (cmd-help (first arguments))))
              (= "help" (first arguments)) (println (if (= 1 (count arguments))
                                                      help
                                                      (cmd-help (second arguments))))
              :else (let [[cmd-id & cmd-args] arguments]
                      (if-let [spec (get cmds cmd-id)]
                        (let [cmd-fn (:function spec)]
                          (if (= (count cmd-args) (count (:args (meta cmd-fn))))
                            (apply cmd-fn (cons system cmd-args))
                            (do (println (cmd-usage cmd-id spec))
                                false)))
                        (println (cmd-rejection cmd-id))))))
          (finally (component/stop-system system)))))))
