(ns open-ports.app
  "cloud-itonami-isic-5222 (etzhayyim-project-open-ports) Worker appview —
  reagent + re-frame, view built from jp-go-dds (デジタル庁デザインシステム)
  hiccup.

  Faithful port of the previous SvelteKit scaffold's status page
  (`worker/svelte/src/routes/+page.svelte`, 84 lines): a static display of
  this Worker's own declared surface — title / project / kind, route count
  + list, wrangler var keys, an XRPC-enabled flag, and its own source path.
  Every field below mirrors the constant `app` object `+page.svelte` held
  in its <script> block, with two kinds of deliberate change (nothing here
  is invented beyond these, and nothing is simplified away):

  - `:app/relative-path` now names this file, not the deleted Svelte one.
  - `:app/xrpc?` is now false. This migration's `worker/wrangler.jsonc`
    drops `main` (see that file's header comment and the repo README):
    neither Worker source left in this repo calls `env.ASSETS.fetch`, so
    the XRPC route this page used to advertise as enabled no longer
    deploys. The XRPC handler itself is preserved byte-for-byte at
    `worker/src/xrpc-mcp-router-proxy.ts` (moved, not deleted, from
    `worker/svelte/src/routes/xrpc/[...path]/+server.ts`) — backend
    Worker/XRPC code is out of scope for a frontend migration.
  - `:app/route-count` and `:app/routes`, and `:app/vars`, are NOT copied
    from the Svelte constant, which held `routeCount: 0`, `routes: []` and
    `vars: []` — stale relative to `worker/wrangler.jsonc`, which already
    declared one route (`open-ports.etzhayyim.com/*`) and four vars
    (`AGENTGATEWAY_MCP_ROUTER_URL`, `APP_FRAMEWORK`, `APP_HANDLE`,
    `PRIMARY_DID`). This namespace reports what `wrangler.jsonc` actually
    declares instead of repeating the page's pre-existing drift.

  `public/index.html`'s inlined <style> was produced once, at authoring
  time, by `jp-go-dds.page/->page` running on the JVM (via this deps.edn's
  jp-go-dds git/sha), concatenating the vendored `dds.css` with
  `jp-go-dds.core/ext-css` — exactly what `jp-go-dds.page/page` composes
  for its own <style> block. This namespace only requires
  `jp-go-dds.core` — the browser bundle does not need `jp-go-dds.page` or
  `html.core` at runtime; those are JVM-only tools used to author the
  static shell once. Regenerate that shell (e.g. if jp-go-dds's core
  components or ext-rules change) with:

    (require '[jp-go-dds.page :as page] '[clojure.java.io :as io])
    (spit \"public/index.html\"
          (page/->page {:title \"etzhayyim-project-open-ports\"
                         :lang \"ja\"
                         :description \"Ai etzhayyim Open Ports Worker appview (reagent + re-frame + jp-go-dds).\"
                         :css (slurp (io/resource \"jp_go_dds/dds.css\"))}
                        [:div {:id \"app\"} \"etzhayyim-project-open-ports loading…\"]
                        [:script {:src \"js/app.js\"}]))

  (then add back the `<noscript>etzhayyim-project-open-ports requires
  JavaScript.</noscript>` line the regen recipe above does not emit, same
  as app-warehouse's shell)."
  (:require [reagent.dom :as rdom]
            [re-frame.core :as rf]
            [jp-go-dds.core :as dds]))

;; -- db ------------------------------------------------------------------
;;
;; Same seven facts + own source path that `+page.svelte`'s `app` const
;; held (title/project/name/kind/routeCount/routes/vars/xrpc/relativePath),
;; with routeCount/routes/vars corrected to what wrangler.jsonc actually
;; declares (see namespace docstring).

(def default-db
  {:app/title "Worker"
   :app/project "etzhayyim-project-open-ports"
   :app/name "worker"
   :app/kind "worker"
   :app/route-count 1
   :app/routes ["open-ports.etzhayyim.com/*"]
   :app/vars ["AGENTGATEWAY_MCP_ROUTER_URL" "APP_FRAMEWORK" "APP_HANDLE" "PRIMARY_DID"]
   :app/xrpc? false
   :app/relative-path "worker/cljs/src/open_ports/app.cljs"})

(rf/reg-event-db
 :initialize-db
 (fn [_ _] default-db))

(rf/reg-sub :app/title (fn [db _] (:app/title db)))
(rf/reg-sub :app/project (fn [db _] (:app/project db)))
(rf/reg-sub :app/name (fn [db _] (:app/name db)))
(rf/reg-sub :app/kind (fn [db _] (:app/kind db)))
(rf/reg-sub :app/route-count (fn [db _] (:app/route-count db)))
(rf/reg-sub :app/routes (fn [db _] (:app/routes db)))
(rf/reg-sub :app/vars (fn [db _] (:app/vars db)))
(rf/reg-sub :app/xrpc? (fn [db _] (:app/xrpc? db)))
(rf/reg-sub :app/relative-path (fn [db _] (:app/relative-path db)))

;; -- view ------------------------------------------------------------------

(defn app-view []
  (let [title         @(rf/subscribe [:app/title])
        name          @(rf/subscribe [:app/name])
        kind          @(rf/subscribe [:app/kind])
        project       @(rf/subscribe [:app/project])
        route-count   @(rf/subscribe [:app/route-count])
        routes        @(rf/subscribe [:app/routes])
        vars          @(rf/subscribe [:app/vars])
        xrpc?         @(rf/subscribe [:app/xrpc?])
        relative-path @(rf/subscribe [:app/relative-path])]
    (dds/container

     [:section {:class "dds-ext-section"}
      [:p {:class "dds-ext-lead"} (str "Cloudflare " kind)]
      (dds/heading 1 title)
      [:span {:class "dads-u-mono-16N-150"} name]]

     [:section {:class "dds-ext-section"}
      (dds/grid {:min "12rem"}
        (dds/card [:p {:class "dds-ext-lead"} "Project"] [:strong project])
        (dds/card [:p {:class "dds-ext-lead"} "Routes"] [:strong (str route-count)])
        (dds/card [:p {:class "dds-ext-lead"} "XRPC"]
                  [:strong (if xrpc? "enabled" "not configured")]))]

     [:section {:class "dds-ext-section"}
      (dds/heading 2 "Public Routes" {:size "24"})
      (if (seq routes)
        (dds/card
         (into [:ul {:class "dds-ext-stack"}]
               (map (fn [r] [:li {:class "dads-u-mono-16N-150"} r]) routes)))
        [:p {:class "dds-ext-lead"} "No public route is declared next to this app surface."])]

     [:section {:class "dds-ext-section"}
      (dds/heading 2 "Runtime Bindings" {:size "24"})
      (if (seq vars)
        (into [:div {:class "dds-ext-row"}]
              (map (fn [v] (dds/chip-label v {:color "blue"})) vars))
        [:p {:class "dds-ext-lead"} "No public vars are declared in the nearest wrangler config."])]

     [:section {:class "dds-ext-section"}
      (dds/heading 2 "Source" {:size "24"})
      [:p {:class "dads-u-mono-16N-150"} relative-path]])))

;; -- mount -------------------------------------------------------------------

(defn render []
  (rdom/render [app-view] (.getElementById js/document "app")))

(defn ^:export main []
  (rf/dispatch-sync [:initialize-db])
  (render))
