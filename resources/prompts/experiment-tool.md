{% if usage %}`experiment` binds a change you are making to what you expect it to do, so the next round can tell you whether it worked: {name, change, hypothesis, problem?, kind?, target?, predicts?, fixes?, until?}. `problem` is the root cause the change is meant to fix: cite one from the improvement record by id ("P3") when it explains the failure, or name a new one. Every attempt on a problem is kept with its outcome and the model it ran under, for every later supervisor and the next project. `fixes` lists the battery cases you expect this edit to fix, before anything is measured; the record settles each as fixed, missed or still unresolved once the battery replays it. Start one whenever you edit a cell, manifest, prompt or threshold. `kind` and `target` name what you edited (cell / manifest / prompt / policy and its name), and the ledger records the version. `predicts` is the one signal you expect to move, which way, and by about how much, e.g. "parse-error down 30%" or "gate-unmet down"; the verdict says whether it moved that way and, when you gave a size, how close it came. Give the size: a prediction that only says which way is met by any wobble and teaches nothing when it is. `until` is when the edit should come out — "remove once the provider stops echoing the fence" — kept on the version and shown to every later supervisor while that version is current.
`verdict {name}` reads it back: better / worse / unchanged / costly / cheaper / below best / too early, with the fitness and the tokens per turn before and after.
`verdict {name, action, why, learned?}` settles it — action is `reverted` or `kept`. `learned` is what the result says about the loop and what that suggests trying next; it goes into the memory beside the numbers, which can say that a lever failed and never why. `reverted` also puts back the version before the edit when the experiment named its kind and target and nothing has edited it since. A losing change you have not settled keeps being raised, because a modification the evidence says is not helping, left in place because nobody got back to it, is the thing this measurement exists to prevent.{% endif %}{% if started %}Experiment `{{name}}` started. Measuring from here.

  changed:  {{change}}
  expected: {{hypothesis}}{% if problem %}
  problem:  {{problem}}{% endif %}{% if target %}
  edit:     {{target}}{% endif %}{% if predicts %}
  predicts: {{predicts}}{% endif %}{% if fixes %}
  fixes:    {{fixes}}{% endif %}{% if until %}
  remove when: {{until}}{% endif %}

You will see the verdict on your next turn. Change nothing else until then — a second change measured alongside this one tells you nothing about either.{% if unfiled %}

No `problem` was named, so this attempt is on the record under no root cause. Name one next time: an attempt filed under its problem is what the next supervisor, model or project can reuse.{% endif %}{% endif %}{% if settled %}`{{name}}` settled as {{action}}.{% if why %} Reason: {{why}}{% endif %}{% if learned %}
Learned: {{learned}}{% endif %}

It will stop being raised. Its verdict and this decision stay on the improvement record with the problem it was filed under. `remember` anything it taught you that the record does not say.{% endif %}{% if too-many %}Refused: `{{open}}` change is already in flight and unsettled, and the cap is `{{cap}}`.

Two changes measured over the same interval tell you nothing about either, so this is enforced rather than trusted to discipline. Settle what is open first — `verdict {name}` to read it, then `verdict {name, action: reverted|kept, why}` — and the slot frees.{% if unsettled %}

Waiting on: {{unsettled}}{% endif %}{% endif %}{% if bad-prediction %}`predicts` is one signal, a direction and optionally a size as a percentage, e.g. "parse-error down" or "parse-error down 30%". The signals: {{signals}}.{% endif %}{% if undo-moved %}{{target}} was not reverted: it is at v{{now}} now, not the version this experiment measured, so it has already been reverted or edited again. Check it is where you want it.{% endif %}{% if undo-first %}{{target}} was not reverted: the edit measured was the project's first version of it, and the version before that is the shipped template — `adopt` takes the template back if you want it.{% endif %}{% if no-experiment %}No experiment named `{{name}}`. `experiment` starts one.{% endif %}{% if reported %}`{{name}}` — {{verdict}}{% if numbers %} ({{numbers}}){% endif %}

  changed:  {{change}}
  expected: {{hypothesis}}{% if prediction %}
  predicted {{prediction.said}}: {{prediction.moved}} — {% if prediction.hit %}it went that way{% else %}it did not{% endif %}{% if prediction.calibration %} ({{prediction.calibration}}){% endif %}{% endif %}
{% if better %}
It earned its place. Leave it, and `remember` what it fixed so the next run starts from it.{% endif %}{% if worse %}
Revert it. That is the experiment working, not a mistake — and `remember` that this lever made things worse, so nobody spends a round trying it again.{% endif %}{% if costly %}
It gained, but it added more tokens per turn than that gain pays for. Revert it, or say why the gain is worth the price — a harness that grows more expensive for small wins ends up slow for nothing.{% endif %}{% if below-best %}
It moved up from the stretch just before it, but it is still below the {{below-best}} a change you kept already reached. Beating the previous step while sitting under the best one is how a run walks downhill a small step at a time; revert it.{% endif %}{% if cheaper %}
Fitness did not move measurably, and the turns got clearly cheaper. That is worth keeping; say so and leave it.{% endif %}{% if unchanged %}
The change was not the fix. Revert it rather than leaving a change nobody can justify, and look somewhere else.{% endif %}{% if stale %}
Not a measurement of this change any more: {{stale.target}} was v{{stale.started}} when the experiment opened and is v{{stale.now}} now, so the stretch measured both edits and credits them to the first. Settle it — `verdict {name, action: reverted|kept, why}` — and start a fresh experiment on the version in place if you still want a reading. It holds the slot until you do.{% endif %}{% if too-early %}
Not enough has happened to tell yet. Wait — do not stack another change on top of an unmeasured one.{% endif %}{% if confounded %}
Not attributable. Discounting the provider's own failures, the same two stretches read {{confounded.verdict}} ({{confounded.numbers}}) — so the direction above belongs to the endpoint as much as to your change. From inside one run, a change that stopped empty replies and an endpoint that simply came back look identical, and no number here separates them. Decide on the reasoning, say which you believe, and settle it — `verdict {name, action: reverted|kept, why}`. It holds the slot until you do.{% endif %}{% if regraded %}
Scored under weights this run changed: the before side was stamped at {{regraded}} and both numbers above are on the scale in force now. They subtract honestly, but it is a scale you chose while being measured by it. Say what was wrong with the old one.{% endif %}{% if branches %}
{{branches.regressed}} of {{branches.measured}} measured branches went backwards under this change. The number above is the whole run and cannot say that either way — a change that lifted the run while breaking a branch is a different decision from one that lifted both.{% endif %}{% endif %}
