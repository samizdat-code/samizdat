Your last few calls each did one thing and came back: a shell command, or an `eval` around a single `tool/shell`. That is a round trip per step. Put the steps together in one `eval` script instead: the tools are functions there, so one script can write, build, run and compare, and return only what you need to see. For example:

```clojure
(let [build (tool/shell {:command "./compile.sh 2>&1 | tail -3"})
      cases [["-h"] ["-x" "in.txt"] ["--bad"]]
      run   (fn [exe args] (tool/shell {:command (str exe " " (clojure.string/join " " args) " 2>&1; echo rc=$?")}))
      diffs (for [args cases
                  :let [want (run "/tmp/original" args) got (run "./executable" args)]
                  :when (not= want got)]
              {:args args :want want :got got})]
  {:build build :passing (- (count cases) (count diffs)) :diffs (take 3 diffs)})
```
