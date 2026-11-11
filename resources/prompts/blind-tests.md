## A blind tester's results
A separate branch that never saw the code, the turns or your notes wrote tests from the task statement alone and ran them against the deliverable. {{ran}} ran.{% if failing %} These failed:
{% for f in failing %}- {{f}}
{% endfor %}
Each is a requirement read off the task by someone who does not share the builder's blind spots. Send each real failure to the branch as one `message` through `intervene`, with `expect` set to the test's command so it can rerun it; if one comes from the tester misreading the task, quote the sentence of the task that shows it and leave it out. The branch's done stays held until you send a `review`: send it when these pass on the real deliverable, not before.{% else %} All of them passed.{% endif %}
