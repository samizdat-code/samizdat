## A branch wants to ship — review it now
Its `done` is held until this pass ends, and it has time left to act on what you find.

The task:
{{problem}}

Its answer:
{{answer}}

What the run executed, newest last (exit code, or refused):
{% for e in evidence %}- `{{e.command}}`{% if e.refused %} refused{% else %} → {{e.exit}}{% endif %}
{% endfor %}
A hidden grader will check the task's literal requirements. Go through them one at a time: the deliverable's exact path and format, every behaviour the task names, every constraint ("no comments", "single query", "do not modify"). For each, is there a command above whose result shows it holds on the real deliverable? Read the files yourself if you need to.

- If a requirement is unverified, or the evidence contradicts it: `intervene({"kind": "message", "branch": "<id>", "text": "...", "expect": "..."})` naming that requirement and the check that would show it, with `expect` set to that check. One message, the most important gap first.
- If every requirement is shown: say so in a line and end the pass. Ending it releases the done.

Do not do the work yourself, and do not ask for more polish than the task asks for.
