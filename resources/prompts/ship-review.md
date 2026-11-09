## {% if branch %}`{{branch}}`{% else %}A branch{% endif %} wants to ship — review it now
Its `done` is held until this pass ends, and it has time left to act on what you find.{% if wait %} The hold lapses {{wait}} seconds after it was asked for and the done ships then, whatever you have found: send each gap as soon as you can name it. One message now beats a complete case after the answer has shipped, and you can send another after it.{% endif %}

The task:
{{problem}}

Its answer:
{{answer}}

What the run executed, newest last (exit code, or refused):
{% for e in evidence %}- `{{e.command}}`{% if e.refused %} refused{% else %} → {{e.exit}}{% endif %}
{% endfor %}
A hidden grader will check the task's literal requirements. Go through them one at a time: the deliverable's exact path and format, every behaviour the task names, every constraint ("no comments", "single query", "do not modify", "do not alter the formatting"). For each, is there a command above whose result shows it holds on the real deliverable? Read the files and run checks yourself if you need to.

The branch's own checks are not the grader. A check of its own that is still failing is a finding, not polish: when the task's text says anything about what that check measures, it is a requirement unmet, whatever the branch calls it. Where the task names output the program must print, a value it must reach or a tolerance, find it in a result on the real deliverable before you count it as shown — "it runs end to end" is not the same claim. The grader runs inputs the branch was never given: one sample answered right is one case, so where the task is a program over inputs, ask for it to be run on more of the same kind (other files in the data, generated cases) before you count the behaviour as shown — tb21-unread pytorch-model-cli was approved on its one example image and missed the grader's fifty.

- If a requirement is unverified, or the evidence contradicts it: `intervene({"kind": "message", "branch": "{% if branch %}{{branch}}{% else %}<id>{% endif %}", "text": "...", "expect": "..."})` quoting the task's line, naming the check that would show it, with `expect` set to that check. One message, the most important gap first. Never tell the branch to stop working on something the task's text asks for, or to stop checking.
- If every requirement is shown: say so in a line and end the pass. Ending it releases the done.

When the branch weighed more than one answer, approve its choice only on evidence that the others are wrong. An error from one way of doing it is a fault in that attempt (a wrong argument, a missing import), not proof the way is closed: name the fault and let the branch try it properly, as in tb21-unread mteb-retrieve, where `prompt_type='query'` failed because the library wanted its enum and the approved answer was the wrong one.

Do not do the branch's work for it. Polish the task never asks for is no reason to hold it; a constraint the task states is never polish.
