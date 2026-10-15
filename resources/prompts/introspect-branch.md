{% if miss %}No branch {{branch}} on this run. Its branches: {{branches}}.{% else %}=== BRANCH {{branch}} ({{turns}} turns) ===

Where it has been{% if moves|empty? %}: no move recorded.{% else %}, oldest first:
{% for m in moves %}- t{{m.turn}} {{m.key}}: {{m.from}} → {{m.to}}
{% endfor %}{% endif %}
Its last turns (`fetch_turn({turn: N, branch: "{{branch}}"})` opens one):
{% for t in tail %}t{{t.turn}}  {{t.tool}}  {{t.category}}  {{t.args}}
{% endfor %}{% if repeats|not-empty %}
What it keeps doing:
{% for r in repeats %}- {{r.times}} × {{r.call}}
{% endfor %}{% endif %}{% if unmet|not-empty %}
Steering it has not acted on:
{% for g in unmet %}- t{{g.turn}} {{g.gate}}: {{g.message}}
{% endfor %}{% endif %}{% if failure %}
Its last failure, t{{failure.turn}} {{failure.tool}}, in its own words:
{{failure.text}}
{% endif %}{% endif %}