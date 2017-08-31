(ns contentful.test-runner
  (:require
   [doo.runner :refer-macros [doo-tests]]
   [contentful.core-test]
   [contentful.common-test]))

(enable-console-print!)

(doo-tests 'contentful.core-test
           'contentful.common-test)
