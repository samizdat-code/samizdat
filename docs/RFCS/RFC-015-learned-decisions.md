# RFC-015 — Learned decisions: a model that earns what the LLM decides

**Status:** partially implemented. The mechanism (`samizdat.learned`), the
decision log, and the approval classifier as the first decision point are
built and tested: shadow by default, cascade with audit and CUSUM demotion
once the operator turns it on. Trajectory, critic and triage are not wired
yet, and the learners are the keyed Beta only (karamazov-dcnj).

## Purpose

Several of the harness's decisions are an LLM call whose answer is one of a
few labels: whether an ask may run without a person, which workflow a task
gets, how a branch's trajectory scores. Each call costs time and tokens on
every occurrence. The same decisions recur within a project, and a small
Bayesian model fitted on the harness's own history could make most of them in
microseconds.

The model starts with no evidence, though, and a learned answer that is wrong
and confident is worse than a slow one. So the LLM stays authoritative until
the model has shown, on this project's own decisions, that it gives the same
answer. Then the model takes over the cases it is sure of, the LLM keeps the
rest, and a sample of the model's cases keeps being checked against the LLM so
that drift sends the decision back.

The external source is *JevAgentHarness* (2610.21412), read in full. Its
numbers decided three things here:

- **A threshold is fitted, not chosen.** A fixed 0.5 confidence cutoff lost
  21.5 points against the fitted one, and replacing the LLM outright (no
  cutoff) lost 19. The fit: equal-mass confidence bins of at least 20
  decisions; the threshold is the lowest confidence above which every bin
  agrees well enough.
- **Confident errors dominate failures**, and they are the irreversible ones.
  The thresholds are per label, so a label whose mistakes cost more (allowing
  what should have been asked) is held to a stricter bar than its opposite.
- **Small learners collapse under shift.** The paper's 0.8B head scored 73.2%
  on held-out steps and 17.5% live. A threshold fitted once is not a threshold
  you can keep. Here it is refitted continuously, and the model loses the
  decision when its audited agreement falls.

The savings measured for coding agents were 7–10.5% of calls. Expect gains in
that range, not more. They come mostly from latency and from cases where no
model call is needed at all.

## Scope

**This layer decides** which of two deciders answers a learned decision point
on a given occurrence, the LLM or the learner, and when that assignment
changes.

**It must not decide** what the right answer is. That is still the LLM's call
until the learner has matched it, and the decision point's own rules after
that. It must not learn over a deterministic check (RFC-003's reach check, a
deny rule, a person-only ask, a flow ask). Those run before it and it never
sees what they settle. It must not change what a decision's labels mean.

**It hands** each decision point a decider for this occurrence and, once the
answer is known, a row to the decision log. It hands the supervisor
(RFC-012) a table it can read: every decision point with its mode,
agreement, threshold and evidence.

## Model

### A decision point

A site in the harness where an LLM picks one of a fixed set of labels. Each
one declares:

| | |
|---|---|
| `:id` | `:approval`, `:triage-kind`, `:trajectory`, … |
| `:labels` | the closed set it answers with |
| `:features` | a pure function from the occurrence to a numeric vector or a categorical key. Cheap: no model call, no file read the occurrence did not already make |
| `:key` | how occurrences are grouped for evidence, e.g. `[head path-class reason]` |
| `:outcome` | optionally, where ground truth arrives later: a person's answer, a verification that passed, a run that shipped |
| `:costs` | per label, how bad a wrong answer is. This sets the per-label agreement bar |

The features function and the label set are mechanism, in `src/`. A
decision point's thresholds and costs are numbers in `gates.edn`.

### Three modes, per decision point and project

```
shadow ──graduate──▶ cascade ──demote──▶ shadow
```

- **shadow** (the start, always): the LLM decides. The learner predicts too,
  and its prediction is logged with the LLM's answer. Nothing it says reaches
  the run.
- **cascade**: the learner answers an occurrence only when its calibrated
  confidence in its label is at least that label's fitted threshold τ. Below
  τ, the LLM decides as before. A seeded random fraction ε of the learner's
  answers (the audit rate) also go to the LLM. The learner's answer is still
  the one used, but the LLM's is logged, so agreement keeps being measured on
  the cases the learner now owns. Without the audit, the log after graduation
  would hold only the cases the learner left to the LLM, and agreement could
  not be measured.
- **Demotion** back to shadow happens when the audited agreement drops (a
  CUSUM on disagreement crosses its limit), when the LLM it imitates changes
  (the model family is part of the key), or when the operator says so.

There is no mode where the LLM is gone entirely. The paper's REPLACE arm is
the result that rules it out.

### What "accurate enough" means

Per decision point, label and confidence bin:

- agreement with the LLM is a Beta posterior on the logged pairs
  (`augur.beta`, Jeffreys prior), decayed per run so old evidence fades;
- the pairs a label's learner gave are sorted by confidence and cut into
  equal-mass bins of at least `:bin-n` (20, as in the paper);
- a cut qualifies when the pooled pairs above it clear the label's
  `:agreement` bar on the lower end of their `:level` interval, and every
  bin above it clears the bar on its mean. The lower end, not the mean, so
  that thin evidence never graduates. Pooled, because no single bin of 20
  can show 98% on its lower end (that takes about 180 agreeing pairs), and
  per bin on the mean, so a pocket of disagreement is not hidden by pooling;
- τ for a label is the confidence at the lowest qualifying cut. When no
  cut qualifies, that label does not graduate;
- a decision point graduates when some label has a τ and the point has at
  least `:min-n` logged pairs in total.

Where an `:outcome` exists, the bar is also outcome-level: on the occurrences
where both answered, the learner may not be worse than the LLM on the outcome
(`score/paired-loss-2way`; Romano–Wolf across decision points when several
graduate at once, so one of many does not pass by luck). Agreement with the
LLM tops out at the LLM's own quality. Outcomes are the only way to see past
that, and the only way to catch two deciders that agree and are both wrong.

### The learners

All are augur and all are cheap, so a decision point can run two and keep the
one that scores better on held-out rows (`score/pick-model`). Only the keyed
Beta is built; the others are for the points that come after approval.

- **Keyed Beta** (built) (`augur.beta` + `augur.layer`): p(label | key), layered
  project → machine → shipped prior. This is the right model when the key is
  categorical and keys repeat, as in approval.
- **Shrunk logistic** (`augur.logit/fit-shrunk`, updated online with
  `evidence-step`): for a numeric feature vector, refitted at run end.
  One-vs-rest for more than two labels.
- **Hierarchical intercepts** (`augur.hier`): pools across projects, so a new
  project starts from what the machine has seen. It still has to graduate on
  its own evidence. Pooling sets the prior; it never grants authority.
- **Calibration** is not needed for τ: τ is fitted on the learner's own
  confidence, and the bins are measured against the LLM, so a miscalibrated
  but monotone confidence still finds its cut. It matters only when two
  learners' confidences are compared.
- **The log** stands in for `augur.ledger` here: the table already holds
  every prediction beside the answer that resolves it, and an occurrence
  the LLM never answered is simply not a pair.

## The decision points, in order

1. **Approval (`:approval`)**: allow or ask, after the reach check
   (RFC-003). Frequent, binary, and with a categorical key, so the keyed Beta
   fits it. It is also the only point with a second label source: under
   `:attended` and `:block` a person's answer is ground truth. Costs are
   asymmetric: the learner's `allow` must clear a far higher bar than its
   `ask`, since a wrong `ask` costs a person a keystroke. It never learns to
   allow a write outside the project, which is the classifier's own rule 3,
   enforced before the learner (PLAN R5).
2. **Trajectory scoring (`:trajectory`)**: K LLM calls per score, so this is
   the most expensive per occurrence. Features come from the counters the
   stall gates already read (turns since the last write, the failing-test
   trend, repeated reads). Agreement is the predicted bucket being within one
   of the LLM's. The outcome is whether the branch shipped (PLAN R3).
3. **Critic objectives (`:critic`)**: the same shape as trajectory, per
   objective.
4. **Triage (`:triage-kind`, `:triage-size`, `:triage-workflow`)**: once a
   run, so it is slow to gather evidence. Features are the request's text, as
   hashed tokens, or lev embeddings when lev is configured. It leans most on
   machine-level pooling and comes last.

The generative calls (compaction, decompose, reflect, revise, the supervisor's
passes, the acceptance judge's criteria) are not decision points. Their
answer is not a label.

## API

### `samizdat.learned`: mechanism

```clojure
(learned/register! :approval {:labels [:allow :ask]})   ; idempotent
(learned/decide ctx :approval {:id :key :head :features :family}
                {:llm thunk :label-of :verdict})
    ; => {:label :by :llm|:learner :p :audited? :llm raw}
    ; Predicts, consults the mode and τ, calls the thunk when the LLM
    ; decides or audits, logs the occurrence. A learner that fails is the
    ; LLM deciding; the LLM's own errors propagate to the caller.
(learned/observe! conn occurrence-id {:outcome label})  ; a person's answer
(learned/run-ended! conn run-id)    ; each point to the mode its report earns
(learned/report conn :approval family)
    ; {:n :tau {label c} :bins {label [{:lo :n :agree}]}
    ;  :outcome-blocked? :graduated?}
(learned/standing conn)             ; every point and family: mode, n, τ
(learned/configure! {:approval :cascade})   ; the operator's config.edn :learned
```

State is the project's sqlite: `learned_decisions` (the log) and
`learned_points` (mode, τ and CUSUM per point and model family, as EDN that
round-trips through `pr-str`). A machine layer under `~/.config/samizdat`
is not built: authority is per project anyway, and pooling across projects
only moves the prior.

### Where the decision lives

In `src/`, reading every number from `gates.edn`, as `samizdat.approval`
does — not in a cell. Cells are steps a manifest wires; the approval path
runs below every workflow, and the mode has to hold whichever manifest the
run chose. What is policy is the bars, and those are data. The mode change
is journalled (`:learned-mode` events), and `introspect` and the manual
show the standing.

### `gates.edn :learned-decisions`

```clojure
{:approval {:min-n 200 :bin-n 20 :level 0.95
            :agreement {:allow 0.98 :ask 0.9}
            :audit 0.1 :cusum {:k 0.05 :h 3.0}
            :forget 0.97 :strength 4.0
            :outcome-level 0.8 :max-rows 5000}}
```

The project's values are held at least as strict as the shipped file's,
field by field (`learned/stricter`): the agent may raise the bar its own
learner has to clear and may not lower it. Whether a graduated point
answers at all is the operator's (`config.edn :learned`), as `:auto` is.

### The decision log

One table, `learned_decisions`: run, branch, decision point, key, features,
the learner's label and p, the LLM's label (when asked), the outcome (when
known), which decider was used, whether it was an audit. The log is the
evidence for every number above and is what `introspect` and the TUI read.

## Invariants

| Invariant | Enforced by |
|---|---|
| A fresh project, model family, or decision point starts in shadow | `learned/state`; learned_test |
| The learner never sees what a deterministic check settled | `classify/classify` order; classify_test |
| A learner error or missing state means the LLM decides | `decide`'s catch; learned_test |
| Graduation uses the interval's lower end, never the mean | `learned/report`; learned_test over a thin bin and a pocket |
| Audit sampling is seeded by run and occurrence | `learned/audit?`; learned_test |
| Every answer records who gave it | the log; journal event `:learned-decision` |
| An `:approval` allow by the learner never covers a write outside the project | reach check first; classify_test |
| The approval bar is not the agent's to lower | `learned/stricter` against the shipped file; learned_test |
| Cascade answers only where the operator turned it on | `learned/configure!`; learned_test |

## Order of work

1. **The decision log, and shadow for approval.** Log the classifier's
   verdict (including the `ask` verdicts dropped today) and a person's answer.
   No behaviour change.
2. **The approval learner.** Keyed Beta plus logistic on `[head path-class
   reason outside-count read-only?]`, refitted at run end, with the report in
   `introspect` and the TUI. Still shadow.
3. **Cascade, audit and demotion for approval**, behind `config.edn
   :learned {:approval :cascade}` (operator-only, as `:auto` is). Measure it
   on a campaign: calls saved, agreement on the audited cases, person
   overrides.
4. **Trajectory**, shadow → cascade with the same machinery.
5. **Critic**, then **triage**.

Each step after the first changes no decision until its own shadow numbers
say it may.

## What this does not do

- It does not train or fine-tune a neural model. That is karamazov-pcft,
  deferred, and would enter here as one more learner.
- It does not make the learner better than the LLM where there is no
  outcome. It makes the same answer cheaper.
- It does not transfer authority between projects. Pooled evidence moves the
  prior, and each project graduates on its own record.
