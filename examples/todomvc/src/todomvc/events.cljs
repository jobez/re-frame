(ns todomvc.events
  (:require
    [todomvc.db    :refer [default-value localstore->todos todos->local-store]]
    [re-frame.core :refer [reg-event-db path trim-v after debug]]
    [cljs.spec     :as s]))


;; -- Interceptors --------------------------------------------------------------
;;
;; XXX Add URL for docs here
;; XXX Ask Stu to figure out first time spec error

(defn check-and-throw
  "throw an exception if db doesn't match the spec."
  [a-spec db]
  (when-not (s/valid? a-spec db)
    (throw (ex-info "spec check failed: " {:problems
                                             (s/explain-str a-spec db)}))))

;; Event handlers change state, that's their job. But what happens if there's
;; a bug which corrupts app state in some subtle way? This interceptor is run after
;; each event handler has finished, and it checks app-db against a spec.  This
;; helps us detect event handler bugs early.
(def check-spec-interceptor (after (partial check-and-throw :todomvc.db/db)))


(def ->local-store (after todos->local-store))    ;; interceptor to store todos into local storage


;; interceptors for any handler that manipulates todos
(def todo-interceptors [check-spec-interceptor   ;; ensure the spec is still valid
                        (path :todos)   ;; 1st param to handler will be the value from this path
                        ->local-store                          ;; write to localstore each time
                        (when ^boolean js/goog.DEBUG debug)    ;; look in your browser console
                        trim-v])                               ;; removes first (event id) element from the event vec


;; -- Helpers -----------------------------------------------------------------

(defn allocate-next-id
  "Returns the next todo id.
  Assumes todos are sorted.
  Returns one more than the current largest id."
  [todos]
  ((fnil inc 0) (last (keys todos))))


;; -- Event Handlers ----------------------------------------------------------

;; XXX make localstore a coeffect interceptor

                                  ;; usage:  (dispatch [:initialise-db])
(reg-event-db                     ;; on app startup, create initial state
  :initialise-db                  ;; event id being handled
  check-spec-interceptor          ;; after the event handler runs, check that app-db matches the spec
  (fn [_ _]                       ;; the handler being registered
    (merge default-value (localstore->todos))))  ;; all hail the new state


                                  ;; usage:  (dispatch [:set-showing  :active])
(reg-event-db                     ;; this handler changes the todo filter
  :set-showing                    ;; event-id
  [check-spec-interceptor (path :showing) trim-v]    ;; this colelction of interceptors wrap wrap the handler

  ;; Because of the path interceptor above, the 1st parameter to
  ;; the handler below won't be the entire 'db', and instead will
  ;; be the value at a certain path within db, namely :showing.
  ;; Also, the use of the 'trim-v' interceptor means we can omit
  ;; the leading underscore from the 2nd parameter (event vector).
  (fn [old-keyword [new-filter-kw]]  ;; handler
    new-filter-kw))                  ;; return new state for the path


                                  ;; usage:  (dispatch [:add-todo  "Finsih comments"])
(reg-event-db                     ;; given the text, create a new todo
  :add-todo
  todo-interceptors
  (fn [todos [text]]              ;; the "path" interceptor in `todo-interceptors` means 1st parameter is :todos
    (let [id (allocate-next-id todos)]
      (assoc todos id {:id id :title text :done false}))))


(reg-event-db
  :toggle-done
  todo-interceptors
  (fn [todos [id]]
    (update-in todos [id :done] not)))


(reg-event-db
  :save
  todo-interceptors
  (fn [todos [id title]]
    (assoc-in todos [id :title] title)))


(reg-event-db
  :delete-todo
  todo-interceptors
  (fn [todos [id]]
    (dissoc todos id)))


(reg-event-db
  :clear-completed
  todo-interceptors
  (fn [todos _]
    (->> (vals todos)                ;; find the ids of all todos where :done is true
         (filter :done)
         (map :id)
         (reduce dissoc todos))))    ;; now do the delete of these done ids


(reg-event-db
  :complete-all-toggle
  todo-interceptors
  (fn [todos _]
    (let [new-done (not-every? :done (vals todos))]   ;; toggle true or false?
      (reduce #(assoc-in %1 [%2 :done] new-done)
              todos
              (keys todos)))))
