(ns portauthority.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean clearance through
  service-record logging (auto-commits at phase 3) -> berth/tug/pilot
  scheduling (escalates/approve/commit) -> a navigation-safety-concern
  flag (ALWAYS escalates/approve/commit) -> facility maintenance
  coordination (escalates/approve/commit), then shows HARD-hold
  scenarios: an unverified clearance, an unregistered clearance, an
  unregistered facility, a hallucinated (non-allowlisted) op, and a
  proposal whose text tries to finalize a berth-safety clearance
  (scope-exclusion hard block).

  Each check is exercised directly and independently, one clearance/
  facility per HARD-hold scenario, the same 'exercise the failure mode
  directly, never only via a happy-path actuation' discipline every
  sibling actor's sim establishes."
  (:require [langgraph.graph :as g]
            [portauthority.store :as store]
            [portauthority.operation :as op]
            [portauthority.governor :as governor]))

(def operator {:actor-id "op-1" :actor-role :port-authority-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== log-service-record clr-1 (clean, JPN -- auto-commits at phase 3) ==")
    (println (exec-op actor "t1" {:op :log-service-record :target-id "clr-1"
                                  :detail "pilotage assist completed"} operator))

    (println "== schedule-port-operation clr-1 (escalates -- real resource commitment) ==")
    (let [r (exec-op actor "t2" {:op :schedule-port-operation :target-id "clr-1"
                                 :resource-request {:berth "B-12" :tug 1 :pilot 1}} operator)]
      (println r)
      (println "-- human port-authority operator approves --")
      (println (approve! actor "t2")))

    (println "== flag-navigation-safety-concern clr-1 (ALWAYS escalates) ==")
    (let [r (exec-op actor "t3" {:op :flag-navigation-safety-concern :target-id "clr-1"
                                 :concern-type "berth-hazard"
                                 :description "reported debris near berth fender"} operator)]
      (println r)
      (println "-- human port-authority operator signs off --")
      (println (approve! actor "t3")))

    (println "== coordinate-maintenance fac-1 (escalates -- real maintenance dispatch) ==")
    (let [r (exec-op actor "t4" {:op :coordinate-maintenance :target-id "fac-1"
                                 :maintenance-type "buoy-inspection"} operator)]
      (println r)
      (println "-- human port-authority operator approves --")
      (println (approve! actor "t4")))

    (println "== log-service-record clr-2 (unverified clearance -> HARD hold) ==")
    (println (exec-op actor "t5" {:op :log-service-record :target-id "clr-2"
                                  :detail "towage assist"} operator))

    (println "== schedule-port-operation clr-3 (unregistered clearance -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :schedule-port-operation :target-id "clr-3"
                                  :resource-request {:berth "B-03"}} operator))

    (println "== coordinate-maintenance fac-2 (unregistered facility -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :coordinate-maintenance :target-id "fac-2"
                                  :maintenance-type "radar-calibration"} operator))

    (println "== defense-in-depth: hallucinated op / smuggled finalize action (governor/check directly) ==")
    (println "hallucinated op not in closed allowlist:"
             (governor/check {:op :log-service-record :target-id "clr-1"}
                             operator
                             {:operation :finalize-berth-clearance :effect :propose
                              :target-id "clr-1" :confidence 0.9}
                             db))
    (println "proposal text smuggling a finalize-clearance action:"
             (governor/check {:op :schedule-port-operation :target-id "clr-1"}
                             operator
                             {:operation :schedule-port-operation :effect :propose
                              :target-id "clr-1" :confidence 0.9
                              :rationale "recommend to finalize the berth safety clearance now"}
                             db))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft service-log records ==")
    (doseq [r (store/service-log db)] (println r))

    (println "== draft schedule-log records ==")
    (doseq [r (store/schedule-log db)] (println r))

    (println "== draft concern-log records ==")
    (doseq [r (store/concern-log db)] (println r))

    (println "== draft maintenance-log records ==")
    (doseq [r (store/maintenance-log db)] (println r))))
