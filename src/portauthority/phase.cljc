(ns portauthority.phase
  "Phase 0->3 staged rollout for the port/harbor-support-services
  operations-coordination actor.

    Phase 0  read-only            -- no writes, still governor-gated.
    Phase 1  assisted-logging     -- service-record logging and
                                      navigation-safety-concern flagging
                                      allowed, every write needs human
                                      approval.
    Phase 2  assisted-scheduling  -- adds berth/tug/pilot scheduling
                                      proposal writes, still approval.
    Phase 3  supervised-auto      -- governor-clean, high-confidence
                                      `:log-service-record` (pure
                                      administrative logging, no
                                      capital risk, no navigation-
                                      safety determination) may
                                      auto-commit. `:schedule-port-
                                      operation` and `:coordinate-
                                      maintenance` (real resource
                                      commitments -- berth/tug/pilot
                                      assignment, maintenance dispatch)
                                      ALWAYS need human approval, even
                                      when governor-clean, at every
                                      phase including 3.

  `:flag-navigation-safety-concern` is deliberately ABSENT from every
  phase's `:auto` set, including phase 3 -- a permanent structural
  fact, not a rollout milestone still to come. Surfacing a reported
  berth hazard or navigation-aid fault ALWAYS reaches a human; this
  actor never itself acts on the concern (`portauthority.governor`'s
  `high-stakes` set enforces the same invariant independently -- two
  layers, not one, agree on this). Likewise, no op that would finalize
  a berth/navigation-safety clearance decision exists ANYWHERE in this
  domain's op set at all (see `portauthority.governor`'s closed
  op-allowlist + `finalize-clearance-violations`), so there is no
  entry to accidentally add to `:auto` in the first place -- the
  strongest possible form of 'never auto-commit-eligible'.")

(def read-ops  #{})
(def write-ops #{:log-service-record :schedule-port-operation
                 :flag-navigation-safety-concern :coordinate-maintenance})

;; NOTE the invariant: `:flag-navigation-safety-concern` is a member of
;; `write-ops` (governor-gated like any write) but is NEVER a member of
;; any phase's `:auto` set below. Do not add it there. Likewise no op
;; that finalizes a clearance/pilotage/vessel-movement decision exists
;; in `write-ops` at all -- see the namespace docstring.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed
  to auto-commit when governor-clean>}."
  {0 {:label "read-only"           :writes #{}                                                                       :auto #{}}
   1 {:label "assisted-logging"    :writes #{:log-service-record :flag-navigation-safety-concern}                    :auto #{}}
   2 {:label "assisted-scheduling" :writes #{:log-service-record :flag-navigation-safety-concern :schedule-port-operation} :auto #{}}
   3 {:label "supervised-auto"     :writes write-ops
      :auto #{:log-service-record}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:flag-navigation-safety-concern` is never auto-eligible at any
    phase, so it always escalates once the governor clears it (or
    holds if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Port Authority Governor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
