## Assumptions made without asking
{% for e in entries %}- {{e.question}}{% if e.chosen %} → {{e.chosen}}{% endif %}{% if e.alternatives %} (not: {{e.alternatives}}){% endif %}{% if e.why %} — {{e.why}}{% endif %}
{% endfor %}
