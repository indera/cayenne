(ns cayenne.user
  (:require [cayenne.conf :as conf]
            [cayenne.api.route :as route]
            [cayenne.action :as action]
            [taoensso.timbre.appenders.irc :as irc-appender]
            [taoensso.timbre :as timbre]))

(timbre/set-config! [:appenders :standard-out :enabled?] false)
(timbre/set-config! [:appenders :spit :enabled?] true)
(timbre/set-config! [:shared-appender-config :spit-filename] "log/log.txt")

(conf/create-core-from! :user :default)

(conf/with-core :user
  (conf/set-param! [:env] :user))

(conf/set-core! :user)

(conf/start-core! :user)
