## Current plan
Your latest `plan` declaration, read from the journal rather than from the summary above. It is what `done` holds you to; call `plan` again to change it.{% if goal %}
Goal: {{goal|safe}}{% endif %}
Files: {% for f in files %}`{{f|safe}}`{% if not forloop.last %}, {% endif %}{% endfor %}{% if checklist %}
Checklist:{% for c in checklist %}
- c{{forloop.counter}}: {{c|safe}}{% endfor %}{% endif %}