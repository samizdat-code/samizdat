## From the run's journal
{% if files %}
Files written, with the latest turn: {% for f in files %}`{{f.path}}` (t{{f.turn}}){% if not forloop.last %}, {% endif %}{% endfor %}
{% endif %}{% if commands %}
Latest commands:
{% for c in commands %}- t{{c.turn}} {{c.category}}: `{{c.command|safe}}` → {{c.last|safe}}
{% endfor %}{% endif %}{% if errors %}
Still failing at their latest run:
{% for e in errors %}- t{{e.turn}}: `{{e.command|safe}}` → {{e.last|safe}}
{% endfor %}{% endif %}
