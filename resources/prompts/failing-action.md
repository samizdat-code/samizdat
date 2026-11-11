These commands keep failing in this project and have not worked once:
{% for a in actions %}
- `{{a.key}}` failed {{a.failed}} times{% if a.exit %} (last exit {{a.exit}}){% endif %}{% if a.instead|not-empty %}; what worked here instead: {% for w in a.instead %}`{{w}}`{% if not forloop.last %}, {% endif %}{% endfor %}{% endif %}{% endfor %}

Running one again as it is will fail the same way. Read its last error, then change what you run: use what worked here, fix the cause (a missing program, package, path or argument), or take another route to the same result.
