(ns portauthority.registry
  "Pure-function record construction for the port/harbor-support-
  services operations-coordination actor -- an append-only
  book-of-record draft for each of the four coordination ops.

  Like every sibling actor's registry, there is no single
  international reference-number standard for a service-log entry, a
  scheduling proposal, a maintenance-coordination record or a
  navigation-safety-concern record -- every port authority/harbor
  master assigns its own reference format. This namespace does NOT
  invent one beyond a jurisdiction-scoped sequence number; it drafts
  the record's required fields honestly, the same non-fabricating
  discipline every sibling registry uses.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real VTS / AIS / port-community system. It builds the
  RECORD an operator would keep, not a real-world act. Every record
  produced here is UNSIGNED and explicitly `:kind :*-draft` -- it is
  never, itself, a berth-safety clearance, a pilotage waiver or a
  vessel-movement authorization. That authority stays outside this
  actor entirely (see `portauthority.governor`'s
  `finalize-clearance-violations`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the operator's act, not this actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn- require-field! [op-label field-name v]
  (when-not (and v (not= v ""))
    (throw (ex-info (str op-label ": " field-name " required") {}))))

(defn- draft-record
  "Shared record-draft shape for all four coordination record kinds.
  `label` names the record kind (e.g. \"service-log\"); `target-id` is
  the clearance/facility id the record concerns."
  [label prefix target-id jurisdiction sequence]
  (require-field! label "target_id" target-id)
  (require-field! label "jurisdiction" jurisdiction)
  (when (< sequence 0)
    (throw (ex-info (str label ": sequence must be >= 0") {})))
  (let [record-number (str (str/upper-case jurisdiction) "-" prefix "-" (zero-pad sequence 6))
        record {"record_id" record-number
                "kind" (str label "-draft")
                "target_id" target-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "record_number" record-number
     "certificate" (unsigned-certificate label record-number record-number)}))

(defn register-service-record
  "Draft a pilotage/towage/berth-assignment SERVICE-LOG entry --
  administrative record-keeping only, never a clearance decision."
  [clearance-id jurisdiction sequence]
  (draft-record "service-log" "SERVICE" clearance-id jurisdiction sequence))

(defn register-schedule-record
  "Draft a berth/tug/pilot SCHEDULING PROPOSAL -- a proposal only,
  never a vessel-movement authorization."
  [clearance-id jurisdiction sequence]
  (draft-record "schedule-proposal" "SCHEDULE" clearance-id jurisdiction sequence))

(defn register-maintenance-record
  "Draft a navigation-aid/facility MAINTENANCE-COORDINATION record --
  coordination only, never a navigation-aid fitness certification."
  [facility-id jurisdiction sequence]
  (draft-record "maintenance-coordination" "MAINT" facility-id jurisdiction sequence))

(defn register-concern-record
  "Draft the record of a NAVIGATION-SAFETY-CONCERN flag, written only
  after mandatory human sign-off -- the flag itself, never a berth or
  navigation-aid status change."
  [clearance-id jurisdiction sequence]
  (draft-record "navigation-safety-concern" "CONCERN" clearance-id jurisdiction sequence))

(defn append [history result]
  (conj (vec history) (get result "record")))
