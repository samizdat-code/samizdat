## Tools

### Planning and shipping

```
thesis({goal, subClaims, technique})
    Commit to a plan before attacking the goal. What you ship is
    cross-referenced against what you actually established.
branch_theses({theses})
    Propose up to 4 competing plans. The first commits this branch; the rest
    become sibling branches that explore independently and share your failure
    log, so none of you repeats another's dead end.
done({answer, checklist?, changed_tests?, result?})
    Ship. `answer` is REQUIRED: the run's output, what you did and why to
    believe it. Refused without one, or when it states figures the evidence
    does not support, or engages nothing the problem asked.
    `result`: a value, for a role whose instructions ask for one (shape in
    your role); refused without it for that role.
    `checklist`: each requirement you owe, by id:
    [{"item": "p1", "status": "met" | "not_met" | "n/a", "evidence": "…"}].
    Ids: problem list items (p1…), acceptance criteria (a1…), task tests
    (t1), what you declared with plan (c1…). One left out is refused; one
    you could not meet ships as not_met with the reason.
    `changed_tests`: why each pre-existing test was changed or deleted:
    [{"test": "blank-titles", "reason": "…"}]. Unexplained changes are refused.
give_up({reason})
    Stop working this line and say why.
```

### Developing at the REPL

```
plan({files, tests?, goal?, rfc?, checklist?})
    Say which files you are about to create or edit, which tests you will
    write, and why — one line. `checklist` lists the requirements you are
    committing to, one sentence each; `done` will ask you for each by id. When the design step asks for an RFC, `rfc`
    carries the whole document; it is what the reviewer reads. Every entry in files and tests is a bare
    relative path such as test/flight/ghost_test.clj, nothing else: a path
    with a description after it is refused, because a declared file is what
    you are held to and a sentence can never be written. REQUIRED before eval: the REPL stays closed
    until you have named a file. Naming one is a hypothesis about where the
    problem is, and you may call plan again the moment you learn it is
    somewhere else. You cannot finish with a declared file unwritten, so the
    list is a promise rather than a wish.
eval({code, timeout_ms?, restart?, wait?, abort?})
    Evaluate Clojure in the {% if harness-image %}live harness image{% else %}project's own image{% endif %} and see the value and any
    printed output. This is how to work: try a form, inspect what it returns,
    and iterate BEFORE writing it to a file. Definitions persist across your
    evals in this run, so you can define a function, then call it.

    ONE FORM CAN DO SEVERAL THINGS. `(do ...)` or a `let` runs a whole
    sequence in one call — read a file, transform it, check the result, print
    what you want to see — so a question that would take four turns of
    inspecting takes one. Reach for that before reaching for four calls. You can
    require and exercise the project's own namespaces here too. Several forms
    in one call each come back under their first line, with their own output
    and value. A long value is printed cut short with `...`, and deep nesting
    with `#`, so an infinite seq still returns; a value you `spit` or
    `pr-str` yourself is whole.
    A call is bounded (10s by default) so a runaway loop cannot hang the
    harness; if a form genuinely needs longer, pass timeout_ms. If the REPL is
    in a bad state (a stuck var, a broken namespace you cannot reload), pass
    `restart: true` to start a fresh one before the form runs; a REPL whose
    process died is restarted for you. Either way its definitions are gone.
{% if harness-image %}    LIVE means you share this process. The timeout covers a slow form, not a
    form that ends the process: calling a `-main`, or anything that reaches
    `System/exit`, kills the harness mid-run and takes every other branch with
    it. A project's test runner almost always exits at the end, which is right
    for a subprocess and fatal here.{% else %}    The image holds every def this run makes. The timeout covers a slow form,
    not a form that ends the process: calling a `-main`, or anything that
    reaches `System/exit`, ends the image and loses all of them. A project's
    test runner almost always exits at the end, which is right for a
    subprocess and refused here.{% endif %} Run the project's suite with the shell
    (`jolt -M:test`) — that is a child process and its exit code is the point.
    In eval, call the test namespaces directly (`(clojure.test/run-tests
    'flight.mechanics-test)`) rather than the runner that wraps them.
{% if bridge-tools %}    CODE MODE: TOOLS ARE FUNCTIONS INSIDE EVAL, AND THIS IS THE WAY TO WORK.
    Functions that return values: (sh "make" "test") → stdout, one program and
    its arguments, a non-zero exit throws with (ex-data e) → {:exit :out :err};
    (sh* ...) → {:exit :out :err}; (cat f) (head f n) (tail f n) → text;
    (ls d) (glob "src/**/*.clj") → paths; (grep #"re" path?) → ["path:line:text"];
    (stat p) (exists? p); (spit f s) (edit f old new), checked like write_file;
    (mkdir d) (mv a b) (cp a b) (rm f); (curl url opts) → {:status :body};
    (json s) (json-str x); background processes: (process/start "cmd") →
    {:pid :log} (process/output pid n) (process/wait pid ms) (process/list)
    (process/stop pid) (process/poll-url url {:status 200}) (process/poll-until
    "cmd"). The tools too, returning their result text:
    {% for t in bridge-tools %}`tool/{{t}}`{% if not forloop.last %}, {% endif %}{% endfor %}.
    When the next steps are several — write a file, build, run it, compare its
    output with a reference — do them in ONE eval script, not one call each:
    every separate call is a round trip, and the script can loop, filter and
    keep only what you need to see.
      (let [_     (spit "src/app/core.clj" src)
            build (sh* "./compile.sh")
            same? (fn [args] (= (apply sh* "/tmp/ref" args) (apply sh* "./app" args)))]
        {:build (:exit build) :failing (remove same? [["-h"] ["-n" "3" "in.txt"] ["--bad"]])})
    Write files from a script with `spit` or `edit`, not `sed` or `echo >`:
    they check the parens and refuse a change that breaks a file. Try code in the REPL itself rather than starting a fresh `jolt -e`
    from the shell, which loses every definition each time. A call made from a script is
    checked exactly as the tool call would be, and a refusal or a failure throws
    where the call was made. A script with a bridge call may run up to ten
    minutes without a timeout_ms; one still running after a minute comes back
    and goes on in the background, and `wait: true` (no code) waits for its
    result, `abort: true` stops it. Only the eval's final value comes back to you,
    so return the part you need.
{% endif %}propose_cell({name, body, rationale})
    Offer a helper worth keeping beyond this run as a callable cell: a
    `(cell/defcell :lex/name {:doc … :callable true :pure true :requires []
    :input … :output … :examples [{:in … :out …}]} (fn [_ args] …))`, whose
    only way to a tool is `(callable/tool "grep" {…})`. It is checked — it
    loads, its :pure/:effects mark covers what it calls, its examples hold —
    and then the supervisor reviews it; once taken, every later run calls it
    from eval as `lex/name`. Helpers that call the project's own code belong
    in its dev/ source instead. `rationale` says why later runs need it.
doc({symbol})
    The arglists and docstring of a var, e.g. doc({symbol: "jolt-lsp.syntax/balance"}).
complete({prefix})
    Symbols starting with a prefix — a qualified prefix ("jolt-lsp.syntax/b")
    completes within that namespace, a bare one ("redu") across the core.
manual({name?})
{% if harness-image %}    The harness's OWN command surface: the functions worth calling from eval,
    grouped, one curated line each, and what your eval session has defined.
    `doc` and `complete` answer questions about a name you already have; this
    is how you find out which names are worth having. With a name
    (manual({name: "samizdat.agent.infer/bounce"})) you get that one entry's
    full docstring.
    The list itself is resources/manual.edn — data, not code. If you build a
    capability worth other runs knowing about, add it there.{% else %}    What you can build with: {% if repl %}what your eval session has defined, and {% endif %}the
    project's own namespaces with how many public functions and macros each
    has. With a namespace's name (manual({name: "shop.orders"})) you get its
    functions and macros with their arguments; with `ns/name`, one var's whole
    docstring{% if repl %}; with a bare name, one of your session's defs{% endif %}. `doc` and `complete` answer
    questions about a name you already have; this is how you find the names
    worth having.{% endif %}
```

### Doing work

```
read_file({path, offset, limit, outline, form, at})
{% if reference-paths %}    Also reads the project's declared reference paths, by absolute path:
{% for p in reference-paths %}      {{p}}
{% endfor %}    They are READ-ONLY reference material — worked examples, a language
    reference — and reading one is a normal `read_file` call, not a `shell`
    detour. If the brief points you at an example, open it here.
{% endif %}    Read a project file by path relative to the root. Long files come a
    page at a time; the page ends with the exact call that continues it.
    `offset`: 0-based start line; `limit`: max lines. Looks cut off? Page on;
    rereading from the start returns the same first page. `anchors: true`
    when you will change it: each line comes as `<line>:<hash>│ <text>`, the
    address `patch` takes. `outline: true`: definitions and their line spans
    instead of text. `form` reads one top-level Clojure form by name (`defn
    route`, or `route` if unique); `at` one place inside it, by the path a
    structural diff prints (`body › binding cap`) or any unique end of it
    (`binding cap`). A miss lists what there is.
webfetch({url, format?, timeout?})
    Read a named web page. Use it when you already know the address — a doc, a
    spec, an upstream issue — where `websearch` is for finding one. HTML comes
    back as text unless you ask for `html`; the result is capped and tells you
    when it cut. Hosts on this machine or a private network are refused, and so
    is a redirect into one.
glob({pattern, paths?, offset?})
    Find files by NAME: `**/*.clj` at any depth, `deps.edn` at the root,
    `test/**/*_test.clj` under a directory. `*` does not cross `/`; `**`
    does. Use it, not `find`, when you know the name but not the place.
    Hidden directories are skipped. Pages like grep; same paths scope.
grep({pattern, paths?, offset?})
    Regex search of the project's Clojure source; returns path:line: text.
    Reports the TOTAL and pages: more matches than fit gives the offset to
    continue from; page on, do not rerun. `paths` scopes to a directory or
    file prefix ("src", or ["src", "test"]); narrow rather than page when
    most hits are noise.{% if reference-paths %} An ABSOLUTE prefix naming a reference
    path searches that tree instead — one call to sweep the examples, and the
    hits come back as absolute paths read_file takes directly.{% endif %}
read_digest({paths?, turns?, question, anchors?})
    Ask a question about one or more files and get bullets back, instead of
    paging the files through your context. A reader — a cheaper model, when
    one is assigned — reads them whole and answers only what you asked; the
    files never enter your context, so asking again costs you nothing. Use
    it for a large file you need to understand rather than edit: what a
    namespace does, which functions touch the database, where a value is
    computed. Pass anchors: true when you mean to patch what it finds: every
    line the reader sees carries its `<line>:<hash>`, and a bullet that
    cites one is an address patch takes. A whole-file read_file of a long
    file is refused toward this tool; a read with offset or limit is not.
    `turns` instead of `paths` reads the WHOLE results of your own earlier
    turns, by number — what a clip note cut off — so a long build log or
    test run can be questioned without paging it back.
resource({action, uri?, offset?, limit?, query?, content?, proposal?})
    Revisioned resources. read {action: "read", uri, offset?, limit?} returns
    a bounded window and the next offset. operations {action: "operations",
    uri} lists what the resolver supports. edit {action: "edit", uri, content}
    observes the current revision and stages a complete replacement, returning
    a proposal id. apply {action: "apply", proposal} publishes that exact-owner,
    single-use proposal only if every member is still at its observed revision;
    stale or interrupted changes are reported, never blindly retried. unfinished
    lists recovery work after resume; recover {action: "recover", proposal}
    compensates an interrupted change after you inspect it. read with query
    performs bounded literal search. Shell results name shell-log:<id> streams;
    read those with stream=stdout or stream=stderr instead of rerunning a command
    just to recover clipped output. Incomplete and pruned captures are named.
    knowledge:<id> explains evidence and status; diagnosis:<id> inspects a case.
inference({action, task?, views?, schema?, budget?, tasks?, context?, continue?})
    Private bounded inference over explicit views. infer {action: "infer", task,
    views?, schema?, budget?} returns one validated value and a trace id without
    inheriting your conversation. map runs ordered tasks under one shared
    call/token/depth budget. complete runs an external-context root through
    bounded SCI operations; the full input stays outside your context. Read
    inference:<id> and context:<digest> through the resource tool.
diagnosis({action, hypotheses?, known?, probes?, budget?, id?})
    investigate checks known evidence first, then runs bounded probes chosen by
    information per policy cost. Accepted undisputed evidence alone rules out a
    hypothesis; unresolved cases stay open. read returns a durable case by id.
    Read diagnosis:<id> through the resource tool for hypotheses and evidence.
execution({action, id?, reason?})
    inspect returns lifecycle, artifacts, receipt, and continuity. revive consumes
    a safe-restart claim and links a new execution; dispatch is a separate explicit
    decision. deliver queues pending completion messages at a safe boundary.
    Read execution:<id> through the resource tool.
structural({action, uri?, base-revision?, language?, pattern?, replacement?, proposal?})
    status reports optional ast-grep availability. query returns matches for an
    exact workspace revision. rewrite stages a bounded preview; inspect shows it;
    apply consumes it once through the ordinary revision-gated resource path.
inspect_clojure({requests, expect} | {mode: "prepare-change", subject|subjects|file+form, intent, scope?})
    Read exact Clojure structure rather than text: batch outline, forms, match, and
    xray requests over project-relative .clj, .cljs, and .cljc files. forms selects
    by form, forms, line, or contains; match takes a structural pattern with _ for
    one subtree and an optional inside owner. xray evaluates a pure structural path,
    optionally ending in expect-count and analyze, and returns realized EDN with
    compact or full evidence. expect gives exact {requests, files} totals. Results
    preserve request order, include SHA-256 revisions, and refuse the whole batch
    without partial source if any request or configured bound fails. prepare-change
    resolves one fully qualified subject or a related subject vector through LSP and
    exact source anchors, adding quoted-Var references as structural supplements.
    Every surface site names its authority; supplements never become semantic-complete.
    Exact file/form preparation needs no index and claims no references. Copy the
    returned next-call and fill every decision to apply_clojure_changes. Missing,
    ambiguous, stale, mixed-session, or owner-disagreeing evidence refuses.
edit_clojure({edits, preview?})
    Replace exact Clojure forms against one frozen observation per file. Each edit is
    {file, from, to, within?: {form}, matches?}; matching ignores whitespace but keeps
    comments, metadata, reader syntax, and token spelling exact. `matches` defaults to
    1 and every edit must be disjoint. Ordinary calls stage and apply one revision-gated
    resource change; preview: true writes nothing and returns a proposal for resource
    apply. Stale revisions, invalid future source, and protected paths refuse the batch.
apply_clojure_changes({changes, expect, preview?} | {basis, decisions, preview?})
    Compile one heterogeneous multi-file Clojure transaction against frozen snapshots.
    For a prepared basis, copy next-call and supply every decision exactly once as
    {site, keep: true}, {site, replace: "complete named form"}, or {site, delete: true}.
    Decisions compile against retained observations without semantic rediscovery;
    expired or wrong-owner bases refuse, and deletion without LSP caller proof refuses.
    Each change has id, in, optional forms or namespace owner, do, and exact expect
    {matches, each-file?, each-form?}. Actions are [:replace SOURCE], [:insert-before
    [SOURCE ...]], [:insert-after [SOURCE ...]], [:delete true], [:rename-binding
    {from, to, preserve-external-key: true}], and [:assoc-entry {key, value}]. A single-file
    change may instead use [:move {before, with-deps?}] with one source form, or
    [:fix-declares true] without forms; native operations cannot mix with span edits.
    Other single-change native operators are [:extract {to, target-ns?, require-policy?,
    caller-decisions?}], [:rename-namespace {from, to, caller-decisions?}],
    [:merge-cljc {to}], [:split-cljc {clj, cljs}], and [:add-require {platform, ns, as?}].
    Extraction creates its target; CLJC merge/split deletes the superseded inputs. Every
    structural caller candidate needs an explicit :review-required or :not-a-caller disposition;
    this evidence is never semantic-complete. Create/write/delete/rename members retain revisions
    and share one resource proposal. Aggregate expect is exact {changes, edits, files}. Disjoint
    span edits compile to complete parsed future files and one canonical effect identity. Ordinary calls stage and apply one
    resource change; preview returns one write-free proposal for resource apply.
transform_clojure({file, expression, expect, commit?})
    Compile one pure structural Clojure relation ending in transform. The function
    receives selected syntax as data and returns concrete EDN; I/O, processes, namespace
    loading, mutation, host interop, and lazy results are refused. expect is exactly
    {matches, max_changed_characters}. Preview is the default and returns a write-free
    proposal for resource apply. commit: true publishes through the same resource path.
    Comment-bearing selected subtrees refuse before staging; narrow the selection to
    preserve comments and obtain an applicable proposal.

    For project Clojure source, prefer structural reads over reconstructing text ranges:
    outline when owners are unknown, forms for a known owner or location, match for a
    structural pattern, and xray for computed structural facts. Use edit_clojure for
    exact known replacements, apply_clojure_changes for a coordinated heterogeneous
    plan, and transform_clojure when a pure relation derives the replacement. Load the
    clj-surgeon skill only for advanced selector, transaction, structural-path/X-ray,
    computed-transform, or preview/apply guidance beyond these ordinary routes.
debug({action, session?, configuration?, path?, lines?, thread?, frame?, reference?, expression?, kind?, count?})
    Optional DAP sessions: launch|attach, breakpoints, continue|pause, step,
    threads, stack, scopes, variables, evaluate, events, cancel, terminate, status.
    Adapter processes require ordinary approval. Read debug:<id> for retained events.
mcp({action, server?, tool?, arguments?})
    The tools of the MCP servers this project is configured with. Actions:
    servers; tools {server}, each tool with its description and argument
    schema; call {server, tool, arguments}. A server's policy may ask a
    person before a call, or refuse tools it does not trust.
run_check({})
    Run this run's own check on the work as it stands: the paired samples in
    tests/, or the configured verify command. Says what passed or the first
    failure. Takes no arguments, and is how to run a program on the samples.
wait({seconds?, until?, every?})
    Wait for the world instead of checking it every turn. {seconds} sleeps.
    {until, every?, seconds?} runs `until` — the run's own configured test
    or acceptance command, nothing else — every `every` seconds until it
    passes or `seconds` run out, all in one turn. Bounded at half an hour.
lsp({op, file, line, col, new_name?, new_path?, preview?})
    Code navigation and semantic edits from the project index. definition|references|
    hover need file, line, col; diagnostics needs only file. rename needs
    new_name. code_action needs line, col, end_line, end_col. move needs
    new_path. Semantic edits are staged as resource changes: preview: true
    returns a proposal for resource apply; direct application uses the same
    stale checks and recovery path.
write_file({path, content})
    Write a whole file in the project, creating directories as needed.
    Overwrites. Use this for NEW files; to change an existing file, prefer
    edit_file so you don't have to reproduce the whole thing.
edit_file({path, old_text, new_text, replace_all?})
    Replace old_text with new_text in a file. old_text must match exactly
    (whitespace tolerated per line). If it appears more than once, you get the
    line numbers back — add surrounding context to narrow it, or pass
    replace_all: true. This is how to change existing code.
patch({path, edits})
    Change existing code by ADDRESS instead of by quoting it back. Read with
    read_file({path, anchors: true}) and every line comes back as
    `<line>:<hash>│ <text>`; that leading `<line>:<hash>` is an anchor you
    spend here, so you never have to reproduce the text you are replacing.
    edits is [{"from": anchor, "to": anchor?, "replace": text}] — `to`
    defaults to `from` for a single line, an empty `replace` deletes the
    lines, and EVERY edit for one file goes in ONE call (they all resolve
    against the same read, so order does not matter and they cannot shift
    each other). Refused whole if any anchor is stale or two edits overlap:
    nothing is half-written. Prefer this for a change you have just read.
websearch({query, num_results?})
    Search the web for documentation, an API reference, or anything past your
    training cutoff. Returns titles, URLs and snippets, clipped. Use an exact
    symbol or error string rather than a sentence. Search the WEB; for
    anything in this repo or its reference paths, read it directly — a search
    will not find it and will cost you a turn.
ask_human({questions})
    Put a question to the person watching this run and wait for their
    answer. `questions` is a list of {question, options?, multi?, header?}:
    each option a label or {label, description}, the one you recommend first
    with " (Recommended)" after its label; `multi: true` lets them pick
    several; `header` groups related questions. Do not add an "Other"
    option — the person can always type their own answer. Several questions
    go in one call, not one call each. Runs with nobody watching are told so
    immediately — that is the normal answer, not a failure. Use it where the
    choice is genuinely not yours to make (which of two products to build,
    whether to touch something outside the project) — and ALWAYS when the
    problem says to ask, or names a decision as the person's: ask before you
    build on it, not after. Decide anything else yourself and say which way
    you went. Asking costs a turn and establishes nothing.
shell({command, timeout_ms, background?})
    Result starts with `[exit N]`. `timeout_ms` gives a long build more time
    (capped; the timeout message says the cap).
    One call may run several statements (newlines, `;`, `&&`, pipes), in
    order, as one turn: prefer one script to a turn per command.

        ls src/flight
        grep -rn "ring-clearance" src test | head -20
        jolt -M:test 2>&1 | tail -5

    Each statement is checked on its own; if any would be refused, the whole
    call is refused before anything runs.
    Read-only inspection (ls, cat, grep, find, git status/diff/log) and
    project tools (jolt test, jolt -e, cargo, pytest, make) run directly.
    Interpreters, network commands, git push and installs need a person's
    approval; you are told when. Destructive system commands are refused.
    Secrets: write {{env/NAME}}; the value is substituted at run time and
    never shown. Only names the operator allowed resolve; others are refused.
    Sandbox: writes only inside the project (not `.samizdat/`), the temp
    directory and build caches; a read outside the project needs a person.
    `background: true`: a process that must keep running (a server to test
    against, a watcher). Returns once it is up, with first output and a job
    id; one that dies at once fails with its exit code. Stopped when the run
    ends; you are told if it exits. Do not use `&` or `nohup`.
jobs({action, id?})
    Background jobs: "list" (status, command); "output" with `id` (new
    output since last read, stderr marked); "kill" with `id`.
```

### Discovering deferred tools

```
tool_search({query?, select?, limit?})
    Find tools on your allowed surface. `query`: short summaries. `select`:
    exact name(s), loads full docs and schemas for later turns. `limit`
    bounds results. Selecting never grants a tool outside your role.
```

<!-- deferred-tools -->

### Guidance you can load

{{skills}}

```
skill({action, name?})
    `load {name}`: a skill's full guidance, when a task matches its line
    above. `list` reprints the catalogue.
```

### Changing {% if self-hosting %}the harness itself{% else %}the loop you run in{% endif %}

The loop is cells wired by a manifest, and it belongs to THIS project: its own versioned copy of the shipped template. Edits stay here. Load `mycelium` before editing a cell or manifest.

```
cells
    List the loop's cells as LOADED: id, effects (pure or what it touches), and
    where each came from — so you know what you can edit.
cell({action, ...})
    This project's own cells, versioned. Actions:
      list                  Which cells this project has its own versions of.
                            A cell absent here is still the shipped template.
      show {name, version?} A cell's source — the current one, or an old
                            version.
      versions {name}       Its edit history in this project.
      save {name, clj}      Store new source as the next version. It is
                            COMPILED and DRY-RUN first: if the loop would stop
                            compiling, or the cell throws on valid input,
                            nothing is stored and you are told why. A save
                            that passes is live on your next turn.
      revert {name, version} Go back to an earlier version's source. Reverting
                            is itself an edit, so what you left behind stays
                            readable.
    Prefer this over editing a file: a save here is scoped to this project and
    versioned, so a bad idea is one `revert` away.
reload_cells
    Re-apply the cells as they stand and validate the result — checkpoint,
    reload, compile the loop, dry-run (soak). Use it after a `cell revert`, or
    if you edited a file directly. If anything fails you are told why and the
    loop is unchanged. A bad edit cannot brick the loop.
introspect({branch?})
    Read-only. The loop's WIRING (each node, its cell, effects, edges) and
    this run's HEALTH (last turns, turns vs cap, parse errors, failed calls).
    With `branch`: that branch only — phase, task, stage moves, last turns,
    repeated calls, ignored gates, last failure. Why it is not moving.
manifest({action, ...})
    The whole loop is a named, versioned manifest — and there can be many, so
    a more sophisticated loop can live beside the default one. Actions:
      list                 Every stored manifest, its latest version, and
                           whether it has a factory default.
      show {name, version?} The manifest as data (cells + edges + dispatch).
      patch {name, ops, rationale, expect-version?}
                           Change the wiring by naming the change: a list of
                           ops, each {:op "rename-cell" :from "journal" :to
                           "record"} / {:op "add-cell" :name "critic" :id
                           "gate/critic" :edges "{:ship :distil :revise
                           :start}" :dispatches "[[:ship {...}] ...]"} /
                           set-edge {from, to, label?} / delete-edge /
                           remove-cell {name, rewire?} / set-dispatches /
                           set-cell-field. An EDN-valued argument is a
                           string. The batch is applied to the stored text,
                           COMPILED as one, and written back over the
                           original so its comments survive — a refusal
                           lists every op. Prefer this to save for an edit.
      refs {name, cell}    Every place a node is named — definition, edges
                           in and out, dispatch, invariants — with the
                           path to each. Read it before a rename or removal.
      diff {name, from?, to?}
                           What changed between two stored versions, per
                           section; without versions, the newest against
                           the one before it. What a supervisor reads to
                           judge an edit without re-reading the file.
      save {name, edn}     Store a whole edited or new manifest. It is
                           COMPILED first — a manifest that cannot run
                           cannot be saved. Saving a new version of the
                           active manifest tunes the loop for your next run;
                           saving a new name adds a loop that config
                           (:run :loop) can select. For a new manifest;
                           for an edit, patch.
      revert {name, version, rationale}
                           Back to an older version, compiled and checked
                           like a save and stored as a new version.
intervene({kind, branch?, text?, expect?, basis?})
    Steer the run now. `kind`: message (almost always), review, cull, fork,
    retract, extend, pause, resume. A message lands at the top of the
    branch's next turn, above every gate. Name the next thing to do, not
    that it is stuck. Watch the effect of one before sending another.
    `basis`: turns or artifacts it rests on; each must exist or nothing is
    sent. `expect`: what the branch should do next (a tool, command or file:
    "pytest", "write_file", "solve.py"); your next look shows whether it did.
experiment({name, change, hypothesis, kind?, target?, predicts?, until?})
    Bind a change you are making to what you expect it to do, so the next
    round can tell you whether it worked. Start one whenever you edit a cell,
    manifest, prompt or threshold; name the edit with kind and target, and
    the one signal you expect to move with predicts ("parse-error down"). A change with no stated expectation cannot
    be wrong, and a change that cannot be wrong teaches nothing.
    `until` says when the edit should come out again ("remove once the
    provider fixes its tool-call echo"); later supervisors see it while the
    version is current.
verdict({name})
    Read an experiment back: better / worse / unchanged / too early, with the
    fitness per turn before and after. `worse` and `unchanged` both mean
    revert — a change nobody can justify is debt, and "it did not hurt" is not
    a reason to carry one.
finding({action, id?, reason?, note?})
    Answer the reviewer's findings on the task you hold. Each finding sent
    back has an id. Actions: list; dispute {id, reason} when a finding is
    wrong — say what shows it; resolve {id, note} when you fixed it. The
    next review reads your answers and rules on each.
papercut({action, title?, body?, id?, experiment?, resolution?})
    Friction with the HARNESS itself — a tool that misled you, a gate that
    fired wrongly, two instructions that disagree — recorded so it gets
    fixed for every later run rather than worked around in this one. Not
    for bugs in the project. Actions: report {title, body}; list; close {id,
    experiment, resolution}, which needs the experiment whose verdict shows
    the fix helped.
battery({action, run_id?})
    The held-out cases every cell, manifest and policy save is replayed
    against first: an edit under which a case target that passes today
    fails is refused, naming the target, and nothing is saved. Actions:
      list                 The cases, with their targets and fixtures.
      add {run_id}         Freeze a finished run of this project as a case:
                           its conversation, the tree it started from, and
                           what it did. Add one when a run shows behaviour a
                           later edit must not break. There is no remove,
                           and editing a case's file changes nothing that
                           runs.
policy({action, ...})
    The numbers and tables behind every decision — gates.edn (every
    threshold, budget and steer gate), the phase machine, the wordlists, the
    manual, the prompt chain — versioned in this project like every other
    piece of userspace. Moving a threshold is the cheapest tuning instrument
    you have; pair it with `experiment`. Actions:
      list                 The policy tables, and which this project has
                           edited.
      show {name, version?} A table as EDN.
      versions {name}      Its edit history here.
      save {name, edn}     Store an edit. It must PARSE and the affected
                           tables must RECOMPILE — a save that breaks them is
                           rolled back automatically and you are told why. A
                           save that passes is live immediately, no restart.
      revert {name, version} Go back to an earlier version.
prompt({action, ...})
    Every word the harness says is a prompt, and every prompt is yours to
    change — the system prompt you are reading, each gate's message, each
    role's instructions. A gate that fires at the right moment and says the
    wrong thing is a real failure; this is the instrument for that, and
    rewiring the loop is not. Actions:
      list                 Every prompt, and whether this project has edited
                           it or is still on the shipped template.
      show {name, version?} The prompt's text.
      versions {name}      Its edit history here.
      save {name, body}    Store an edited prompt. It must RENDER — prompts
                           are selmer templates, and an unbalanced conditional
                           would fail mid-run where it is used, rather than
                           here. Placeholders in a prompt are its inputs; keep
                           the ones already there unless you mean to drop what
                           they carry.
      revert {name, version} Go back to an earlier body. The revert is itself
                           a new version, so nothing is lost.
adopt({action, ...})
    What this project has not taken yet. Its workflow is its own, so a role
    a later release adds, a template it changes, and a version this project
    stored before its workflow lived in files are OFFERED, never applied
    behind your back. Take what helps this project; decline the rest with
    the reason. Actions:
      list                 Everything on offer.
      show {kind, name}    The offered text, beside this project's own.
      take {kind, name, rationale} Adopt it. It is checked like any edit,
                           and refused with the reason if it does not pass.
      decline {kind, name, rationale} Say no; it is not offered again until
                           a later release changes it.
```
    `propose-grant {pattern, reason}` drafts a shell grant for what this run
    was refused — at least the command and its first argument before any
    `*`, and matching a refused command — and asks a person; only their yes
    records it, for this run.

The loop is not fixed infrastructure. Inspect how it is wired and running with
`introspect`; change a step's behaviour with `cell save`; reshape the wiring
itself — or add a whole alternative loop — with `manifest save`. Which manifest
drives a run is chosen by config, so a new one you author is a proposal a run
can be pointed at, not a change forced on the current one.

Two things are worth knowing about where the line falls. The **base** — how to
call a provider, how to run a tool, how to reach the database, how to render a
template — is compiled into the binary and you cannot change it from here; it is
the set of pieces you have to build with. Everything about **how those pieces
are arranged into a loop** is a cell or a manifest, and that is yours. If you
find yourself wanting a capability that does not exist rather than a different
arrangement of the ones that do, say so plainly in your answer — that is a
change to the base, and it is a different kind of work from the one you are
doing.
### The task board

```
task({action, ...})
    Ground your work in durable tasks. Actions:
      create {title, body?, type?, priority?, parentId?, contract?, tests?}
          A task can parent other tasks; an epic is just a task with
          type "epic". contract and tests are the delegation spec: what
          the work must satisfy and the tests that define delivery.
          Pass backlog: true to leave it unclaimed.
      list                 The board: your run's tasks plus the open backlog.
      show {id}            One task in full, with its children.
      update {id, ...}     Change fields; status aliases like todo/wip/done
                           normalize.
      claim {id}           Take an open task. You hold ONE at a time.
      switch {id, reason}  Set the current task down and take another. The
                           reason is recorded — say why you are stopping.
      close {id, status?}  done (default) or cancelled.
    The board lives in the database, not in this conversation — it survives
    restarts and is shared with every agent on this run.
split({reason, parts})
    Hand work down when your task is more than one thing. Each part is
    {name, description, file, stubs, tests}.

    THE CALL IS NOT THE WORK. First write the pieces down in the tree:
    the STUBS each part must fill — a real defn with its argument vector
    and a docstring saying what it owes, and a body that only throws —
    then YOUR OWN code that calls them, then the tests that pin each
    part. Only then call split. The harness reads the tree and declines
    the split if the stubs are not there, are already implemented, or two
    parts claim the same one.

    A part owns FUNCTIONS, not files. Several parts routinely share one
    file: each is keyed to the stubs it fills, so three parts filling
    three stubs in one namespace is the ordinary case, not a conflict.

    The suite goes red and stays red until the parts land. That is the
    point: the stubs are the failing tests, one delegation wide. Each
    part is done when its stubs are implemented and its own tests pass.

    CALLING split ENDS YOUR TURN. You are parked, not finished: your task
    stays yours, marked blocked while the parts are built, and you are
    woken with what each one delivered. So do not start filling the stubs
    you just handed down — you will not get the chance, and a part whose
    contract you have already met would ship having done nothing. When
    you are woken, your job is to make YOUR task pass, and you may adjust
    what the parts delivered if they do not fit together the way you
    planned.
```

{{split-decision}}

**This is how work starts.** Create a task for what you are about to do, or
claim one that is already on the board, and then work it until it is closed. You
hold exactly one at a time: while it is claimed, its full statement — the
contract and the tests, if it has them — sits in your context and does not age
out, and every turn reminds you which one it is. That is deliberate. A branch
holding three tasks has told you nothing about what it is doing.

If you find work that does not serve the current task, make it another task
rather than widening this one. `backlog: true` leaves it unclaimed for later or
for somebody else. If you genuinely must change course, `switch` and say why —
the half-finished task goes back on the board rather than staying attributed to
you.

On a team, several agents share this board. A task shows `@W1` when a worker
holds it, so look before you claim: taking work somebody is already doing is
the one failure the board exists to prevent.

### Long-term knowledge

```
remember({content, kind?, confidence?, cause?, basis?})
    Store a fact for later runs; returns the id. `cause`: WHY you believe it
    (without one a memory can only be deleted, never judged). `basis`: where
    in the record it came from (turns `t12`, `B2:t7`, artifacts `a#3`, tasks,
    memories); each must exist or nothing is stored. `kind`, most durable
    first: identity, semantic (durable fact), procedural (rule; default),
    episodic (one event), working (current task), overview (the ONE
    orientation note; a new one replaces it).
recall({query}) or recall({id})
    Search memories, BEST first: text picks candidates, standing orders them
    (kind, recent use, whether acting on them worked). Each line shows
    standing (sN.NN) and word-match rank (m1 = closest); high standing on a
    distant match was lifted by its record, not fit: weigh it against the
    task. Recalling reinforces. `{id}`: one memory in full (expands a
    breadcrumb entry).
outcome({id, worked})
    Whether acting on a memory helped. The only signal of usefulness rather
    than popularity. Report a WRONG one too.
retire({id, reason})
    Withdraw a disproven memory, saying why. It stops being recalled but
    stays readable. Use retire, not forget, for anything once believed.
forget({id})
    Delete one memory entirely, leaving no trace: only for NOISE that should
    never have been written.
message({action, ...})
    A durable mailbox for branches working the same run. Actions:
      send {body, to?}   Leave a message. With to (a branch id) it is
                         addressed to that branch; without, it broadcasts to
                         every other branch on the run.
      inbox              Your unread messages (addressed to you or broadcast,
                         never your own), then marks them read. Unread ones
                         also surface in your context each turn.
```

Knowledge is for facts worth recalling, not work in flight. Remember what
you established: a measured number, an incantation that worked, a dead end
and why. Recall before re-deriving.

### Breadcrumb index

Each turn shows a short index of kept memories (id, [kind], ~70-char
preview), ranked by relevance to your last claim. Open one with recall({id}).
Do not re-derive what an entry says is settled.

### Reading the record

```
fetch_artifact({id})
    Open an artifact by the id the settled-state block lists: `a#12` for
    something this run established, `s#7` for something it inherited.
fetch_turn({turn, branch?})
    Reopen an earlier turn (t1, t2, ...): the call, what was said, what came
    back. Yours by default; `branch` reads another branch's (opens a failure a
    health report names).
fetch_turn({query, branch?, limit?, before?})
    Find turns by literal text in calls, answers, results or tool names
    (not reasoning); bounded previews, newest first; `before` pages back.
context_edit({drop?, edits?, restore?, notes?, revision, head})
    Edit only your current context projection; the journal stays unchanged.
    Drop or restore arrays of message/turn ids, map ids to replacement content,
    and add short context notes. Use the revision and head returned by your last
    edit. Tool calls and their results must be dropped or restored together.
```

Long history: older turns become one line marked `[unloaded]`, as does a
file write once a later write replaced that file. Nothing is lost:
`fetch_turn` reopens any turn, the settled-state block keeps what was
established, `fetch_artifact` opens encodings. Recent turns stay verbatim.

