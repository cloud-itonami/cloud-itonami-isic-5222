(ns portauthority.scope-exclusion-test
  "Dedicated regression test for a self-tripping bug class multiple
  sibling `cloud-itonami-isic-*` actors in this fleet independently
  hit and fixed: a governor's scope-exclusion term list phrased as a
  bare noun ('safety', 'clearance', 'pilotage') can accidentally match
  inside the mock advisor's own DEFAULT rationale/disclaimer text for
  a legitimate, allowed proposal, causing the actor to self-block on
  its own happy path.

  `portauthority.governor/finalize-clearance-patterns` is phrased as
  the finalization/execution ACTION ('finalize the berth-safety
  clearance', not the bare noun 'safety') specifically to avoid this.
  This test asserts the invariant directly: NONE of
  `portauthority.advisor`'s four default proposals ever trips
  `:finalize-clearance-attempt`, for every target (including the
  unverified/unregistered ones, which SHOULD hold on other rules but
  must never additionally trip the scope-exclusion check)."
  (:require [clojure.test :refer [deftest is testing]]
            [portauthority.store :as store]
            [portauthority.advisor :as advisor]
            [portauthority.governor :as governor]))

(defn- rule-set [verdict]
  (set (map :rule (:violations verdict))))

(deftest default-proposals-never-self-trip-scope-exclusion
  (let [db (store/seed-db)
        adv (advisor/mock-advisor)
        cases [{:op :log-service-record :target-id "clr-1" :detail "pilotage assist"}
               {:op :log-service-record :target-id "clr-2" :detail "towage"}
               {:op :log-service-record :target-id "clr-3" :detail "berth assignment"}
               {:op :schedule-port-operation :target-id "clr-1" :resource-request {:berth "B-12" :tug 1 :pilot 1}}
               {:op :schedule-port-operation :target-id "clr-2" :resource-request {:berth "B-07"}}
               {:op :flag-navigation-safety-concern :target-id "clr-1"
                :concern-type "berth-hazard" :description "reported debris near berth fender"}
               {:op :flag-navigation-safety-concern :target-id "clr-2"
                :concern-type "nav-aid-fault" :description "buoy light reported out"}
               {:op :flag-navigation-safety-concern :target-id "clr-3"
                :concern-type "pilotage-availability" :description "no pilot available for scheduled window"}
               {:op :coordinate-maintenance :target-id "fac-1" :maintenance-type "buoy-inspection"}
               {:op :coordinate-maintenance :target-id "fac-2" :maintenance-type "radar-calibration"}]]
    (doseq [request cases]
      (testing (str (:op request) " on " (:target-id request))
        (let [proposal (advisor/-advise adv db request)
              verdict (governor/check request {:actor-id "op-1"} proposal db)]
          (is (= :propose (:effect proposal)) "advisor always proposes, never commits directly")
          (is (not (contains? (rule-set verdict) :finalize-clearance-attempt))
              (str "legitimate default proposal must never self-trip the scope-exclusion check: "
                   (pr-str (:violations verdict))))
          (is (not (contains? (rule-set verdict) :op-not-allowed))
              "every default advisor op is in the closed allowlist")
          (is (not (contains? (rule-set verdict) :effect-not-propose))
              "the advisor's own :effect is always literally :propose"))))))

(deftest finalize-clearance-patterns-do-catch-a-real-attempt
  (testing "sanity check: the patterns are not vacuously non-matching -- they DO catch an actual finalization-action attempt"
    (let [db (store/seed-db)
          attempts
          [{:operation :schedule-port-operation :effect :propose :target-id "clr-1"
            :confidence 0.9 :rationale "we will finalize the berth safety clearance for this vessel"}
           {:operation :schedule-port-operation :effect :propose :target-id "clr-1"
            :confidence 0.9 :rationale "propose to override the pilotage requirement"}
           {:operation :log-service-record :effect :propose :target-id "clr-1"
            :confidence 0.9 :summary "岸壁の安全を確定します"}]]
      (doseq [proposal attempts]
        (let [verdict (governor/check {:op (:operation proposal) :target-id "clr-1"}
                                      {:actor-id "op-1"} proposal db)]
          (is (contains? (rule-set verdict) :finalize-clearance-attempt)
              (str "must catch: " (pr-str proposal))))))))
