(defproject luchiniatwork/contentql "0.1.1"
  :description "Access to Contentful content using Om Next Queries"
  :url "https://github.com/luchiniatwork/contentql"
  :license {:name "MIT License"
            :url "https://opensource.org/licenses/MIT"}

  :dependencies [[camel-snake-kebab "0.4.0"]
                 [clj-http "3.7.0"]
                 [cljs-http "0.1.43"]
                 [cheshire "5.8.0"]
                 [environ "1.1.0"]
                 [org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.908" :scope "provided"]
                 [org.clojure/core.async "0.3.443"]
                 [org.omcljs/om "1.0.0-beta1"]
                 [org.clojure/tools.namespace "0.2.11"]]

  :plugins [[lein-cljsbuild "1.1.6"]
            [lein-environ "1.1.0"]]

  :min-lein-version "2.6.1"

  :source-paths ["src/clj" "src/cljs" "src/cljc"]

  :test-paths ["test/clj" "test/cljc"]

  :clean-targets ^{:protect false} [:target-path :compile-path "resources/public/js"]

  :uberjar-name "contentql.jar"

  ;; nREPL by default starts in the :main namespace, we want to start in `user`
  ;; because that's where our development helper functions like (go) and
  ;; (browser-repl) live.
  :repl-options {:init-ns user}

  :cljsbuild {:builds
              [{:id "app"
                :source-paths ["src/cljs" "src/cljc" "dev"]

                :figwheel {:on-jsload "contentql.system/reset"}

                :compiler {:main cljs.user
                           :asset-path "js/compiled/out"
                           :output-to "resources/public/js/compiled/contentql.js"
                           :output-dir "resources/public/js/compiled/out"
                           :source-map-timestamp true}}

               {:id "test"
                :source-paths ["src/cljs" "test/cljs" "src/cljc" "test/cljc"]
                :compiler {:output-to "resources/public/js/compiled/testable.js"
                           :main contentql.test-runner
                           :optimizations :none}}

               {:id "min"
                :source-paths ["src/cljs" "src/cljc"]
                :jar true
                :compiler {:main contentql.system
                           :output-to "resources/public/js/compiled/contentql.js"
                           :output-dir "target"
                           :source-map-timestamp true
                           :optimizations :advanced
                           :pretty-print false}}]}

  :profiles {:dev
             {:source-paths ["dev"]
              :dependencies [[figwheel "0.5.13"]
                             [figwheel-sidecar "0.5.13"]
                             [com.cemerick/piggieback "0.2.2"]
                             [org.clojure/tools.nrepl "0.2.13"]
                             [reloaded.repl "0.2.3"]]
              :plugins [[lein-figwheel "0.5.10"]]
              :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}}

             :uberjar
             {:source-paths ^:replace ["src/clj" "src/cljc"]
              :prep-tasks ["compile"
                           ["cljsbuild" "once" "min"]]
              :hooks []
              :omit-source true
              :aot :all}})
