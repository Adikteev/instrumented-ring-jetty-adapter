(ns mixradio.instrumented-jetty
  "Adapter for the Jetty webserver."
  (:require [environ.core :refer [env]]
            [metrics.core :refer [default-registry]]
            [metrics.timers :refer [timer]]
            [ring.util.servlet :as servlet])
  (:import [ch.qos.logback.access.jetty RequestLogImpl]
           [com.codahale.metrics Timer]
           [com.codahale.metrics.jetty9 InstrumentedConnectionFactory
                                        InstrumentedQueuedThreadPool
                                        InstrumentedHandler]
           [org.eclipse.jetty.http HttpVersion]
           [org.eclipse.jetty.server HttpConfiguration ConnectionFactory
                                     HttpConnectionFactory SslConnectionFactory
                                     ServerConnector Request
                                     Server SecureRequestCustomizer]
           [org.eclipse.jetty.server.handler RequestLogHandler HandlerCollection]
           [org.eclipse.jetty.util.component AbstractLifeCycle LifeCycle]
           [org.eclipse.jetty.util.ssl SslContextFactory]
           [org.eclipse.jetty.util.thread QueuedThreadPool ShutdownThread]))

(def common-rsa-ssl-protocols ["TLS_DHE_RSA_WITH_AES_128_CBC_SHA"
                               "SSL_DHE_RSA_WITH_3DES_EDE_CBC_SHA"
                               "TLS_RSA_WITH_AES_128_CBC_SHA"
                               "SSL_RSA_WITH_3DES_EDE_CBC_SHA"])

(gen-class
 :extends org.eclipse.jetty.server.handler.AbstractHandler
 :constructors {[java.util.Map] []}
 :init init
 :state handler
 :name mixradio.adapter.RequestHandler)

(def ^:dynamic request-log-enabled?
  (Boolean/valueOf (env :requestlog-enabled "false")))

(def ^:dynamic request-log-retain-hours
  (or (when-let [hours (env :requestlog-retainhours)] (Integer/valueOf hours))
      (when-let [days (env :requestlog-retaindays)] (* 24 (Integer/valueOf days)))
      72))

(defn -init
  [col]
  [[] (:handler col)])

(defn -handle
  [this _ ^Request base-request request response]
  (let [request-map (servlet/build-request-map request)
        response-map ((.-handler ^mixradio.adapter.RequestHandler this) request-map)]
    (when response-map
      (servlet/update-servlet-response response response-map)
      (.setHandled base-request true))))

(defn- instrumented-proxy-handler
  "Returns a Jetty Handler implementation that is instrumented for metrics"
  [handler]
  (doto (InstrumentedHandler. default-registry)
    (.setHandler (mixradio.adapter.RequestHandler. {:handler handler}))))

(defn- request-log-handler
  "A Jetty Handler that writes requests to a log file"
  []
  (System/setProperty "REQUESTLOG_RETAINHOURS" (str request-log-retain-hours))
  (let [request-log (doto (RequestLogImpl.)
                      (.setResource "/logback-access.xml"))]
    (doto (RequestLogHandler.)
      (.setRequestLog request-log))))

(defn- handlers
  "Return all the handlers for this Jetty instance"
  [handler]
  (let [handler-col (HandlerCollection.)]
    (.addHandler handler-col (instrumented-proxy-handler handler))
    (when request-log-enabled?
      (.addHandler handler-col (request-log-handler)))
    handler-col))

(defn- ssl-context-factory
  "Creates a new SslContextFactory instance from a map of options."
  [options]
  (let [context (SslContextFactory.)]
    (if (string? (options :keystore))
      (.setKeyStorePath context (options :keystore))
      (.setKeyStore context (options :keystore)))
    (.setKeyStorePassword context (options :key-password))
    (when (options :key-mgr-password)
      (.setKeyManagerPassword context (options :key-mgr-password)))
    (when (options :truststore)
      (if (string? (options :truststore))
        (.setTrustStorePath context (options :truststore))
        (.setTrustStore context (options :truststore))))
    (when (options :trust-password)
      (.setTrustStorePassword context (options :trust-password)))
    (.setIncludeCipherSuites context (into-array String (options :cipher-suites
                                                                 common-rsa-ssl-protocols)))
    (case (options :client-auth)
      :need (.setNeedClientAuth context true)
      :want (.setWantClientAuth context true)
      nil)
    context))

(defn- connection-timer
  "Creates a timer to record time taken to obtain connections from a connection factory"
  [name]
  (timer default-registry ["org"
                           "eclipse"
                           (format "jetty.server.%sConnectionFactory.new-connections" name)]))

(defn- http-config
  [options]
  (doto (HttpConfiguration.)
    (.setSendDateHeader true)
    (.setSendServerVersion (options :send-server-version false))))

(defn- ssl-connector
  "Creates a SSL server connector instance."
  [server options]
  (let [ssl-connection-factory (SslConnectionFactory. (ssl-context-factory options)
                                                      (.toString HttpVersion/HTTP_1_1))
        instr-conn-factory (InstrumentedConnectionFactory. ssl-connection-factory
                                                           (connection-timer "Ssl"))
        https-config (doto (HttpConfiguration.)
                       (.addCustomizer (SecureRequestCustomizer.)))
        http-conn-factory (HttpConnectionFactory. https-config)]
    (doto (ServerConnector. server (into-array ConnectionFactory [instr-conn-factory http-conn-factory]))
      (.setPort (options :ssl-port 443))
      (.setHost (options :host)))))

(defn- std-connector
  "Creates a standard server connector instance."
  [server options]
  (let [connection-factory (HttpConnectionFactory. (http-config options))
        instr-conn-factory (InstrumentedConnectionFactory. connection-factory
                                                           (connection-timer "Http"))]
    (doto (ServerConnector. server (into-array [instr-conn-factory]))
      (.setPort (options :port 80))
      (.setHost (options :host)))))

(defn- create-server
  "Construct a Jetty Server instance."
  [thread-pool options]
  (let [server (Server. thread-pool)]
    (.addConnector server (std-connector server options))
    (when (or (options :ssl?) (options :ssl-port))
      (.addConnector server (ssl-connector server options)))
    server))

(defn- life-cycle
  [{:keys [on-stop]}]
  (let [l (doto (proxy [AbstractLifeCycle] []
                  (doStop []
                    (on-stop)))
            (.start))]
    (ShutdownThread/register (into-array LifeCycle [l]))))

(defn ^Server run-jetty
  "Start a Jetty webserver to serve the given handler according to the
  supplied options:

  :configurator        - a function called with the Jetty Server instance
  :port                - the port to listen on (defaults to 80, note using a port
                         below 1024 requires running as root)
  :host                - the hostname to listen on
  :join?               - blocks the thread until server ends (defaults to true)
  :ssl?                - allow connections over HTTPS
  :ssl-port            - the SSL port to listen on (defaults to 443, implies :ssl?,
                         note using a port below 1024 requires running as root)
  :keystore            - path to the keystore to use for SSL connections, or an actual
                         keystore instance
  :key-password        - the password to the keystore
  :key-mgr-password    - the password for the key manager
  :truststore          - path to the truststore to use for SSL connections, or an actual
                         truststore instance
  :trust-password      - the password to the truststore
  :cipher-suites       - a list of cipher suites to use for SSL (instead of some default
                         values for RSA-based SSL)
  :max-threads         - the maximum number of threads to use (default 50)
  :client-auth         - SSL client certificate authenticate, may be set to :need,
                         :want or :none (defaults to :none)
  :on-stop             - A function to call on shutdown, before closing connectors
  :send-server-version - if true, the server version is sent in responses
                         (defaults to false)"
  [handler options]
  (let [
        ^QueuedThreadPool p (InstrumentedQueuedThreadPool. default-registry
                                                           (options :max-threads 254))
        ^Server s (create-server p (dissoc options :configurator))]
    (doto s
      (.setHandler (handlers handler)))
    (when-let [configurator (:configurator options)]
      (configurator s))
    (when (:on-stop options)
      (life-cycle options))
    (.start s)
    (when (:join? options true)
      (.join s))
    s))
