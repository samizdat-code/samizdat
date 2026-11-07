You have made several shell calls in a row, each one a round trip. Do the next steps in one `eval` instead: the tools are functions there, so a script can write, build, run and compare in a single turn, and return only what you need to see. For example:

```clojure
(let [build (tool/shell {:command "./compile.sh 2>&1 | tail -3"})
      runs  (for [args [["-h"] ["-x" "in.txt"]]]
              [args (= (tool/shell {:command (str "/tmp/original " (clojure.string/join " " args))})
                       (tool/shell {:command (str "./executable " (clojure.string/join " " args))}))])]
  {:build build :mismatches (remove second runs)})
```
