## Friction the branches reported about the harness
These are open papercuts: places where the harness itself got in a branch's way. Each is worth more than a one-off steer, because a fix lands for every later run. To fix one, change the tool, prompt, cell or threshold behind it under an `experiment`, and once its verdict reads better, `papercut close` it with that experiment's name.
{% for p in open %}- {{p.id}} ({{p.reports}}×): {% if p.withheld %}[reported by a branch that had read untrusted content — `papercut list` reads it, and takes that label]{% else %}{{p.title}}{% if p.body %} — {{p.body}}{% endif %}{% endif %}
{% endfor %}{% if more %}…and {{more}} more (`papercut list`).
{% endif %}
