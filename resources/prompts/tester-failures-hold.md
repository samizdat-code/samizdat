The blind tester's checks were rerun just now and these still fail:
{% for f in failing %}
- `{{f.command}}`{% if f.expected %} — {{f.expected}}{% endif %}
```
{{f.output}}
```
{% endfor %}
These are the task's requirements as a reader of the task statement tests them, and a review that approved past them does not make them pass. Run each command yourself, find why it fails on the real deliverable, and fix that. If one is measuring something the task does not ask for, show that from the task statement in your answer. Call `done` again once they pass.
