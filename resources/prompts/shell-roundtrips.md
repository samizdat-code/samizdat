Your last few calls each did one thing and came back: a shell command, or an `eval` around a single program. That is a round trip per step. Put the steps together in one `eval` script instead: `sh`, `cat`, `grep`, `glob` and `spit` are functions there that return values, so one script can write, build, run and compare, and return only what you need to see. For example:

```clojure
(let [build (sh* "./compile.sh")
      cases [["-h"] ["-x" "in.txt"] ["--bad"]]
      run   (fn [exe args] (apply sh* exe args))
      diffs (for [args cases
                  :let [want (run "/tmp/original" args) got (run "./executable" args)]
                  :when (not= want got)]
              {:args args :want want :got got})]
  {:build (:exit build) :errors (take-last 3 (clojure.string/split-lines (:err build)))
   :passing (- (count cases) (count diffs)) :diffs (take 3 diffs)})
```
