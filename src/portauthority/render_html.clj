(ns portauthority.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave 6): this repo previously had NO demo page and no generator at
  all. This namespace drives the REAL actor stack
  (`portauthority.advisor` -> `portauthority.governor` ->
  `portauthority.phase` -> `portauthority.store`) as a compiled
  langgraph StateGraph (`portauthority.operation/build` +
  `langgraph.graph/run*`), through a scenario extended from this repo's
  own `portauthority.sim` demo driver (`clojure -M:dev:run`, run BEFORE
  writing this file to confirm the seeded ids `clr-1`..`clr-3` /
  `fac-1`..`fac-2` and the four coordination ops behave as documented).

  EVERY number, id, disposition, basis keyword and violation detail on
  the generated page is read back out of the real store/ledger or the
  real graph `:audit` channel. Nothing on the page is a literal an
  author typed -- including the gate table, which is derived from
  `governor/allowed-ops`, `governor/high-stakes`,
  `governor/facility-level-ops` and `phase/phases` rather than
  described by hand.

  Coverage: the scenario reaches every HARD rule this repo's governor
  actually has (`:clearance-unverified` in all three of its sub-forms,
  `:facility-unregistered`, `:effect-not-propose`, `:op-not-allowed`,
  `:finalize-clearance-attempt`), the phase gate's own `:phase-disabled`
  hold, the SOFT approval path in both outcomes (granted / rejected),
  and the one op this actor may auto-commit at phase 3. Rules 2-4 are
  proposal-shaped, so they are reached the only honest way: by swapping
  in a deliberately off-contract advisor (`rogue-advisor` below) --
  modelling a real LLM going off-contract -- while the governor, the
  phase gate, the graph and the store stay exactly as shipped.

  Determinism: no timestamps in page content, no random ids, no clock
  read anywhere. `store/seed-db` is a fresh MemStore per run and
  `registry` sequence numbers are jurisdiction-scoped counters, so two
  consecutive runs are byte-identical (verified with `cmp`).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.java.io :as io]
            [kotoba.lang.text :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [portauthority.advisor :as advisor]
            [portauthority.governor :as governor]
            [portauthority.operation :as op]
            [portauthority.phase :as phase]
            [portauthority.store :as store]))

;; ----------------------------- harness -----------------------------

(def ^:private operator
  "The human port-authority operator this run is attributed to. Phase 3
  (`supervised-auto`) -- the highest rollout phase this actor has."
  {:actor-id "op-1" :actor-role :port-authority-operator :phase 3})

(def ^:private early-phase-operator
  "Same operator, pinned to phase 1 (`assisted-logging`) -- used to
  reach `phase/gate`'s own `:phase-disabled` hold on an op that is
  governor-clean but not yet enabled by the rollout phase."
  (assoc operator :phase 1))

(defn- rogue-advisor
  "An advisor that produces this repo's real proposal via
  `advisor/infer` and then applies `f` to it -- i.e. a real LLM advisor
  that went off-contract in exactly one way. Injected through the
  actor's own `:advisor` seam (`portauthority.operation/build`), so the
  governor, the phase gate, the graph and the store are untouched: the
  hold the page shows is the shipped governor's own verdict on a
  malformed proposal, not a hand-written scenario."
  [f]
  (reify advisor/Advisor
    (-advise [_ st req] (f (advisor/infer st req)))))

(defn- exec!
  "One coordination operation = one graph run on thread `tid`."
  [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- resume!
  "Resume an interrupted (`interrupt-before #{:request-approval}`) run
  with a human decision."
  [actor tid status]
  (g/run* actor {:approval {:status status :by (:actor-id operator)}}
          {:thread-id tid :resume? true}))

(defn- scenario
  "Runs one labelled scenario and captures what the actor really
  emitted: the final graph state's `:audit` channel (advisor proposal,
  approval request, approval outcome, commit/hold fact) plus the final
  disposition. Nothing here is synthesised."
  [label actor tid request context & [approval-status]]
  (let [r1 (exec! actor tid request context)
        r2 (when approval-status (resume! actor tid approval-status))
        final (or r2 r1)]
    {:label       label
     :thread-id   tid
     :op          (:op request)
     :target-id   (:target-id request)
     :phase       (:phase context)
     :status      (:status final)
     :disposition (get-in final [:state :disposition])
     :verdict     (get-in final [:state :verdict])
     :audit       (vec (get-in final [:state :audit]))}))

(defn run-demo!
  "Runs a freshly seeded store through every disposition this actor can
  reach. Returns `{:db .. :runs [..]}` -- `:db` is the real store after
  the run, `:runs` the per-scenario record of what the graph actually
  emitted.

  Clean paths (4):
    clr-1 `:log-service-record`             -- governor-clean, phase 3
                                               `:auto` -> AUTO-COMMIT,
                                               no human involved.
    clr-1 `:schedule-port-operation`        -- clean but a real resource
                                               commitment -> escalate
                                               (`:phase-approval`) ->
                                               human approves -> commit.
    clr-1 `:flag-navigation-safety-concern` -- ALWAYS high-stakes ->
                                               escalate -> human signs
                                               off -> commit.
    fac-1 `:coordinate-maintenance`         -- facility-level, real
                                               dispatch -> escalate ->
                                               approve -> commit.

  Soft hold that DID reach a human (1):
    clr-1 `:schedule-port-operation` again  -- escalate -> human
                                               REJECTS -> hold.

  HARD holds that never reach a human (7):
    clr-2  `:clearance-unverified`      (registered, not verified)
    clr-3  `:clearance-unverified`      (not registered)
    clr-404 `:clearance-unverified`     (no clearance record at all)
    fac-2  `:facility-unregistered`     (facility-level check 1)
    clr-1  `:effect-not-propose`        (rogue advisor: effect :commit)
    clr-1  `:op-not-allowed`            (rogue advisor: op outside the
                                         closed allowlist)
    clr-1  `:finalize-clearance-attempt` (rogue advisor: a berth-safety
                                         FINALIZATION action smuggled
                                         into an otherwise legitimate
                                         proposal's rationale)

  Phase-gate hold that never reaches a human (1):
    fac-1 `:coordinate-maintenance` at phase 1 -- governor-clean but the
    op is not in phase 1's `:writes` -> `:phase-disabled`."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        ;; Each rogue actor shares the SAME store -- only the advisor differs.
        rogue-effect
        (op/build db {:advisor (rogue-advisor #(assoc % :effect :commit))})
        rogue-op
        (op/build db {:advisor (rogue-advisor #(assoc % :operation :finalize-berth-clearance))})
        rogue-text
        (op/build db {:advisor (rogue-advisor
                                #(assoc % :rationale
                                        (str "Scheduling is ready; recommend we finalize the "
                                             "berth safety clearance now so the call can proceed.")))})

        r-service   (scenario "clean service-record log (phase-3 auto-commit)"
                              actor "t1-service"
                              {:op :log-service-record :target-id "clr-1"
                               :detail "pilotage assist completed"}
                              operator)
        r-schedule  (scenario "berth/tug/pilot scheduling (approved)"
                              actor "t2-schedule"
                              {:op :schedule-port-operation :target-id "clr-1"
                               :resource-request {:berth "B-12" :tug 1 :pilot 1}}
                              operator :approved)
        r-concern   (scenario "navigation-safety concern flag (always escalates, signed off)"
                              actor "t3-concern"
                              {:op :flag-navigation-safety-concern :target-id "clr-1"
                               :concern-type "berth-hazard"
                               :description "reported debris near berth fender"}
                              operator :approved)
        r-maint     (scenario "navigation-aid maintenance coordination (approved)"
                              actor "t4-maintenance"
                              {:op :coordinate-maintenance :target-id "fac-1"
                               :maintenance-type "buoy-inspection"}
                              operator :approved)
        r-rejected  (scenario "second scheduling attempt (human REJECTED)"
                              actor "t5-schedule-rejected"
                              {:op :schedule-port-operation :target-id "clr-1"
                               :resource-request {:berth "B-12" :tug 2 :pilot 1}}
                              operator :rejected)
        r-unverif   (scenario "service log on an unverified clearance"
                              actor "t6-unverified"
                              {:op :log-service-record :target-id "clr-2"
                               :detail "towage assist"}
                              operator)
        r-unreg     (scenario "scheduling on an unregistered clearance"
                              actor "t7-unregistered"
                              {:op :schedule-port-operation :target-id "clr-3"
                               :resource-request {:berth "B-03"}}
                              operator)
        r-missing   (scenario "service log on a clearance that does not exist"
                              actor "t8-missing"
                              {:op :log-service-record :target-id "clr-404"
                               :detail "pilotage assist"}
                              operator)
        r-facunreg  (scenario "maintenance on an unregistered facility"
                              actor "t9-facility"
                              {:op :coordinate-maintenance :target-id "fac-2"
                               :maintenance-type "radar-calibration"}
                              operator)
        r-effect    (scenario "advisor returned effect :commit instead of :propose"
                              rogue-effect "t10-effect"
                              {:op :log-service-record :target-id "clr-1"
                               :detail "pilotage assist completed"}
                              operator)
        r-op        (scenario "advisor hallucinated an op outside the closed allowlist"
                              rogue-op "t11-op"
                              {:op :log-service-record :target-id "clr-1"
                               :detail "pilotage assist completed"}
                              operator)
        r-finalize  (scenario "advisor smuggled a berth-clearance finalization into its rationale"
                              rogue-text "t12-finalize"
                              {:op :schedule-port-operation :target-id "clr-1"
                               :resource-request {:berth "B-12"}}
                              operator)
        r-phase     (scenario "maintenance coordination attempted at phase 1"
                              actor "t13-phase"
                              {:op :coordinate-maintenance :target-id "fac-1"
                               :maintenance-type "buoy-inspection"}
                              early-phase-operator)]
    {:db db
     :runs [r-service r-schedule r-concern r-maint r-rejected
            r-unverif r-unreg r-missing r-facunreg
            r-effect r-op r-finalize r-phase]}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw [v] (if (keyword? v) (name v) (str v)))

(defn- code [v] (str "<code>" (esc (kw v)) "</code>"))

(defn- span [cls text] (str "<span class=\"" cls "\">" text "</span>"))

(defn- yes-no [b] (if b (span "ok" "yes") (span "critical" "no")))

(defn- row [cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>"
       (str/join (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lead body]
  (str "  <section class=\"card\">\n"
       "    <h2>" (esc title) "</h2>\n"
       (when lead (str "    <p class=\"muted\">" lead "</p>\n"))
       body
       "  </section>\n"))

(defn- sorted-kws [s] (sort-by name s))

;; ----------------------------- derived cells -----------------------------

(defn- hold-rules
  "The governor rule keywords behind a hold fact, straight from the
  fact's own `:basis` (which `governor/hold-fact` builds from the
  verdict's violations)."
  [f]
  (or (seq (:basis f))
      (seq (mapv :rule (:violations f)))))

(defn- disposition-cell [{:keys [disposition audit]}]
  (let [granted? (some #(= :approval-granted (:t %)) audit)
        rejected? (some #(= :approval-rejected (:t %)) audit)
        hold (last (filter #(#{:governor-hold :approval-rejected} (:t %)) audit))
        rules (hold-rules hold)]
    (cond
      (and (= :commit disposition) granted?) (span "ok" "approved &amp; committed")
      (= :commit disposition) (span "ok" "auto-committed")
      rejected? (span "warn" "rejected by approver")
      ;; A phase-gate hold is NOT a governor HARD violation -- it is the
      ;; rollout phase refusing an op the governor itself cleared. It
      ;; equally never reaches a human, so it belongs on this page, but
      ;; calling it a HARD hold would misreport which layer said no.
      (and (:phase-reason hold) (empty? rules))
      (span "critical" (str "phase hold &middot; " (esc (kw (:phase-reason hold)))))
      (seq rules)
      (span "critical" (str "HARD hold &middot; "
                            (esc (str/join ", " (map kw rules)))))
      :else (span "muted" (esc (str disposition))))))

(defn- human-cell
  "Did a human ever see this run? A HARD governor hold is written by
  the `:hold` node without ever entering `:request-approval`."
  [{:keys [audit]}]
  (if (some #(= :approval-requested (:t %)) audit)
    (span "warn" "yes &mdash; approval requested")
    (span "muted" "no &mdash; never reached a human")))

(defn- last-fact-for [ledger target-id]
  (last (filter #(= (:target-id %) target-id) ledger)))

(defn- status-cell [ledger target-id]
  (let [f (last-fact-for ledger target-id)
        rules (hold-rules f)]
    (cond
      (nil? f) (span "muted" "no ledger activity")
      (= :committed (:t f)) (span "ok" "committed")
      (:phase-reason f) (span "critical" (str "held &middot; " (esc (kw (:phase-reason f)))))
      (seq rules) (span "critical" (str "held &middot; "
                                        (esc (str/join ", " (map kw rules)))))
      :else (span "muted" (esc (kw (:t f)))))))

;; ----------------------------- sections -----------------------------

(defn- clearances-section [db ledger]
  (section
   "Vessel clearances (SSoT)"
   (str "Seeded from " (code 'portauthority.store/demo-data)
        ". <code>registered?</code>/<code>verified?</code> are re-derived from this "
        "store by the governor on every request &mdash; never taken from the advisor's "
        "self-report.")
   (table ["Clearance" "Vessel" "Berth" "Jurisdiction" "Registered" "Verified" "Last ledger fact"]
          (for [c (store/all-clearances db)]
            (row [(code (:id c)) (esc (:vessel-name c)) (esc (:berth-id c))
                  (esc (:jurisdiction c))
                  (yes-no (:registered? c)) (yes-no (:verified? c))
                  (status-cell ledger (:id c))])))))

(defn- facilities-section [db ledger]
  (section
   "Port facilities / navigation aids (SSoT)"
   (str "Targets of " (code :coordinate-maintenance)
        " &mdash; a facility-level op, exempt from per-vessel clearance verification. "
        "The governor independently re-verifies the FACILITY is registered instead.")
   (table ["Facility" "Name" "Kind" "Jurisdiction" "Registered" "Last ledger fact"]
          (for [f (store/all-facilities db)]
            (row [(code (:id f)) (esc (:name f)) (esc (:kind f))
                  (esc (:jurisdiction f))
                  (yes-no (:registered? f))
                  (status-cell ledger (:id f))])))))

(defn- gate-section []
  (section
   "Action gate (Port Authority Governor + rollout phase)"
   (str "Derived at build time from " (code 'portauthority.governor/allowed-ops) ", "
        (code 'portauthority.governor/high-stakes) ", "
        (code 'portauthority.governor/facility-level-ops) " and "
        (code 'portauthority.phase/phases) " &mdash; not a hand-written description. "
        "Confidence floor: " (code governor/confidence-floor) ".")
   (table ["Op" "Target kind" "Always human?" "Auto-commit at phase 3?" "Enabled from phase"]
          (for [o (sorted-kws governor/allowed-ops)]
            (let [facility? (contains? governor/facility-level-ops o)
                  stakes? (contains? governor/high-stakes o)
                  auto? (contains? (get-in phase/phases [3 :auto]) o)
                  from (first (for [p (sort (keys phase/phases))
                                    :when (contains? (get-in phase/phases [p :writes]) o)]
                                p))]
              (row [(code o)
                    (if facility? (esc "facility") (esc "clearance"))
                    (if stakes? (span "warn" "yes &mdash; high-stakes") (esc "no"))
                    (if auto? (span "ok" "yes") (span "warn" "no &mdash; human approval"))
                    (if from
                      (esc (str "phase " from " (" (get-in phase/phases [from :label]) ")"))
                      (span "muted" "never"))]))))))

(defn- hard-rules-section [runs]
  (let [holds (for [r runs
                    f (:audit r)
                    :when (= :governor-hold (:t f))
                    :let [rules (hold-rules f)]
                    rule (or rules [(:phase-reason f)])
                    :when rule]
                {:rule rule :run r :fact f})
        by-rule (sort-by (comp name key) (group-by :rule holds))]
    (section
     "Rule coverage (this run)"
     (str "Every rule below was reached by a real graph run in this build. "
          "The detail text is the deciding layer's own, copied out of the hold fact's "
          (code :violations) " &mdash; not restated here. "
          "All five of this governor's HARD rules plus the rollout phase gate appear.")
     (table ["Rule" "Decided by" "Threads" "Detail(s) emitted"]
            (for [[rule hs] by-rule
                  :let [phase-only? (every? #(empty? (:basis (:fact %))) hs)
                        details (->> hs
                                     (map (fn [{:keys [fact]}]
                                            (or (some->> (:violations fact)
                                                         (filter #(= rule (:rule %)))
                                                         first :detail)
                                                (str "phase " (:phase fact)
                                                     " does not enable this op for writes"))))
                                     distinct)]]
              (row [(code rule)
                    (if phase-only?
                      (span "warn" (esc "rollout phase gate"))
                      (span "critical" (esc "governor (HARD)")))
                    (str/join ", " (map #(code (:thread-id (:run %))) hs))
                    (str/join "<br>" (map esc details))]))))))

(defn- runs-section [runs]
  (section
   "Scenario runs (real graph executions)"
   (str "One row = one " (code 'langgraph.graph/run*) " on its own thread. "
        "<em>Disposition</em> and <em>Saw a human?</em> are read out of the final graph "
        "state's " (code :audit) " channel.")
   (table ["#" "Scenario" "Thread" "Op" "Target" "Phase" "Disposition" "Saw a human?"]
          (map-indexed
           (fn [i r]
             (row [(esc (str (inc i)))
                   (esc (:label r))
                   (code (:thread-id r))
                   (code (:op r))
                   (code (:target-id r))
                   (esc (str (:phase r)))
                   (disposition-cell r)
                   (human-cell r)]))
           runs))))

(defn- ledger-section [ledger]
  (section
   "Audit ledger (append-only, this run)"
   (str "The store's own immutable decision log &mdash; " (esc (str (count ledger)))
        " facts, in the order the actor wrote them. Only the "
        (code :commit) " and " (code :hold) " nodes ever append here.")
   (table ["#" "Fact" "Op" "Target" "Disposition" "Basis / phase reason" "Confidence"]
          (map-indexed
           (fn [i f]
             (row [(esc (str (inc i)))
                   (code (:t f))
                   (code (:op f))
                   (code (:target-id f))
                   (esc (kw (:disposition f)))
                   (cond
                     (seq (hold-rules f))
                     (span "critical" (esc (str/join ", " (map kw (hold-rules f)))))
                     (:phase-reason f)
                     (span "critical" (esc (kw (:phase-reason f))))
                     :else (span "muted" "&mdash;"))
                   (esc (str (:confidence f)))]))
           ledger))))

(defn- records-section [db]
  (let [logs [["service-log" (store/service-log db)]
              ["schedule-log" (store/schedule-log db)]
              ["maintenance-log" (store/maintenance-log db)]
              ["concern-log" (store/concern-log db)]]
        rows (for [[log-name records] logs
                   r records]
               (row [(esc log-name)
                     (code (get r "record_id"))
                     (esc (get r "kind"))
                     (code (get r "target_id"))
                     (esc (get r "jurisdiction"))
                     (yes-no (get r "immutable"))]))]
    (section
     "Committed record drafts"
     (str "Produced by " (code 'portauthority.registry) " on commit. Every record is "
          "UNSIGNED and explicitly a <em>draft</em> &mdash; it is never itself a "
          "berth-safety clearance, a pilotage waiver or a vessel-movement "
          "authorization. Record numbers are jurisdiction-scoped sequences, which is "
          "why this page is byte-identical across reruns.")
     (if (seq rows)
       (table ["Log" "Record id" "Kind" "Target" "Jurisdiction" "Immutable"] rows)
       "    <p class=\"critical\">no records committed</p>\n"))))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole operator console from a completed `run-demo!`
  result. Reads only real store/ledger/graph output."
  [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))
        holds (filter #(= :governor-hold (:t %)) ledger)
        commits (filter #(= :committed (:t %)) ledger)
        approvals (for [r runs f (:audit r) :when (= :approval-granted (:t f))] f)
        hold-bases (->> holds
                        (mapcat #(or (hold-rules %) [(:phase-reason %)]))
                        (remove nil?)
                        (map kw)
                        distinct
                        sort)]
    (str
     "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-5222 &middot; port &amp; harbor support services &mdash; Operator Console</title>\n"
     "<style>\n" (jp-go-dds.skin/dds+skin) "\n</style>\n"
     "</head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Port &amp; harbor support services (ISIC 5222) &mdash; Operator Console</h1>\n"
     "  <span class=\"badge\">operations coordination &middot; never a navigation-safety authority</span>\n"
     "</header>\n"
     "<main>\n"
     (section
      "This page"
      (str "Generated at build time by " (code 'portauthority.render-html)
           " (<code>clojure -M:dev:render-html</code>) by actually running "
           (esc (str (count runs)))
           " coordination operations through the shipped actor graph. "
           "Nothing below is mock HTML: the tables are the real store, the real "
           "append-only ledger, and the real graph audit channel.")
      (table ["Measure" "Value"]
             [(row [(esc "scenario runs") (esc (str (count runs)))])
              (row [(esc "ledger facts") (esc (str (count ledger)))])
              (row [(esc "committed") (span "ok" (esc (str (count commits))))])
              (row [(esc "human approvals granted") (esc (str (count approvals)))])
              (row [(esc "governor holds") (span "critical" (esc (str (count holds))))])
              (row [(esc "distinct hold bases")
                    (span "critical" (esc (str/join ", " hold-bases)))])]))
     (runs-section runs)
     (hard-rules-section runs)
     (gate-section)
     (clearances-section db ledger)
     (facilities-section db ledger)
     (ledger-section ledger)
     (records-section db)
     "</main>\n"
     "<footer>\n"
     "  <p>Read-only sample. Regenerate with <code>clojure -M:dev:render-html</code>. "
     "Deterministic: no timestamps, no random ids &mdash; two consecutive runs are "
     "byte-identical.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        ledger (vec (store/ledger db))
        holds (filter #(= :governor-hold (:t %)) ledger)
        approvals (for [r runs f (:audit r) :when (= :approval-granted (:t f))] f)]
    ;; Build-time invariant, not a convention: a console that shows no
    ;; governor hold is not evidence of a governed actor.
    (when (zero? (count holds))
      (throw (ex-info (str "refusing to write " out
                           ": the scenario produced ZERO :governor-hold ledger facts. "
                           "This console exists to show that the Port Authority Governor "
                           "can actually reject a proposal; a run with no hold proves "
                           "nothing. Fix the scenario in run-demo!, not this check.")
                      {:out out :ledger-facts (count ledger) :holds 0})))
    (io/make-parents out)
    (spit out (render result))
    (println "wrote" out
             (str "(" (count runs) " runs, " (count ledger) " ledger facts, "
                  (count holds) " governor holds, "
                  (count approvals) " human approvals, "
                  (count (filter #(= :committed (:t %)) ledger)) " commits)"))))
