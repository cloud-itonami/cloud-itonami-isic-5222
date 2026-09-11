(ns portauthority.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:flag-navigation-safety-concern` must NEVER be a member
  of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [portauthority.phase :as phase]))

(deftest flag-navigation-safety-concern-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in any future entry, auto-commits a navigation-safety-concern flag"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :flag-navigation-safety-concern))
          (str "phase " n " must not auto-commit :flag-navigation-safety-concern")))))

(deftest no-finalize-clearance-op-exists-anywhere
  (testing "structural invariant: no phase's :writes or :auto set contains any op that would finalize a berth/navigation-safety clearance -- no such op exists in the domain at all"
    (doseq [[n {:keys [writes auto]}] phase/phases]
      (is (= #{} (set/intersection writes #{:finalize-berth-clearance :finalize-clearance :override-pilotage-requirement}))
          (str "phase " n " writes must never contain a finalize/override op"))
      (is (= #{} (set/intersection auto #{:finalize-berth-clearance :finalize-clearance :override-pilotage-requirement}))
          (str "phase " n " auto must never contain a finalize/override op")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-risk-ops
  (testing ":log-service-record carries no capital risk or navigation-safety determination -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:log-service-record} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :log-service-record} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :schedule-port-operation} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :coordinate-maintenance} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :flag-navigation-safety-concern} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :log-service-record} :commit)))))

(deftest gate-auto-commits-a-clean-eligible-write-at-phase-3
  (is (= :commit (:disposition (phase/gate 3 {:op :log-service-record} :commit)))))
