## The harness has been failing since its last good set of edits
The last {{errors}} runs ended in an error — the harness could not finish them, which is evidence about the harness rather than about the tasks. The last run that SHIPPED was {{run}} ({{at}}), and these project versions have moved since:
{% for m in moved %}- {{m.kind}} `{{m.name}}`: now v{{m.from}}, {% if m.to %}v{{m.to}} then{% else %}the shipped template then{% endif %}
{% endfor %}
If one of these broke the loop, revert it to the version it had then (`{kind} revert {name, version, rationale}`; for a name that was the template then, `adopt` takes the template back) — under an `experiment`, one at a time, starting with the one you trust least. If none of them is the cause, say so in your answer, so the next pass does not chase it.
