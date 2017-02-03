;;   Copyright (c) Dragan Djuric. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) or later
;;   which can be found in the file LICENSE at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:author "Dragan Djuric"}
    uncomplicate.neanderthal.opencl.clblock
  (:require [uncomplicate.commons.core
             :refer [Releaseable release let-release with-release wrap-float wrap-double]]
            [uncomplicate.fluokitten.protocols :refer [Magma Monoid Foldable Applicative]]
            [uncomplicate.clojurecl.core :refer :all]
            [uncomplicate.neanderthal
             [protocols :refer :all]
             [core :refer [transfer! copy!]]]
            [uncomplicate.neanderthal.impl.fluokitten
             :refer [vector-op matrix-op vector-pure matrix-pure]]
            [uncomplicate.neanderthal.impl.buffer-block
             :refer [real-block-vector real-ge-matrix real-tr-matrix col-navigator row-navigator
                     non-unit-upper-nav unit-upper-nav non-unit-lower-nav unit-lower-nav
                     non-unit-top-navigator unit-top-navigator non-unit-bottom-navigator unit-bottom-navigator]]
            [uncomplicate.neanderthal.impl.cblas :refer [cblas-float cblas-double]])
  (:import [clojure.lang IFn IFn$L IFn$LD IFn$LLD]
           [uncomplicate.clojurecl.core CLBuffer]
           [uncomplicate.neanderthal.protocols BLAS BLASPlus DataAccessor Block Vector
            RealVector Matrix RealMatrix GEMatrix TRMatrix RealChangeable
            RealOrderNavigator UploNavigator StripeNavigator]
           [uncomplicate.neanderthal.impl.buffer_block RealBlockVector RealGEMatrix]))

(def ^{:private true :const true} INEFFICIENT_STRIDE_MSG
  "This operation would be inefficient when stride is not 1.")

(def ^{:private true :const true} INEFFICIENT_OPERATION_MSG
  "This operation would be inefficient because it uses memory transfer. Please use transfer! of map-memory to be reminded of that.")

(defn cl-to-host [cl host]
  (let [mapped-host (map-memory cl :read)]
    (try
      (copy! mapped-host host)
      (finally (unmap cl mapped-host)))))

(defn host-to-cl [host cl]
  (let [mapped-host (map-memory cl :write-invalidate-region)]
    (try
      (copy! host mapped-host)
      cl
      (finally (unmap cl mapped-host)))))

(defprotocol CLAccessor
  (get-queue [this]))

;; ================== Declarations ============================================

(declare cl-block-vector)
(declare cl-ge-matrix)
(declare cl-tr-matrix)

;; ================== Accessors ================================================

(deftype TypedCLAccessor [ctx queue et ^long w array-fn wrap-fn host-fact]
  DataAccessor
  (entryType [_]
    et)
  (entryWidth [_]
    w)
  (count [_ b]
    (quot (long (size b)) w))
  (createDataSource [_ n]
    (cl-buffer ctx (* w (max 1 (long n))) :read-write))
  (initialize [_ buf]
    (enq-fill! queue buf (array-fn 1))
    buf)
  (initialize [_ buf v]
    (enq-fill! queue buf (wrap-fn v))
    buf)
  (wrapPrim [_ s]
    (wrap-fn s))
  CLAccessor
  (get-queue [_]
    queue)
  Contextual
  (cl-context [_]
    ctx)
  DataAccessorProvider
  (data-accessor [this]
    this)
  MemoryContext
  (compatible? [this o]
    (let [da (data-accessor o)]
      (or
       (identical? this o)
       (and (instance? TypedCLAccessor da)
            (= et (.et ^TypedCLAccessor da)) (= ctx (.ctx ^TypedCLAccessor da)))
       (= ctx o)
       (= et o))))
  FactoryProvider
  (native-factory [_]
    host-fact)
  (factory [_]
    host-fact))

(defn cl-float-accessor [ctx queue]
  (->TypedCLAccessor ctx queue Float/TYPE Float/BYTES float-array wrap-float cblas-float))

(defn cl-double-accessor [ctx queue]
  (->TypedCLAccessor ctx queue Double/TYPE Double/BYTES double-array wrap-double cblas-double))

;; ================== Non-blas kernels =========================================
(defprotocol BlockEngine
  (equals-block [_ cl-x cl-y]))

;; =============================================================================

(deftype CLBlockVector [^uncomplicate.neanderthal.protocols.Factory fact
                        ^DataAccessor da ^BLASPlus eng master
                        ^CLBuffer buf^long n ^long ofst ^long strd]
  Object
  (hashCode [x]
    (-> (hash :CLBlockVector) (hash-combine n) (hash-combine (.nrm2 eng x))))
  (equals [x y]
    (cond
      (nil? y) false
      (identical? x y) true
      (and (instance? CLBlockVector y) (compatible? x y) (fits? x y))
      (equals-block eng x y)
      :default false))
  (toString [this]
    (format "#CLBlockVector[%s, n:%d, offset:%d stride:%d]" (.entryType da) n ofst strd))
  Releaseable
  (release [_]
    (if (compare-and-set! master true false)
      (release buf)
      true))
  Container
  (raw [_]
    (cl-block-vector fact n))
  (raw [_ fact]
    (create-vector fact n false))
  (zero [x]
    (zero x fact))
  (zero [_ fact]
    (create-vector fact n true))
  (host [x]
    (let-release [res (create-vector (native-factory da) n false)]
      (cl-to-host x res)))
  (native [x]
    (host x))
  MemoryContext
  (compatible? [_ y]
    (compatible? da y))
  (fits? [_ y]
    (= n (.dim ^Vector y)))
  EngineProvider
  (engine [_]
    eng)
  FactoryProvider
  (factory [_]
    fact)
  (native-factory [_]
    (native-factory da))
  DataAccessorProvider
  (data-accessor [_]
    da)
  Block
  (buffer [_]
    buf)
  (offset [_]
    ofst)
  (stride [_]
    strd)
  (count [_]
    n)
  IFn
  (invoke [x i]
    (.entry x i))
  (invoke [x]
    n)
  IFn$LD
  (invokePrim [x i]
    (.entry x i))
  IFn$L
  (invokePrim [x]
    n)
  RealChangeable
  (set [x val]
    (if (and (= 0 ofst) (= 1 strd))
      (.initialize da buf val)
      (throw (IllegalArgumentException. INEFFICIENT_STRIDE_MSG)))
    x)
  (set [_ _ _]
    (throw (UnsupportedOperationException. INEFFICIENT_OPERATION_MSG)))
  (setBoxed [x val]
    (.set x val))
  (setBoxed [x i val]
    (.set x i val))
  (alter [_ _ _]
    (throw (UnsupportedOperationException. INEFFICIENT_OPERATION_MSG)))
  RealVector
  (dim [_]
    n)
  (entry [_ _]
    (throw (UnsupportedOperationException. INEFFICIENT_OPERATION_MSG)))
  (boxedEntry [x i]
    (.entry x i))
  (subvector [_ k l]
    (cl-block-vector fact (atom false) buf l (+ ofst (* k strd)) strd))
  Monoid
  (id [x]
    (cl-block-vector fact 0))
  Foldable
  (fold [x]
    (.sum eng x))
  Mappable
  (map-memory [_ flags]
    (let [host-fact (native-factory da)
          queue (get-queue da)
          mapped-buf (enq-map-buffer! queue buf true (* ofst (.entryWidth da))
                                      (* strd n (.entryWidth da)) flags nil nil)]
      (try
        (real-block-vector host-fact true mapped-buf n 0 strd)
        (catch Exception e (enq-unmap! queue buf mapped-buf)))))
  (unmap [x mapped]
    (enq-unmap! (get-queue da) buf (.buffer ^Block mapped))
    x))

(defn cl-block-vector
  ([fact master ^CLBuffer buf n ofst strd]
   (let [da (data-accessor fact)]
     (if (and (<= 0 n (.count da buf)))
       (CLBlockVector. fact da (vector-engine fact) (atom master) buf n ofst strd)
       (throw (IllegalArgumentException.
               (format "I can not create an %d element vector from %d-element %s."
                       n (.count da buf) (class buf)))))))
  ([fact n]
   (let-release [buf (.createDataSource (data-accessor fact) n)]
     (cl-block-vector fact true buf n 0 1))))

(extend CLBlockVector
  Applicative
  {:pure vector-pure}
  Magma
  {:op (constantly vector-op)})

(defmethod print-method CLBlockVector
  [x ^java.io.Writer w]
  (.write w (str x)))

(defmethod transfer! [CLBlockVector CLBlockVector]
  [source destination]
  (copy! source destination))

(defmethod transfer! [CLBlockVector RealBlockVector]
  [source destination]
  (cl-to-host source destination))

(defmethod transfer! [RealBlockVector CLBlockVector]
  [source destination]
  (host-to-cl source destination))

(defmethod transfer! [clojure.lang.Sequential CLBlockVector]
  [source ^CLBlockVector destination]
  (with-release [host (raw destination (native-factory destination))]
    (host-to-cl (transfer! source host) destination)))

;; ================== CL Matrix ============================================

(deftype CLGEMatrix [^RealOrderNavigator navigator ^uncomplicate.neanderthal.protocols.Factory fact
                     ^DataAccessor da ^BLAS eng master ^CLBuffer buf ^long m ^long n
                     ^long ofst ^long ld ^long sd ^long fd ^long ord]
  Object
  (hashCode [a]
    (let [h (-> (hash :CLGEMatrix) (hash-combine m) (hash-combine n))]
      (if (< 0 sd)
        (let-release [x (.stripe navigator a 0)] (hash-combine h (.nrm2 eng x)))
        h)))
  (equals [a b]
    (cond
      (nil? b) false
      (identical? a b) true
      (and (instance? CLGEMatrix b) (compatible? a b) (fits? a b))
      (equals-block eng a b)
      :default false))
  (toString [_]
    (format "#CLGEMatrix[%s, mxn:%dx%d, order%s, offset:%d, ld:%d]"
            (.entryType da) m n (dec-property ord) ofst ld))
  Releaseable
  (release [_]
    (if (compare-and-set! master true false)
      (release buf)
      true))
  EngineProvider
  (engine [_]
    eng)
  FactoryProvider
  (factory [_]
    fact)
  (native-factory [_]
    (native-factory da))
  DataAccessorProvider
  (data-accessor [_]
    da)
  Container
  (raw [_]
    (cl-ge-matrix fact m n ord))
  (raw [_ fact]
    (create-ge fact m n ord false))
  (zero [a]
    (zero a fact))
  (zero [_ fact]
    (create-ge fact m n ord true))
  (host [a]
    (let-release [res (create-ge (native-factory da) m n ord false)]
      (cl-to-host a res)))
  (native [this]
    (host this))
  DenseContainer
  (subtriangle [_ uplo diag];;TODO remove and introduce new function similar to copy that reuses memory (view x :tr)
    (cl-tr-matrix fact false buf (min m n) 0 ld ord uplo diag))
  MemoryContext
  (compatible? [_ b]
    (compatible? da b))
  (fits? [_ b]
    (and (= m (.mrows ^GEMatrix b)) (= n (.ncols ^GEMatrix b))))
  GEMatrix
  (buffer [_]
    buf)
  (offset [_]
    ofst)
  (stride [_]
    ld)
  (order [_]
    ord)
  (count [_]
    (* m n))
  (sd [_]
    sd)
  (fd [_]
    fd)
  IFn$LLD
  (invokePrim [a i j]
    (.entry a i j))
  IFn
  (invoke [a i j]
    (.entry a i j))
  (invoke [a]
    sd)
  IFn$L
  (invokePrim [a]
    sd)
  RealChangeable
  (isAllowed [a i j]
    true)
  (set [a val]
    (if (and (= ld sd)
             (= 0 ofst)
             (= (* m n) (.count da buf)))
      (.initialize da buf val)
      (throw (IllegalArgumentException. INEFFICIENT_STRIDE_MSG)))
    a)
  (set [_ _ _ _]
    (throw (UnsupportedOperationException. INEFFICIENT_OPERATION_MSG)))
  (setBoxed [a val]
    (.set a val))
  (setBoxed [a i j val]
    (.set a i j val))
  (alter [a i j f]
    (throw (UnsupportedOperationException. INEFFICIENT_OPERATION_MSG)))
  RealMatrix
  (mrows [_]
    m)
  (ncols [_]
    n)
  (entry [_ _ _]
    (throw (UnsupportedOperationException. INEFFICIENT_OPERATION_MSG)))
  (boxedEntry [a i j]
    (.entry a i j))
  (row [a i]
    (cl-block-vector fact false buf n (.index navigator ofst ld i 0) (if (= ROW_MAJOR ord) 1 ld)))
  (col [a j]
    (cl-block-vector fact false buf m (.index navigator ofst ld 0 j) (if (= COLUMN_MAJOR ord) 1 ld)))
  (submatrix [a i j k l]
    (cl-ge-matrix fact false buf k l (.index navigator ofst ld i j) ld ord))
  (transpose [a]
    (cl-ge-matrix fact false buf n m ofst ld (if (= COLUMN_MAJOR ord) ROW_MAJOR COLUMN_MAJOR)))
  Monoid
  (id [a]
    (cl-ge-matrix fact 0 0))
  Mappable
  (map-memory [a flags]
    (let [host-fact (native-factory da)
          queue (get-queue da)
          mapped-buf (enq-map-buffer! queue buf true (* ofst (.entryWidth da))
                                      (* fd ld (.entryWidth da)) flags nil nil)]
      (try
        (real-ge-matrix host-fact true mapped-buf m n 0 ld ord)
        (catch Exception e (enq-unmap! queue buf mapped-buf)))))
  (unmap [this mapped]
    (enq-unmap! (get-queue da) buf (.buffer ^Block mapped))
    this))

(defn cl-ge-matrix
  ([fact master buf m n ofst ld ord]
   (let [^RealOrderNavigator navigator (if (= COLUMN_MAJOR ord) col-navigator row-navigator)]
     (CLGEMatrix. (if (= COLUMN_MAJOR ord) col-navigator row-navigator) fact (data-accessor fact)
                  (ge-engine fact) (atom master) buf m n ofst (max (long ld) (.sd navigator m n))
                  (.sd navigator m n) (.fd navigator m n) ord)))
  ([fact ^long m ^long n ord]
   (let-release [buf (.createDataSource (data-accessor fact) (* m n))]
     (cl-ge-matrix fact true buf m n 0 m ord)))
  ([fact ^long m ^long n]
   (cl-ge-matrix fact m n DEFAULT_ORDER)))

(extend CLGEMatrix
  Applicative
  {:pure matrix-pure}
  Magma
  {:op (constantly matrix-op)})

(defmethod print-method CLGEMatrix
  [x ^java.io.Writer w]
  (.write w (str x)))

(defmethod transfer! [CLGEMatrix CLGEMatrix]
  [source destination]
  (copy! source destination))

(defmethod transfer! [CLGEMatrix RealGEMatrix]
  [source destination]
  (cl-to-host source destination))

(defmethod transfer! [RealGEMatrix CLGEMatrix]
  [source destination]
  (host-to-cl source destination))

(defmethod transfer! [clojure.lang.Sequential CLGEMatrix]
  [source destination]
  (with-release [host (raw destination (native-factory destination))]
    (host-to-cl (transfer! source host) destination)))
