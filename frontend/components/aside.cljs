(ns frontend.components.aside
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [frontend.async :refer [put!]]
            [frontend.components.common :as common]
            [frontend.models.build :as build-model]
            [frontend.models.project :as project-model]
            [frontend.routes :as routes]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.github :as gh-utils]
            [frontend.utils.vcs-url :as vcs-url]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [sablono.core :as html :refer-macros [html]]))

(defn status-ico-name [build]
  (case (:status build)
    "running" :busy-light

    "success" :pass-light
    "fixed"   :pass-light

    "failed"   :fail-light
    "canceled" :fail-light
    "timedout" :fail-light

    "queued"      :hold-light
    "not_running" :hold-light
    "retried"     :hold-light
    "scheduled"   :hold-light

    "no_tests"            :stop-light
    "not_run"             :stop-light
    "infrastructure_fail" :stop-light
    "killed"              :stop-light

    :none-light))

(defn sidebar-build [build {:keys [org repo branch latest?]}]
  [:a {:class (when latest? "latest")
       :href (routes/v1-build-path org repo (:build_num build))
       :title (str (build-model/status-words build) ": " (:build_num build))}
   (common/ico (status-ico-name build))])

(defn branch [data owner]
  (reify
    om/IRender
    (render [_]
      (let [{:keys [org repo branch-data]} data
            [name-kw branch-builds] branch-data
            display-builds (take 5 (sort-by (comp - :build_num) (concat (:running_builds branch-builds)
                                                                  (:recent_builds branch-builds))))]
        (html
         [:li {:class (-> display-builds last :status)}
          [:div.branch
           {:role "button"}
           [:a {:href (routes/v1-project-branch-dashboard {:org org :repo repo :branch (name name-kw)})
                :title (name name-kw)}
            (-> name-kw name js/decodeURIComponent (utils/trim-middle 23))]]
          [:div.status {:role "button"}
           (for [build display-builds]
             (sidebar-build build {:org org :repo repo :branch (name name-kw)}))]])))))

(defn project-aside [data owner]
  (reify
    om/IRender
    (render [_]
      (let [user (:user data)
            project (:project data)
            controls-ch (om/get-shared owner [:comms :controls])
            settings (:settings data)
            project-id (project-model/id project)
            vcs-url (:vcs_url project)
            org (vcs-url/org-name vcs-url)
            repo (vcs-url/repo-name vcs-url)
            personal-branches (project-model/personal-branches user project)
            branches-filter (if (:show-all-branches settings) identity (partial project-model/personal-branch? user project))]
        (html
         [:ul {:class (when-not (get-in settings [:projects project-id :branches-collapsed]) "open")}
          [:li
           [:div.project {:role "button"}
            [:a.toggle {:title "show/hide"
                        :on-click #(put! controls-ch [:collapse-branches-toggled {:project-id project-id}])}
             (common/ico :repo)]

            [:a.title {:href (routes/v1-project-dashboard {:org org
                                                           :repo repo})
                       :title (project-model/project-name project)}
             (project-model/project-name project)]
            (when-let [latest-master-build (first (project-model/master-builds project))]
              (sidebar-build latest-master-build {:org org :repo repo :branch (name (:default_branch project)) :latest? true}))]]
          (for [branch-data (filter branches-filter (:branches project))]
            (om/build branch
                      {:branch-data branch-data
                       :org org
                       :repo repo}
                      {:react-key (first branch-data)}))])))))

(defn aside [app owner]
  (reify
    om/IRender
    (render [_]
      (let [controls-ch (om/get-shared owner [:comms :controls])
            projects (get-in app state/projects-path)
            settings (get-in app state/settings-path)
            user (:current-user app)]
        (html/html
         ;; XXX: browser settings
         [:nav.aside-left-nav {:class (when (:slim-aside? settings) "slim")}
          [:a.aside-item.logo
           ;; XXX: tooltips
           {:data-bind "tooltip: {title: 'Home', placement: 'right', trigger: 'hover'}"}
           [:div.logomark
            (common/ico :logo)]]
          [:div.aside-activity {:class (when-not (:slim-aside? settings) "open")}
           [:div.wrapper
            [:div.toggle-all-branches
             [:button {:class (when-not (:show-all-branches (utils/inspect settings)) "active")
                       :on-click #(put! controls-ch [:show-all-branches-toggled false])}
              "You"]
             [:button {:class (when (:show-all-branches settings) "active")
                       :on-click #(put! controls-ch [:show-all-branches-toggled true])}
              "All"]]
            (for [project projects]
              (om/build project-aside
                        {:project project
                         :user user
                         :settings settings}
                        {:react-key (project-model/id project)}))]]
          [:a#add-projects.aside-item {:href "/add-projects",
                                       :data-bind "tooltip: {title: 'Add Projects', placement: 'right', trigger: 'hover'}"}
           [:i.fa.fa-plus-circle]
           [:span "Add Projects"]]
          [:a.aside-item {:href "#",
                          :data-bind "tooltip: {title: 'Invite Teammate', placement: 'right', trigger: 'hover'}"
                          :data-toggle "modal"
                          :data-target "#inviteForm"}
           [:i.fa.fa-envelope-o]
           [:span "Invite Teammate"]]

          [:div.aside-slideup
           [:a.aside-item {:href "/account"
                           :data-bind "tooltip: {title: 'User Account', placement: 'right', trigger: 'hover'}"}
            [:img {:src (gh-utils/gravatar-url {:gravatar_id (:gravatar_id user)
                                                :login (:login user)
                                                :size 50})}]
            (:login user)]
           [:a.aside-item {:href "/logout"
                           :data-bind "tooltip: {title: 'Logout', placement: 'right', trigger: 'hover'}"}
            [:i.fa.fa-sign-out]
            [:span "Logout"]]
           [:a.aside-item {:href "#"
                           :data-bind "tooltip: {title: 'Expand', placement: 'right', trigger: 'hover'}"
                           :on-click #(put! controls-ch [:slim-aside-toggled])}
            (if (:slim-aside? settings)
              [:i.fa.fa-long-arrow-right]
              (list
               [:i.fa.fa-long-arrow-left]
               [:span "Collapse"]))]]])))))
