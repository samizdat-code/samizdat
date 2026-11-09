## Commands that keep failing
Run again and again in this run, never successfully:
{% for c in commands %}- `{{c.key}}` failed {{c.failed}} time(s){% if c.exit %}, last exit {{c.exit}}{% endif %}{% if c.instead %}; what worked here instead: {% for w in c.instead %}`{{w}}`{% if not forloop.last %}, {% endif %}{% endfor %}{% endif %}
{% endfor %}
The branch has already been told about these once. If it is still retrying, steer it with the specific alternative.
