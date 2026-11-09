## {% if branch %}`{{branch}}`{% else %}A branch{% endif %} wants to ship — review it now
Its `done` is held until this pass ends, and it has time left to act on what you find.

The task:
{{problem}}

Its answer:
{{answer}}

What the run executed, newest last (exit code, or refused):
{% for e in evidence %}- `{{e.command}}`{% if e.refused %} refused{% else %} → {{e.exit}}{% endif %}
{% endfor %}
A hidden grader will check the task's literal requirements. Go through them one at a time: the deliverable's exact path and format, every behaviour the task names, every constraint ("no comments", "single query", "do not modify", "do not alter the formatting"). For each, is there a command above whose result shows it holds on the real deliverable? Read the files and run checks yourself if you need to.

The branch's own checks are not the grader. A check of its own that is still failing is a finding, not polish: when the task's text says anything about what that check measures, it is a requirement unmet, whatever the branch calls it. Where the task names output the program must print, a value it must reach or a tolerance, find it in a result on the real deliverable before you count it as shown — "it runs end to end" is not the same claim.

- If a requirement is unverified, or the evidence contradicts it: `intervene({"kind": "message", "branch": "{% if branch %}{{branch}}{% else %}<id>{% endif %}", "text": "...", "expect": "..."})` quoting the task's line, naming the check that would show it, with `expect` set to that check. One message, the most important gap first. Never tell the branch to stop working on something the task's text asks for.
- If every requirement is shown: say so in a line and end the pass. Ending it releases the done.

Do not do the branch's work for it. Polish the task never asks for is no reason to hold it; a constraint the task states is never polish.
