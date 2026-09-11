(ns portauthority.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite."
  (:require [clojure.test :refer [deftest is testing]]
            [portauthority.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "MV Aurora" (:vessel-name (store/clearance s "clr-1"))))
      (is (true? (:registered? (store/clearance s "clr-1"))))
      (is (true? (:verified? (store/clearance s "clr-1"))))
      (is (true? (:registered? (store/clearance s "clr-2"))) "clr-2 registered")
      (is (false? (:verified? (store/clearance s "clr-2"))) "clr-2 unverified")
      (is (false? (:registered? (store/clearance s "clr-3"))) "clr-3 unregistered")
      (is (true? (:registered? (store/facility s "fac-1"))))
      (is (false? (:registered? (store/facility s "fac-2"))) "fac-2 unregistered")
      (is (= ["clr-1" "clr-2" "clr-3"] (mapv :id (store/all-clearances s))))
      (is (= ["fac-1" "fac-2"] (mapv :id (store/all-facilities s))))
      (is (= [] (store/ledger s)))
      (is (= [] (store/service-log s)))
      (is (= [] (store/schedule-log s)))
      (is (= [] (store/maintenance-log s)))
      (is (= [] (store/concern-log s)))
      (is (zero? (store/next-sequence s "JPN" :service)))
      (is (zero? (store/next-sequence s "JPN" :schedule))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "service-record commit drafts a record and advances the service sequence"
        (store/commit-record! s {:effect :service/log :path ["clr-1"]})
        (is (= "JPN-SERVICE-000000" (get (first (store/service-log s)) "record_id")))
        (is (= "service-log-draft" (get (first (store/service-log s)) "kind")))
        (is (= 1 (count (store/service-log s))))
        (is (= 1 (store/next-sequence s "JPN" :service))))
      (testing "schedule-proposal commit drafts a record and advances the schedule sequence"
        (store/commit-record! s {:effect :schedule/propose :path ["clr-1"]})
        (is (= "JPN-SCHEDULE-000000" (get (first (store/schedule-log s)) "record_id")))
        (is (= 1 (count (store/schedule-log s))))
        (is (= 1 (store/next-sequence s "JPN" :schedule))))
      (testing "maintenance-coordination commit drafts a record against a facility"
        (store/commit-record! s {:effect :maintenance/coordinate :path ["fac-1"]})
        (is (= "JPN-MAINT-000000" (get (first (store/maintenance-log s)) "record_id")))
        (is (= 1 (count (store/maintenance-log s)))))
      (testing "navigation-safety-concern commit drafts a record"
        (store/commit-record! s {:effect :concern/record :path ["clr-1"]})
        (is (= "JPN-CONCERN-000000" (get (first (store/concern-log s)) "record_id")))
        (is (= 1 (count (store/concern-log s)))))
      (testing "a second service-record commit for the SAME jurisdiction advances the sequence"
        (store/commit-record! s {:effect :service/log :path ["clr-2"]})
        (is (= 2 (count (store/service-log s))))
        (is (= "JPN-SERVICE-000001" (get (second (store/service-log s)) "record_id")))
        (is (= 2 (store/next-sequence s "JPN" :service))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/clearance s "nope")))
    (is (nil? (store/facility s "nope")))
    (is (= [] (store/all-clearances s)))
    (is (= [] (store/all-facilities s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/service-log s)))
    (is (zero? (store/next-sequence s "JPN" :service)))
    (store/with-clearances s {"x" {:id "x" :vessel-name "MV Test" :berth-id "B-1"
                                   :jurisdiction "JPN" :registered? true :verified? true}})
    (is (= "MV Test" (:vessel-name (store/clearance s "x"))))
    (store/with-facilities s {"y" {:id "y" :name "Light 9" :kind "light"
                                   :jurisdiction "JPN" :registered? true}})
    (is (= "Light 9" (:name (store/facility s "y"))))))
