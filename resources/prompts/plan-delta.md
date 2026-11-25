Plan changed since your last one:{% if added-files %}
+ now owed: {{added-files|safe}}{% endif %}{% if removed-files %}
- no longer owed: {{removed-files|safe}}{% endif %}{% if added-checklist %}
+ checklist: {{added-checklist|safe}}{% endif %}{% if removed-checklist %}
- checklist: {{removed-checklist|safe}}{% endif %}{% if goal %}
goal: {{goal.to|default:"(none)"|safe}} (was: {{goal.from|default:"(none)"|safe}}){% endif %}{% if rfc %}
the RFC was rewritten{% endif %}