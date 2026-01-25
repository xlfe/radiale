(ns radiale.test-runner
  "Test runner for Clojure tests."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :as test]))

(defn find-test-namespaces
  "Find all test namespaces in the test directory."
  []
  (->> (file-seq (io/file "test"))
       (filter #(str/ends-with? (.getName %) "_test.clj"))
       (map
         (fn [f]
           (-> (.getAbsolutePath f)
               (str/replace #"^.*/test/" "")
               (str/replace #"\.clj$" "")
               (str/replace "/" ".")
               (str/replace "_" "-")
               symbol)))))

(defn -main
  [& _args]
  (let [test-nss (find-test-namespaces)]
    (println "Loading test namespaces:" test-nss)
    (doseq [ns-sym test-nss]
      (require ns-sym))
    (let [results (apply test/run-tests test-nss)]
      (println
        (str
          "\nTests: "
          (:test results)
          ", Assertions: "
          (:pass results)
          ", Failures: "
          (:fail results)
          ", Errors: "
          (:error results)))
      (System/exit
        (if (and
              (zero? (:fail results))
              (zero? (:error results)))
          0
          1)))))
