The blind tester's checks were rerun just now and these still fail.
{% for f in failing %}
`{{f.command}}` prints:
```
{{f.output}}
```
{% if f.expected %}When the tester first ran it, it reported:
{% for e in f.expected %}- {{e}}
{% endfor %}{% endif %}{% endfor %}
What the command prints NOW is the failure to fix: it can differ from what the tester first saw once earlier failures are fixed, and the script exits non-zero while any check in it fails. These checks are the task's requirements as a reader of the task statement tests them. Run each command yourself, find why it fails on the real deliverable, and fix that. The done stays held while any of them fails, until the run winds down, so arguing that a check is out of scope does not release it: if you are sure one is wrong, say why in your answer, quoting the task statement, and keep improving everything else while the clock runs.
