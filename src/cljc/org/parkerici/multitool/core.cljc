(ns org.parkerici.multitool.core
  "Various generally useful utilities"
  (:require
   [clojure.string :as str]
   [clojure.set :as set]
   [clojure.walk :as walk]
   ))

;;; ⩇⩆⩇ Memoization ⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇

;;; See https://github.com/clojure/core.memoize/ for more memoize hacks
(defmacro defn-memoized
  "Like `defn`, but produces a memoized function"
  [name args & body]
  ;; This crock is because you can't have multiple varadic arg signatures...sigh
  (if (string? args)
    `(def ~name ~args (memoize (fn ~(first body) ~@(rest body))))
    `(def ~name (memoize (fn ~args ~@body)))))

(defmacro def-lazy
  "Like `def` but produces a delay; value is acceessed via @ and won't be computed until needed"
  [var & body]
  `(def ~var (delay ~@body)))

;;; ⩇⩆⩇ Exception handling ⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇

(defmacro ignore-errors
  "Execute `body`; if an exception occurs ignore it and return `nil`. Note: strongly deprecated for production code."
  [& body]
  `(try (do ~@body)
        (catch #?(:clj Throwable :cljs :default) e# nil)))

(defmacro ignore-report
  "Execute `body`, if an exception occurs, print a message and continue"
  [& body]
  `(try (do ~@body)
        (catch #?(:clj Throwable :cljs :default) e# (println (str "Ignored error: " (.getMessage e#))))))

(defn error-handling-fn
  "Returns a fn that acts like f, but return value is (true result) or (false errmsg) in the case of an error"
  [f]
  (fn [& args]
    (try
      (let [res (apply f args)]
        (list true res))
      (catch  #?(:clj Throwable :cljs :default) e
        (list false (str "Caught exception: " e))))))

;;; ⩇⩆⩇ Strings ⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇

(defn coerce-numeric
  "Attempt to turn thing into a number (long or double).
  Return number if succesful, otherwise original string"
  [thing]
  (cond
    (number? thing) thing
    (nil? thing) nil
    (and (string? thing) (not (empty? thing)))
    (if-let [inum (re-matches #"-?\d+" thing)]
      (try
        #?(:cljs (js/parseInt inum)
           :clj (Long. inum))
        (catch #?(:clj Throwable :cljs :default)  _ thing))
      (if-let [fnum (re-matches #"-?\d*\.?\d*" thing)]
        (try
          #?(:cljs (js/parseFloat fnum)
             :clj (Double. fnum))
          (catch #?(:clj Throwable :cljs :default) _ thing))
        thing))
    true thing))

(defn coerce-numeric-hard
  "Coerce thing to a number if possible, otherwise return nil"
  [thing]
  (let [n (coerce-numeric thing)]
    (and (number? n) n)))

(defn coerce-boolean
  "Coerce a value (eg a string from a web API) into a boolean"
  [v]
  (if (string? v)
    (= v "true")
    (not (nil? v))))

;;; For more conversions like this, use https://clj-commons.org/camel-snake-kebab/
(defn underscore->camelcase
  "Convert foo_bar into fooBar"
  [s]
  (let [parts (str/split s #"_")]
    (apply str (first parts) (map str/capitalize (rest parts)))))

(defn labelize
  "Convert - and _ to spaces"
  [s]
  (str/replace (name s) #"[\_\-]" " "))

;;; ⩇⩆⩇ Regex and templating ⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇

(defn re-find-all
  "Find all matches in a string."
  [re s]
  #?(:clj
     (let [m (re-matcher re s)
           collector (atom [])]            ;TODO better to use transients
       (while (.find m)
         (swap! collector conj (re-groups m)))
       @collector)
     :cljs
     (throw (ex-info "TODO" {}))))

(defn- re-quote
  [s]
  #?(:clj  
     (java.util.regex.Pattern/quote s)
     :cljs
     (str/replace s #"([)\/\,\.\,\*\+\?\|\(\)\[\]\{\}\\])" "\\$1")))

(defn re-pattern-literal
  "Return a regex that will match the literal string"
  [string]
  (re-pattern (re-quote string)))

;;; TODO make a cljs version
;;; Note: clojure.string/replace is very close to this, but it returns a string instead of fragments.
#?(:clj 
   (defn re-substitute
     "Match re against s, apply subfn to matching substrings. Return list of fragments, processed and otherwise"
     ([re s subfn]
     (let [matcher (re-matcher re s)]
       (loop [fragments ()
              start 0]
         (if (.find matcher)
           (recur (cons (subfn (subs s (.start matcher) (.end matcher)))
                        (cons (subs s start (.start matcher)) fragments))
                  (.end matcher))
           (reverse
            (cons (subs s start) fragments))))))
     ([re s]
      (re-substitute re s identity))))

(def template-regex #"\{(.*?)\}")       ;extract the template fields from the entity

(defn expand-template-string
  "Template is a string containing {foo} elements, which get replaced by corresponding values from bindings"
  [template bindings]
  (let [matches (->> (re-seq template-regex template) 
                     (map (fn [[match key]]
                            [match (or (bindings key) "")])))]
    (reduce (fn [s [match key]]
              (str/replace s (re-pattern-literal match) (str key)))
            template matches)))

(defn validate-template
  "Validate a template, defined as in expand-template-string; fields is a set of allowed field names"
  [template fields]
  (let [vars (map second (re-seq template-regex template))]
    (assert (not (empty? vars)) "Template has no {fields}")
    (doseq [var vars]
      (assert (contains? fields var)
              (format "Template var {%s} is not in sheet" var)))))

;;; Stolen from clj-glob, where it is internal 
(defn glob->regex
  "Takes a glob-format string and returns a regex."
  [s]
  (loop [stream s
         re ""
         curly-depth 0]
    (let [[c j] stream]
        (cond
         (nil? c) (re-pattern (str (if (= \. (first s)) "" "(?=[^\\.])") re))
         (= c \\) (recur (nnext stream) (str re c c) curly-depth)
         (= c \/) (recur (next stream) (str re (if (= \. j) c "/(?=[^\\.])"))
                         curly-depth)
         (= c \*) (recur (next stream) (str re "[^/]*") curly-depth)
         (= c \?) (recur (next stream) (str re "[^/]") curly-depth)
         (= c \{) (recur (next stream) (str re \() (inc curly-depth))
         (= c \}) (recur (next stream) (str re \)) (dec curly-depth))
         (and (= c \,) (< 0 curly-depth)) (recur (next stream) (str re \|)
                                                 curly-depth)
         (#{\. \( \) \| \+ \^ \$ \@ \%} c) (recur (next stream) (str re \\ c)
                                                  curly-depth)
         :else (recur (next stream) (str re c) curly-depth)))))


;;; ⩇⩆⩇ Keywords and namespaces ⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇

(defn keyword-safe
  "Make a string into a readable keyword by replacing certain punctuation"
  [str]
  (keyword (str/replace str #"[ ,\(\):]" "_")))

(def key-counter (atom {}))

(defn unique-key
  "Produce a unique keyword based on root."
  [root]
  (swap! key-counter update root #(inc (or % 0)))
  (keyword (namespace root)
           (str (name root) (get @key-counter root))))

;;; see https://github.com/brandonbloom/backtick for possibly better solution
(defn dens
  "Remove the namespaces that backquote insists on adding"
  [struct]
  (walk/postwalk 
   #(if (symbol? %)
    (symbol nil (name %))
    %)
   struct))

;;; ⩇⩆⩇ Variations on standard predicates ⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇

(defn nullish? 
  "True if value is something we probably don't care about (nil, false, empty seqs, empty strings)"
  [v]
  (or (false? v) (nil? v) (and (seqable? v) (empty? v))))

(defn >*
  "Generalization of > to work on anything with a compare fn"
  ([a b]
   (> (compare a b) 0))
  ([a b & rest]
   (and (>* a b)
        (apply >* b rest))))

(defn <*
  "Generalization of < to work on anything with a compare fn"
  ([a b]
   (< (compare a b) 0))
  ([a b & rest]
   (and (<* a b)
        (apply <* b rest))))

;;; ⩇⩆⩇ Sequences ⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇

(defn doall-safe
  "Realize lazy sequences, if arg is such, otherwise a no-op."
  [thing]
  (if (sequential? thing)
    (doall thing)
    thing))

;;; Convention: <f>= names a fn that is like fn but takes an element to test for equality in place of a predicate.
(defn remove= 
  "Remove occurences of elt in seq"
  [elt seq]
  (remove #(= % elt) seq))

(defn positions
  "Returns a list of indexes of coll for which pred is true"
  [pred coll]
  (keep-indexed (fn [idx x]
                  (when (pred x) idx))
                coll))

(defn position
  "Returns the first index of coll for which pred is true"
  [pred coll]
  (first (positions pred coll)))

(defn positions=
  "Return list of indexes of coll that contain elt"
  [elt coll]
  (positions #(= % elt) coll))

(defn position=
  "Returns the first index of coll that contains elt"
  [elt coll]
  (first (positions= elt coll)))

(defn separate
  "Separate coll into two collections based on pred"
  [pred coll]
  (let [grouped (group-by pred coll)]
    [(get grouped true) (get grouped false)]))

(defn pam
  "Like map but takes its args in different order, useful for ->"
  [seq f]
  (map f seq))

(defn map-filter
  "Applies f to coll. Returns a lazy sequence of the items in coll for which
   all the results that are truthy. f must be free of side-effects."
  [f coll]
  (remove nullish? (map f coll)))

(defn clean-map
  "Remove values from 'map' based on applying 'pred' to value (default is `nullish?`). "
  ([map] (clean-map map nullish?))
  ([map pred] (select-keys map (for [[k v] map :when (not (pred v))] k))))

(defn clean-walk
  "Remove values from all maps in 'struct' based on 'pred' (default is `nullish?`). "
  ([struct] (clean-walk struct nullish?))
  ([struct pred] 
   (walk/postwalk #(if (map? %) (clean-map % pred) %) struct)))

(defn dissoc-walk
  [struct & keys]
  (walk/postwalk #(if (map? %)
                    (apply dissoc % keys)
                    %)
                 struct))

(defn something
  "Like some, but returns the original value of the seq rather than the result of the predicate."
  [pred seq]
  (some #(and (pred %) %) seq))

;;; TODO better name for this! Now that it has a much cleaner implementation.
(defn repeat-until
  "Iterate f on start until a value is produced that is pred"
  [pred f start]
  (something pred (iterate f start)))

(defn safe-nth
  [col n]
  (and (<= 0 n (count col))
       (nth col n)))

(defn distinctly
  "Like distinct, but equality determined by keyfn"
  [coll keyfn]
  (let [step (fn step [xs seen]
               (lazy-seq
                ((fn [[f :as xs] seen]
                   (when-let [s (seq xs)]
                     (if (contains? seen (keyfn f))
                       (recur (rest s) seen)
                       (cons f (step (rest s) (conj seen (keyfn f)))))))
                 xs seen)))]
    (step coll #{})))

(defn unique-relative-to
  "Generate a name based on s, that is not already found in existing."
  [s existing suffix]
  (if (get (set existing) s)
    (unique-relative-to (str s suffix) existing suffix)
    s))

(defn uncollide
  "Given a seq, return a new seq where the elements are guaranteed unique (relative to key-fn), using new-key-fn to generate new elements"
  [seq & {:keys [key-fn new-key-fn existing] :or {key-fn identity
                                                  new-key-fn #(str % "-1")
                                                  existing #{}
                                                  }}]
  (letfn [(step [xs seen]
            (cond (empty? xs) xs
                  (contains? seen (key-fn (first xs)))
                  (let [new-elt (repeat-until #(not (contains? seen (key-fn %)))
                                              new-key-fn (first xs))]
                    (cons new-elt
                          (step (rest xs) (conj seen (key-fn new-elt)))))
                  :else
                  (cons (first xs)
                        (step (rest xs) (conj seen (key-fn (first xs)))))))]
    (step seq (set existing))))

(defn sequencify [thing]
  (if (sequential? thing)
    thing
    (list thing)))

(defn unlist [thing]
  (if (and (sequential? thing) (= 1 (count thing)))
    (first thing)
    thing))

(defn filter-rest
  "A lazy sequence generated by applying f to seq and its tails"
  [f seq]
  (lazy-seq
   (cond
     (empty? seq) []
     (f seq) (cons seq (filter-rest f (rest seq)))
     :else (filter-rest f (rest seq)))))



(defn max-by "Find the maximim element of `seq` based on keyfn"
  [keyfn seq]
  (when-not (empty? seq)
    (reduce (fn [a b] (if (>* (keyfn a) (keyfn b)) a b))
            seq)))

(defn min-by "Find the minimum element of `seq` based on keyfn"
  [keyfn seq]
  (when-not (empty? seq)
    (reduce (fn [a b] (if (<* (keyfn a) (keyfn b)) a b))
            seq)))

(defn lunion "Compute the union of `lists`"
  [& lists]
  (apply set/union (map set lists)))

(defn lintersection "Compute the intersection of `lists`"
  [& lists]
  (seq (apply set/intersection (map set lists))))

(defn lset-difference "Compute the set difference of `list1` - `list2'"
  [list1 list2]
  (seq (set/difference (set list1) (set list2))))



;;; partition-lossless
;;; Previously called take-groups
;;; and turns out to be subsumed by clojure.core/partition-all

(defn map-chunked "Call f with chunk-sized subsequences of l, concat the results"
  [f chunk-size l]
  (mapcat f (partition-all chunk-size l)))

(defn clump-by
  "Sequence is ordered (by vfn), comparator is a fn of two elts. Returns groups in which comp is true for consecutive elements"
  [sequence vfn comparator]
  (if (empty? sequence)
    sequence
    (reverse
     (map reverse
          (reduce (fn [res b]
                    (let [a (first (first res))]
                      (if (comparator (vfn a) (vfn b))
                        (cons (cons b (first res)) (rest res))
                        (cons (list b) res))))
                  (list (list (first sequence)))
                  (rest sequence))))))

(defmacro doseq* "Like doseq, but goes down lists in parallel rather than nested. Assumes lists are same size."
  [bindings & body]
  (let [bindings (partition 2 bindings)
        vars (map first bindings)
        forms (map second bindings)
        lvars (map gensym vars)]
    `(loop ~(into [] (mapcat (fn [v f] [v f]) lvars forms))
       (let ~(into [] (mapcat (fn [v lv] [v `(first ~lv)]) vars lvars))
         ~@body
         (when-not (empty? (rest ~(first lvars)))
           (recur ~@(map (fn [lv] `(rest ~lv)) lvars)))
         ))))

(defmacro for*
  "Like for but goes down lists in parallel rather than nested. Assumes lists are same size."
  [bindings & body]
  (let [bindings (partition 2 bindings)
        vars (map first bindings)
        forms (map second bindings)]
    `(map (fn ~(into [] vars) ~@body) ~@forms)))

;;; ⩇⩆⩇ Maps ⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇

;;; TODO? could work like regular merge and prefer m2 when unmergeable
;;; TODO? might want a version that combined these into a vector or something similar
;;; TODO should take arbitary # of args like merge
(defn merge-recursive [m1 m2]
  (cond (and (map? m1) (map? m2))
        (merge-with merge-recursive m1 m2)
        (nil? m1) m2
        (nil? m2) m1
        (= m1 m2) m1
        :else (throw (ex-info (str "Can't merge " m1 " and " m2) {}))))

(defn map-keys [f hashmap]
  (reduce-kv (fn [acc k v] (assoc acc (f k) v)) {} hashmap))

(defn map-values [f hashmap]
  (reduce-kv (fn [acc k v] (assoc acc k (f v))) {} hashmap))

(defn index-by 
  "Return a map of the elements of coll indexed by (f elt). Similar to group-by, but overwrites elts with same index rather than producing vectors "
  [f coll]  
  (zipmap (map f coll) coll))

(defn index-by-and-transform
  "Return a map of the elements of coll indexed by (f elt) and transformed by (g elt). "
  [f g coll]  
  (zipmap (map f coll) (map g coll)))

;;; TODO use transients as in group-by
(defn group-by-multiple
  "Like group-by, but f produces a seq of values rather than a single one"
  [f coll]  
  (reduce
   (fn [ret x]
     (reduce (fn [ret y]
               (assoc ret y (conj (get ret y []) x)))
             ret (f x)))
   {} coll))

(defn group-by-and-transform
  "Like group-by, but the values of the resultant map have f mapped over them"
  [by f results]
  (map-values
   #(map f %)
   (group-by by results)))

(defn dissoc-if [f hashmap]
  (apply dissoc hashmap (map first (filter f hashmap))))

(defn dissoc-in
  [map [k & k-rest]]
  (if k-rest
    (update map k dissoc-in k-rest)
    (dissoc map k)))

(defn remove-nil-values [hashmap]
  (dissoc-if (fn [[_ v]] (not v)) hashmap))

(defn map-invert-multiple
  "Returns the inverse of map with the vals mapped to the keys. Like set/map-invert, but does the sensible thing with multiple values.
Ex: `(map-invert-multiple  {:a 1, :b 2, :c [3 4], :d 3}) ==>⇒ {2 #{:b}, 4 #{:c}, 3 #{:c :d}, 1 #{:a}}`"
  [m]
  (map-values
   (partial into #{})
   (reduce (fn [m [k v]]
            (reduce (fn [mm elt]
                      (assoc mm elt (cons k (get mm elt))))
                    m
                    (sequencify v)))
          {}
          m)))

;;; See also clojure.data/diff
(defn map-diff
  "Returns a recursive diff of two maps, which you will want to prettyprint."
  [a b]
  (let [both (set/intersection (set (keys a)) (set (keys b)))
        a-only (set/difference (set (keys a)) both)
        b-only (set/difference (set (keys b)) both)
        slot-diffs
        (for [k both
              :when (not (= (k a) (k b)))]
          (if (and (map? (k a)) (map? (k b)))
            [:slot-diff k (map-diff (k a) (k b))]
            [:slot-diff k (k a) (k b)]))]
    [:a-only a-only :b-only b-only :slot-diffs slot-diffs]))

(defn sort-map-by-values [m]
  (into (sorted-map-by (fn [k1 k2] (compare [(get m k2) k2] [(get m k1) k1]))) m))

(defn freq-map [seq]
  (sort-map-by-values (frequencies seq)))

;;; ⩇⩆⩇ Walker ⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇

(defn subst
  "Walk `struct`, replacing any keys in map with corresponding value."
  [struct vmap]
  (walk/postwalk #(if (contains? vmap %) (vmap %) %) struct))

(defn subst-gen
  "Like `subst`, but for entries not in map, call `generator` on first occurance to generate a value"
  [struct map generator]
  (let [cache (atom map)
        generate (fn [k]
                   (let [v (generator k)]
                     (swap! cache assoc k v)
                     v))]
    (walk/postwalk #(if (contains? @cache %)
                      (@cache %)
                      (generate %))
                   struct)))

;;; TODO avoid atom with a loop?
(defn walk-collect
  "Walk f over thing and return a list of the non-nil returned values"
  [f thing]
  (let [collector (atom [])]
    (clojure.walk/postwalk
     #(do
        (when-let [v (f %)]
          (swap! collector conj v))
        %)
     thing)
    @collector))

;;; ⩇⩆⩇ Sets ⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇

(defn powerset
  "Compute the powerset of a set"
  [s]
  (if (empty? s) #{#{}}
      (let [elt (set (list (first s)))
            tail (powerset (rest s))]
        (set/union (into #{} (map #(set/union elt %) tail))
                   tail))))

;;; ⩇⩆⩇ Functional ⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇

(defn invert
  "For use with ->. Produce a 2-arg fn that takes its args in the opposite order."
  [f]
  (fn [a b] (f b a)))

(defn transitive-closure 
  "f is a fn of one arg that returns a list. Returns a new fn that computes the transitive closure."
  [f]
  (fn [root]
    (loop [done (set nil)
           fringe (list root)]
      (if (empty? fringe)
        done
        (let [expanded (first fringe)
              expansion (f expanded)
              new (set/difference (set expansion) done)]
          (recur (set/union done (set (list expanded)))
                 (concat new (rest fringe))))))))

;;; Vectorized fns 

(defn vectorize
  "Given a fn f with scalar args, (vectorized f) is a fn that takes either scalars or vectors for any argument,
  doing the appropriate vectorization. All vector args should be the same length."
  [f]
  (fn [& args]
    (if-let [l (some #(and (sequential? %) (count %)) args)]
      (let [vargs (map #(if (sequential? %) (vec %) %) args)] ;vectorize args
        (mapv (fn [i]
               (apply f (map (fn [arg] (if (vector? arg) (get arg i) arg)) vargs)))
             (range l)))
      (apply f args))))

;;; ⩇⩆⩇ Randomness, basic numerics ⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇⩆⩇

;;; (things that are more for stats or geometry are in org.parkerici.multitool.math)

(defn rand-range
  "Return a random float between a and b"
  [a b]
  (+ a (* (rand) (- b a))))

(defn rand-around
  "Return a random float within range of p"
  [p range]
  (rand-range (- p range) (+ p range)))

(defn round
  "Round the argument"
  [n]
  (if (int? n) n (Math/round n)))

(defn range-truncate
  "Return the closest value to v within range [lower, upper]"
  [v lower upper]
  (and (number? v)
       (max lower (min upper v))))

(defn hex-string
  "Output number as hex string"
  [n]
  #?(:clj (format "%x" n)
     :cljs (.toString n 16)))
