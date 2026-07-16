(ns portauthority.store
  "SSoT for the port/harbor-support-services operations-coordination
  actor (ISIC 5222), behind a `Store` protocol so the backend is a
  swap, not a rewrite -- the same seam every prior `cloud-itonami-
  isic-*` actor in this fleet uses.

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/portauthority/store_contract_test.clj), which is the whole
  point: the actor, the Port Authority Governor and the audit ledger
  never know which SSoT they run on.

  Two entity directories:
    - `clearance`  -- a port/vessel-clearance record (an independently
                      registered + verified navigation-safety scope
                      entry for one vessel's port call). Targeted by
                      `:log-service-record`, `:schedule-port-operation`
                      and `:flag-navigation-safety-concern`.
    - `facility`   -- a navigation-aid / port-facility record (buoy,
                      light, radar beacon, berth fender, etc.).
                      Targeted by `:coordinate-maintenance` (a
                      facility-level op, the same 'facility-level ops
                      don't need a per-vessel clearance' exemption
                      `cloud-itonami-isic-561`'s governor establishes
                      for its own non-reservation ops).

  This actor is deliberately an OPERATIONS COORDINATION layer, not a
  navigation-safety authority: every commit below is a LOG / PROPOSAL
  / COORDINATION record, never a berth-safety clearance, a pilotage
  waiver or a vessel-movement authorization -- see
  `portauthority.governor`'s `finalize-clearance-violations` (a hard,
  permanent block) and the closed op-allowlist, which together make
  'directly finalize a clearance' structurally unreachable from this
  actor.

  The ledger stays append-only on every backend: which clearance/
  facility was screened, which proposal committed or held, and on
  what basis, is always a query over an immutable log -- the audit
  trail a port authority, harbor master or regulator trusts this
  actor with."
  (:require [portauthority.registry :as registry]
            [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (clearance [s id])
  (all-clearances [s])
  (facility [s id])
  (all-facilities [s])
  (ledger [s])
  (service-log [s] "the append-only committed :log-service-record history")
  (schedule-log [s] "the append-only committed :schedule-port-operation history")
  (maintenance-log [s] "the append-only committed :coordinate-maintenance history")
  (concern-log [s] "the append-only committed :flag-navigation-safety-concern history (post human sign-off)")
  (next-sequence [s jurisdiction kind] "next record-number sequence for a jurisdiction + record kind")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-clearances [s clearances] "replace/seed the clearance directory (map id->clearance)")
  (with-facilities [s facilities] "replace/seed the facility directory (map id->facility)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained clearance + facility set covering the happy
  path plus the governor's own hard checks, so the actor + tests run
  offline. Each violation entity isolates exactly ONE failure mode
  (the rest stay clean), the 'exercise the failure mode directly,
  never only via a happy-path actuation' discipline every sibling
  governor's demo data establishes."
  []
  {:clearances
   {"clr-1" {:id "clr-1" :vessel-name "MV Aurora" :berth-id "B-12"
             :jurisdiction "JPN" :registered? true :verified? true}
    "clr-2" {:id "clr-2" :vessel-name "MV Borealis" :berth-id "B-07"
             :jurisdiction "JPN" :registered? true :verified? false}
    "clr-3" {:id "clr-3" :vessel-name "MV Cascade" :berth-id "B-03"
             :jurisdiction "JPN" :registered? false :verified? false}}
   :facilities
   {"fac-1" {:id "fac-1" :name "Buoy 14" :kind "buoy"
             :jurisdiction "JPN" :registered? true}
    "fac-2" {:id "fac-2" :name "Radar Beacon 3" :kind "radar-beacon"
             :jurisdiction "JPN" :registered? false}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- commit-kind!
  "Backend-agnostic record draft for `kind` (:service | :schedule |
  :maintenance | :concern) -- drafts the append-only record for
  `target-id`/`jurisdiction`/`seq-n` and returns {:result ..} for the
  caller to persist. Pure w.r.t. any particular backend's transaction
  mechanics (the backend has already resolved `jurisdiction` and
  `seq-n` via the protocol before calling this)."
  [kind target-id jurisdiction seq-n]
  (let [result (case kind
                 :service     (registry/register-service-record target-id jurisdiction seq-n)
                 :schedule    (registry/register-schedule-record target-id jurisdiction seq-n)
                 :maintenance (registry/register-maintenance-record target-id jurisdiction seq-n)
                 :concern     (registry/register-concern-record target-id jurisdiction seq-n))]
    {:result result}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (clearance [_ id] (get-in @a [:clearances id]))
  (all-clearances [_] (sort-by :id (vals (:clearances @a))))
  (facility [_ id] (get-in @a [:facilities id]))
  (all-facilities [_] (sort-by :id (vals (:facilities @a))))
  (ledger [_] (:ledger @a))
  (service-log [_] (:service-log @a))
  (schedule-log [_] (:schedule-log @a))
  (maintenance-log [_] (:maintenance-log @a))
  (concern-log [_] (:concern-log @a))
  (next-sequence [_ jurisdiction kind] (get-in @a [:sequences kind jurisdiction] 0))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :clearance/upsert
      (swap! a update-in [:clearances (:id value)] merge value)

      :facility/upsert
      (swap! a update-in [:facilities (:id value)] merge value)

      (:service/log :schedule/propose :maintenance/coordinate :concern/record)
      (let [kind (case effect
                   :service/log :service
                   :schedule/propose :schedule
                   :maintenance/coordinate :maintenance
                   :concern/record :concern)
            target-id (first path)
            jurisdiction (or (:jurisdiction (clearance s target-id))
                             (:jurisdiction (facility s target-id)))
            seq-n (next-sequence s jurisdiction kind)
            {:keys [result]} (commit-kind! kind target-id jurisdiction seq-n)
            log-key (case kind
                      :service :service-log :schedule :schedule-log
                      :maintenance :maintenance-log :concern :concern-log)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences kind jurisdiction] (fnil inc 0))
                       (update log-key registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-clearances [s clearances] (when (seq clearances) (swap! a assoc :clearances clearances)) s)
  (with-facilities [s facilities] (when (seq facilities) (swap! a assoc :facilities facilities)) s))

(defn seed-db
  "A MemStore seeded with the demo clearance/facility set. The
  deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :ledger [] :sequences {}
                           :service-log [] :schedule-log []
                           :maintenance-log [] :concern-log []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

;; Schema, the EDN-blob codec and the clearance/facility entity
;; map<->tx<->pull are the shared kotoba-lang/langchain-store machinery
;; (ADR-2607141600) -- the seam ~190 actors hand-roll. Only the
;; clearance/facility field specs and the ledger/log/sequence attrs
;; (custom query shapes) are per-domain wiring.
(def ^:private schema
  (ls/identity-schema [:clearance/id :facility/id :ledger/seq
                       :service/seq :schedule/seq :maintenance/seq :concern/seq
                       :sequence/key]))

(defn- enc [v] (ls/enc v))
(defn- dec* [s] (ls/dec* s))

(def ^:private clearance-spec
  {:id {:attr :clearance/id}
   :vessel-name {:attr :clearance/vessel-name}
   :berth-id {:attr :clearance/berth-id}
   :jurisdiction {:attr :clearance/jurisdiction}
   :registered? {:attr :clearance/registered? :coerce boolean}
   :verified? {:attr :clearance/verified? :coerce boolean}})

(def ^:private facility-spec
  {:id {:attr :facility/id}
   :name {:attr :facility/name}
   :kind {:attr :facility/kind}
   :jurisdiction {:attr :facility/jurisdiction}
   :registered? {:attr :facility/registered? :coerce boolean}})

(defn- clearance->tx [m] (ls/map->tx clearance-spec m))
(def ^:private clearance-pull (ls/pull-pattern clearance-spec))
(defn- pull->clearance [m] (ls/pull->map clearance-spec :id m))

(defn- facility->tx [m] (ls/map->tx facility-spec m))
(def ^:private facility-pull (ls/pull-pattern facility-spec))
(defn- pull->facility [m] (ls/pull->map facility-spec :id m))

(defn- seq-key [jurisdiction kind] (str (name kind) "::" jurisdiction))

(defrecord DatomicStore [conn]
  Store
  (clearance [_ id]
    (pull->clearance (d/pull (d/db conn) clearance-pull [:clearance/id id])))
  (all-clearances [_]
    (->> (d/q '[:find [?id ...] :where [?e :clearance/id ?id]] (d/db conn))
         (map #(pull->clearance (d/pull (d/db conn) clearance-pull [:clearance/id %])))
         (sort-by :id)))
  (facility [_ id]
    (pull->facility (d/pull (d/db conn) facility-pull [:facility/id id])))
  (all-facilities [_]
    (->> (d/q '[:find [?id ...] :where [?e :facility/id ?id]] (d/db conn))
         (map #(pull->facility (d/pull (d/db conn) facility-pull [:facility/id %])))
         (sort-by :id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (service-log [_]
    (->> (d/q '[:find ?s ?r :where [?e :service/seq ?s] [?e :service/record ?r]] (d/db conn))
         (sort-by first) (mapv (comp dec* second))))
  (schedule-log [_]
    (->> (d/q '[:find ?s ?r :where [?e :schedule/seq ?s] [?e :schedule/record ?r]] (d/db conn))
         (sort-by first) (mapv (comp dec* second))))
  (maintenance-log [_]
    (->> (d/q '[:find ?s ?r :where [?e :maintenance/seq ?s] [?e :maintenance/record ?r]] (d/db conn))
         (sort-by first) (mapv (comp dec* second))))
  (concern-log [_]
    (->> (d/q '[:find ?s ?r :where [?e :concern/seq ?s] [?e :concern/record ?r]] (d/db conn))
         (sort-by first) (mapv (comp dec* second))))
  (next-sequence [_ jurisdiction kind]
    (or (d/q '[:find ?n . :in $ ?k
              :where [?e :sequence/key ?k] [?e :sequence/next ?n]]
            (d/db conn) (seq-key jurisdiction kind))
        0))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :clearance/upsert
      (d/transact! conn [(clearance->tx value)])

      :facility/upsert
      (d/transact! conn [(facility->tx value)])

      (:service/log :schedule/propose :maintenance/coordinate :concern/record)
      (let [kind (case effect
                   :service/log :service
                   :schedule/propose :schedule
                   :maintenance/coordinate :maintenance
                   :concern/record :concern)
            target-id (first path)
            jurisdiction (or (:jurisdiction (clearance s target-id))
                             (:jurisdiction (facility s target-id)))
            seq-n (next-sequence s jurisdiction kind)
            {:keys [result]} (commit-kind! kind target-id jurisdiction seq-n)
            next-n (inc seq-n)
            seq-attr (case kind :service :service/seq :schedule :schedule/seq
                          :maintenance :maintenance/seq :concern :concern/seq)
            rec-attr (case kind :service :service/record :schedule :schedule/record
                          :maintenance :maintenance/record :concern :concern/record)
            log-count (case kind
                        :service (count (service-log s)) :schedule (count (schedule-log s))
                        :maintenance (count (maintenance-log s)) :concern (count (concern-log s)))]
        (d/transact! conn
                     [{:sequence/key (seq-key jurisdiction kind) :sequence/next next-n}
                      {seq-attr log-count rec-attr (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-clearances [s clearances]
    (when (seq clearances) (d/transact! conn (mapv clearance->tx (vals clearances)))) s)
  (with-facilities [s facilities]
    (when (seq facilities) (d/transact! conn (mapv facility->tx (vals facilities)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:clearances .. :facilities ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [clearances facilities]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-clearances clearances) (with-facilities facilities)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo clearance/facility set -- the
  Datomic-backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
