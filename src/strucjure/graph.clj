(ns strucjure.graph
  (:require [plumbing.core :refer [fnk for-map map-vals aconcat]]
            [strucjure.pattern :as pattern]))

;; TODO get-in with-deepest-error
;; TODO call stack may become a problem
;; TODO allow parts of the graph to take args eg bindable in sugar
;; TODO shouldnt need 30 lines for tracing :(
;; TODO will later need some way to track dependencies between nodes

(defn with-binding [graph var val]
  (vary-meta graph clojure.core/update-in [::bindings] #(assoc % var val)))

(defn output-in [graph & names&fnks]
  (apply assoc graph
         (aconcat (for [[name fnk] (partition 2 names&fnks)]
                    [name (pattern/->Output (graph name) fnk)]))))

(defn with-named-nodes [graph]
  (with-meta
    (for-map [[name pattern] graph] name (pattern/->Bind name pattern))
    (meta graph)))

(defn graph->view [name graph]
  `(letfn [~@(for [[name pattern] graph] (pattern/pattern->view name pattern))]
     (fn [input#]
       (binding [~@(aconcat (::bindings (meta graph)))]
         (~name input#)))))

(defrecord Trace [pattern name enter-fn exit-fn]
  strucjure.pattern.IPattern
  (pattern->clj [this input output? state result->body]
    `(let [~'_ (~enter-fn '~name ~input)
           result# ~(pattern/*pattern->clj* pattern input output? state result->body)
           ~'_ (~exit-fn '~name result#)]
       result#)))

(defn with-trace [graph enter-fn exit-fn]
  (with-meta
    (for-map [[name pattern] graph] name (->Trace pattern name enter-fn exit-fn))
    (meta graph)))

(def ^:dynamic *depth*)

(defn- indent [n]
  (apply str (repeat (* 4 n) \ )))

(defn print-enter [name input]
  (println (indent *depth*) "=>" name input)
  (set! *depth* (inc *depth*))
  input)

(defn print-exit [name result]
  (set! *depth* (dec *depth*))
  (if result
    (println (indent *depth*) "<=" name result)
    (println (indent *depth*) "X" name))
  result)

(defn with-print-trace [graph]
  (-> graph (with-binding `*depth* 0) (with-trace print-enter print-exit)))
