(ns strucjure.pattern
  (:require [clojure.set :refer [union]]
            [strucjure.util :refer [when-nil let-syms free-syms]]))

;; TODO Record
;; TODO Set? (how would you match subpatterns? maybe only allow bind/with-meta? or only value patterns)
;; TODO Atom/Ref/Agent? (what would the output be?)
;; TODO when gen is added, pattern->clj will be a poor name
;; TODO need a general way to indicate that output is unchanged for eg WithMeta
;;      just check (= input output)?
;; TODO need to be careful about reusing input - a (let-sym [input `(meta input)] ...) would be useful here
;;      let-syms -> with-syms, then use let-sym
;; TODO pattern debugger via *pattern->clj*
;; TODO think carefully about seq vs list
;; TODO might have to rething Rest - is frequently ugly eg in sugar
;; TODO could return to having implicit equality but would require careful thinking about Guard/Output
;;      state needs to track 'has it been bound before' and 'will it need to be bound again' - not exclusive

(defprotocol IPattern
  (pattern->clj [this input output? state result->body]
    "Compile a pattern into clojure which returns nil on failure or hands control to result->body on success.
     input -- form, input to the pattern
     output? -- bool, whether the output of this pattern is used anywhere
     state -- {symbol :bound/:free}, :bound symbols are in scope already, :free symbols are used somewhere in the body
     result->body -- (fn [output remaining] form), returns the body to be evaluated on success, should be called *exactly* once"))

;; --- REST ---

(defrecord Rest [pattern]
  IPattern
  (pattern->clj [this input output? state result->body]
    (throw (Exception. (pr-str "Compiling strucjure.pattern.Rest outside of seq: " this)))))

(defn head->clj [pattern input output? state result->body]
  (if (instance? Rest pattern)
    (pattern->clj (:pattern pattern) input output? state result->body)
    (let-syms [first-input rest-input]
              `(when-let [[~first-input & ~rest-input] ~input]
                 ~(pattern->clj pattern first-input output? state
                                (fn [output remaining state]
                                  (when-nil remaining
                                            (result->body output rest-input state))))))))

(defn cons->clj [pattern first rest]
  (if (instance? Rest pattern)
    `(concat ~first ~rest)
    `(cons ~first ~rest)))

(defn conj->clj [pattern last rest]
  (if (instance? Rest pattern)
    `(apply conj ~rest ~last)
    `(conj ~rest ~last)))

;; --- VALUE PATTERNS ---

(defn seq->clj* [patterns input output? state result->body]
  (if-let [[first-pattern & rest-pattern] (seq patterns)]
    (head->clj first-pattern input output? state
               (fn [first-output first-remaining state]
                 (seq->clj* rest-pattern first-remaining output? state
                            (fn [rest-output rest-remaining state]
                              (result->body (cons->clj first-pattern first-output rest-output) rest-remaining state)))))
    (result->body nil input state)))

(defn seq->clj [patterns input output? state result->body]
  (let-syms [seq-input]
            `(let [~seq-input (seq ~input)]
               ~(seq->clj* patterns seq-input output? state result->body))))

(defn vec->clj [patterns index input output? state result->body]
  (if (< index (count patterns))
    (pattern->clj (nth patterns index) `(nth ~input ~index) output? state
                  (fn [index-output index-remaining state]
                    (when-nil index-remaining
                              (vec->clj patterns (inc index) input output? state
                                        (fn [vec-output vec-remaining state]
                                          (result->body (vec (cons index-output vec-output)) vec-remaining state))))))
    (result->body [] `(seq (subvec ~input ~index)) state)))

(defn map->clj [patterns input output? state result->body]
  (if-let [[[key value-pattern] & rest-pattern] (seq patterns)]
    (pattern->clj value-pattern `(get ~input ~key) output? state
                  (fn [value-output value-remaining state]
                    (when-nil value-remaining
                              (map->clj rest-pattern input output? state
                                        (fn [rest-output _ state]
                                          (result->body `(assoc ~rest-output ~key ~value-output) nil state))))))
    (result->body input nil state)))

(extend-protocol IPattern
  nil
  (pattern->clj [this input output? state result->body]
    `(when (nil? ~input)
       ~(result->body nil nil state)))
  Object
  (pattern->clj [this input output? state result->body]
    `(when (= ~input '~this)
       ~(result->body input nil state)))
  clojure.lang.ISeq
  (pattern->clj [this input output? state result->body]
    `(when (seq? ~input)
       ~(seq->clj this input output? state result->body)))
  clojure.lang.IPersistentVector
  (pattern->clj [this input output? state result->body]
    `(when (vector? ~input)
       ~(if (some #(instance? Rest %) this)
          (seq->clj this input output? state
                    (fn [output remaining state] (result->body `(vec ~output) remaining state)))
          `(when (>= (count ~input) ~(count this))
             ~(vec->clj this 0 input output? state result->body)))))
  clojure.lang.IPersistentMap
  (pattern->clj [this input output? state result->body]
    `(when (instance? clojure.lang.IPersistentMap ~input)
       ~(map->clj this input output? state result->body))))

(defrecord Seqable [patterns]
  IPattern
  (pattern->clj [this input output? state result->body]
    `(when (instance? clojure.lang.Seqable ~input)
       ~(seq->clj patterns input output? state result->body))))

;; --- LOGICAL PATTERNS ---

(defrecord Any []
  IPattern
  (pattern->clj [this input output? state result->body]
    (result->body input nil state)))

(defrecord Is [form]
  IPattern
  (pattern->clj [this input output? state result->body]
    `(when (let [~'&input ~input] ~form)
       ~(result->body input nil state))))

(defrecord Guard [pattern syms form]
  IPattern
  (pattern->clj [this input output? state result->body]
    (pattern->clj pattern input output?
                  (apply assoc state (interleave syms (repeat :free)))
                  (fn [output remaining state]
                    (assert (every? #(= :bound (state %)) syms)
                            (pr-str "All free variables in the guard must be bound in the enclosed pattern:" this state syms))
                    `(when ~form
                       ~(result->body output remaining state))))))

(defrecord Bind [symbol pattern]
  IPattern
  (pattern->clj [this input output? state result->body]
    (if (= :free (state symbol))
      (pattern->clj pattern input true
                    (assoc state symbol :bound)
                    (fn [output remaining state]
                      `(let [~symbol ~output]
                         ~(result->body symbol remaining state))))
      (pattern->clj pattern input output? state result->body))))

(defrecord Output [pattern syms form]
  IPattern
  (pattern->clj [this input output? state result->body]
    (pattern->clj (->Bind '&output pattern) input false
                  (reduce #(assoc %1 %2 :free) state syms)
                  (fn [_ remaining state]
                    (assert (every? #(= :bound (state %)) syms)
                            (pr-str "All free variables in the output must be bound in the enclosed pattern:" this state syms))
                    (result->body form remaining state)))))

(defn bound-since [old-state new-state]
  (for [key (keys new-state)
        :when (= :bound (new-state key))
        :when (not= :bound (old-state key))]
    key))

(defrecord Or [patterns]
  IPattern
  (pattern->clj [this input output? state result->body]
    (assert patterns (pr-str "Or cannot be empty: " this))
    (let [states (atom #{})
          branches (doall (for [pattern patterns]
                            (pattern->clj pattern input output? state
                                          (fn [output remaining state']
                                            (swap! states conj state')
                                            (apply vector output remaining (bound-since state state'))))))
          ;; TODO will need some kind of merge here if we allow other things in state
          _ (assert (= 1 (count @states)) (pr-str "All patterns in Or must have the same set of bindings: " this @states))
          state' (first @states)]
      (let-syms [output remaining]
                `(when-let [~(apply vector output remaining (bound-since state state')) (or ~@branches)]
                   ~(result->body output remaining state'))))))

(defrecord And [patterns]
  IPattern
  (pattern->clj [this input output? state result->body]
    (assert patterns (pr-str "And cannot be empty: " this))
    (let [[first-pattern & rest-pattern] (seq patterns)]
      (if rest-pattern
        (pattern->clj first-pattern input output? state
                      (fn [_ _ state] (pattern->clj (->And rest-pattern) input output? state result->body)))
        (pattern->clj first-pattern input output? state result->body)))))

(defrecord ZeroOrMore [pattern]
  IPattern
  (pattern->clj [this input output? state result->body]
    (let-syms [loop-output loop-remaining output remaining]
              (let [binding (if output? [output remaining] [remaining])
                    output-acc (when output? (conj->clj pattern output loop-output))
                    states (atom #{})
                    loop-body (head->clj pattern loop-remaining output? state
                                         (fn [output remaining state']
                                           (swap! states conj state')
                                           (if output? [output remaining] [remaining])))
                    _ (assert (= 1 (count @states)) (pr-str "result->body should be called exactly once: " pattern))
                    state' (first @states)]
                `(when (or (nil? ~input) (seq? ~input))
                   (loop [~loop-output [] ~loop-remaining (seq ~input)]
                     (if-let [~binding (and ~loop-remaining ~loop-body)]
                       (recur ~output-acc ~remaining)
                       ~(result->body `(seq ~loop-output) loop-remaining state'))))))))

(defrecord WithMeta [pattern meta-pattern]
  IPattern
  (pattern->clj [this input output? state result->body]
    (let-syms [input-meta]
              (pattern->clj pattern input output? state
                            (fn [output remaining state]
                              (pattern->clj meta-pattern `(meta ~input) output? state
                                            (fn [meta-output meta-remaining state]
                                              (when-nil meta-remaining
                                                        (result->body
                                                         `(if (nil? ~meta-output) ~output (with-meta ~output ~meta-output))
                                                         remaining state)))))))))

(defrecord View [form]
  IPattern
  (pattern->clj [this input output? state result->body]
    (let-syms [view-output view-remaining]
              `(when-let [[~view-output ~view-remaining] (~form ~input)]
                 ~(result->body view-output view-remaining state)))))

(defn pattern->view [pattern]
  (let-syms [input]
            `(fn [~input]
               ~(pattern->clj pattern input true {} (fn [output remaining _] [output remaining])))))

(comment
  (use 'clojure.stacktrace)
  (e)
  (pattern->view (->Bind 1 'a))
  (pattern->view (->Output (->Bind 1 'a) '(+ a 1)))
  (pattern->view (list 1 2))
  ((eval (pattern->view (list 1 2))) (list 1 2))
  ((eval (pattern->view (list 1 2))) (list 1))
  ((eval (pattern->view (list 1 2))) (list 1 2 3))
  ((eval (pattern->view (list 1 2))) (list 1 3))
  ((eval (pattern->view (list 1 2))) [1 2])
  ((eval (pattern->view (list 1 2))) 1)
  (let [a (eval (pattern->view 1))] (pattern->view (list (->View a) 2)))
  (let [a (eval (pattern->view 1))] ((eval (pattern->view (list (->View a) 2))) (list 1 2 3)))
  ((eval (pattern->view (list))) (list))
  ((eval (pattern->view (list))) (list 1 2))
  ((eval (pattern->view (list))) nil)
  ((eval (pattern->view (->ZeroOrMore 1))) (list))
  ((eval (pattern->view (->ZeroOrMore 1))) (list 2))
  ((eval (pattern->view (->ZeroOrMore 1))) (list 1 1))
  ((eval (pattern->view (->ZeroOrMore 1))) (list 1 1 2))
  ((eval (pattern->view (->ZeroOrMore (->Or [1 2])))) (list 1 2 1 2 3))
  (pattern->view (->ZeroOrMore 1))
  (pattern->view (->Output (->ZeroOrMore 1) ''ones))
  (pattern->view (->Output (->Bind (->ZeroOrMore 1) 'a) 'a))
  (let [[out rem] ((eval (pattern->view (->WithMeta (->Any) {:foo true}))) ^:foo [])]
    (meta out))
  ((eval (pattern->view [1 2])) [1])
  ((eval (pattern->view [1 2])) [1 2])
  ((eval (pattern->view [1 2])) [1 2 3])
  ((eval (pattern->view [1 2])) [1 3])
  (pattern->view [1 2 (->Bind (->Any) 'a)])
  (pattern->view (->Output [1 2 (->Bind (->Any) 'a)] 'a))
  (pattern->view [1 2 (->Rest (->Any))])
  (pattern->view {1 2 3 (->Bind (->Any) 'a)})
  (pattern->view (->Output {1 2 3 (->Bind (->Any) 'a)} 'a))
  ((eval (pattern->view (list (->Rest (->Bind 'elems (->ZeroOrMore (->Rest (->Any)))))))) (list 1 2 3))
  ((eval (pattern->view (->Seqable [(->Rest (->ZeroOrMore [(->Any) (->Any)]))]))) '{:foo 1 :bar (& * 3)})
  ((eval (pattern->view (->And [{} (->Bind 'elems (->Seqable [(->Rest (->ZeroOrMore [(->Any) (->Any)]))]))]))) '{:foo 1 :bar (& * 3)})
  ((eval (pattern->view [(->Any) (->Any)])) (first (seq '{:foo 1 :bar (& * 3)})))
  (eval (pattern->view (->Output (list (->WithMeta (->Bind 'prefix (->Or ['*])) (->Any)) (->Rest (->View 'inc))) 'prefix)))
  ((eval (pattern->view (->Output (->Bind 'a (list 1 (->Bind 'a 2))) 'a)))) (list 1 2)
  )
