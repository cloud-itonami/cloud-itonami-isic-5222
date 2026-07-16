(ns portauthority.advisor
  "PortAuthorityAdvisor -- the *contained intelligence node* for the
  port/harbor-support-services operations-coordination actor.

  It drafts pilotage/towage/berth-assignment service-log entries,
  berth/tug/pilot scheduling proposals, navigation-aid/facility
  maintenance-coordination proposals, and navigation-safety-concern
  flags. CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal* (with a rationale + the fields it cited), never a
  committed record and never a berth-safety clearance, pilotage
  waiver or vessel-movement authorization. Every output is censored
  downstream by `portauthority.governor` before anything touches the
  SSoT.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  IMPORTANT (self-tripping-bug discipline, this repo's own governor
  history -- see `portauthority.governor`): none of the rationale text
  below uses a finalize/declare/override/waive/authorize/grant/issue
  verb next to 'clearance'/'pilotage'/'berth safe'/'navigation
  clearance'/'vessel movement' -- every disclaimer is phrased as what
  this proposal does NOT do, using verbs the governor's own
  scope-exclusion patterns do not scan for, so a legitimate default
  proposal never matches its own governor's finalization-action
  patterns. `test/portauthority/scope_exclusion_test.clj` asserts this
  directly for every op below.

  Proposal shape (all ops):
    {:operation  kw             ; one of the closed op-allowlist
     :effect     :propose       ; ALWAYS :propose -- structurally checked too
     :target-id  str            ; the clearance/facility id
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :confidence 0..1}"
  (:require [portauthority.store :as store]))

(defn advise-log-service-record
  "Draft a pilotage/towage/berth-assignment SERVICE-LOG proposal --
  administrative data logging only."
  [db {:keys [target-id detail]}]
  (let [c (store/clearance db target-id)]
    {:operation :log-service-record
     :effect :propose
     :target-id target-id
     :detail detail
     :summary (str target-id " のサービス記録(水先/曳船/バース割当)ログ提案")
     :rationale "Administrative logging of pilotage/towage/berth-assignment data only; no navigation-safety determination is made by this proposal."
     :confidence (if c 0.95 0.2)}))

(defn advise-schedule-port-operation
  "Draft a berth/tug/pilot SCHEDULING proposal -- a proposal only."
  [db {:keys [target-id resource-request]}]
  (let [c (store/clearance db target-id)]
    {:operation :schedule-port-operation
     :effect :propose
     :target-id target-id
     :resource-request resource-request
     :summary (str target-id " 向けバース/曳船/水先人スケジューリング提案")
     :rationale "Berth/tug/pilot scheduling proposal only; does not commit any vessel movement and makes no navigation-safety determination."
     :confidence (if c 0.9 0.2)}))

(defn advise-flag-navigation-safety-concern
  "Draft a navigation-safety-concern flag -- ALWAYS escalates to human
  sign-off. This is a REAL-WORLD safety-relevant signal (a reported
  berth hazard or navigation-aid fault), never a draft the actor may
  auto-run and never itself a berth or navigation-aid status change.
  See `portauthority.phase`: no phase ever adds this op to a phase's
  `:auto` set; the governor also always escalates on this op. Two
  independent layers agree, deliberately."
  [db {:keys [target-id concern-type description]}]
  (let [c (store/clearance db target-id)]
    {:operation :flag-navigation-safety-concern
     :effect :propose
     :target-id target-id
     :concern-type concern-type
     :description description
     :summary (str target-id " について航行安全上の懸念(" concern-type ")を提起")
     :rationale "Surfaces a navigation-safety concern for mandatory human review; takes no independent action on berth or navigation-aid status."
     :confidence (if c 0.98 0.5)}))

(defn advise-coordinate-maintenance
  "Draft a navigation-aid/facility MAINTENANCE-COORDINATION proposal
  -- coordination only, never a fitness certification."
  [db {:keys [target-id maintenance-type]}]
  (let [f (store/facility db target-id)]
    {:operation :coordinate-maintenance
     :effect :propose
     :target-id target-id
     :maintenance-type maintenance-type
     :summary (str target-id " の保守調整(" maintenance-type ")提案")
     :rationale "Navigation-aid/facility maintenance coordination only; does not certify navigation-aid fitness or navigation-safety status."
     :confidence (if f 0.9 0.2)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :target-id id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :log-service-record             (advise-log-service-record db request)
    :schedule-port-operation        (advise-schedule-port-operation db request)
    :flag-navigation-safety-concern (advise-flag-navigation-safety-concern db request)
    :coordinate-maintenance         (advise-coordinate-maintenance db request)
    {:operation :noop :effect :propose :target-id nil
     :summary "未対応の操作" :rationale (str op) :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :advisor-proposal
   :op         (:op request)
   :target-id  (:target-id request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :confidence (:confidence proposal)})
