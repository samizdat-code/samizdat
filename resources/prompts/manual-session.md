## Your session

What this run has defined in its eval session — callable from `eval` by these bare names. It lasts until the run ends; what should outlive it belongs in a file.
{% if defs %}{% for d in defs %}
- {% if d.macro %}macro {% endif %}`{{d.name}}`{% for a in d.arglists %} {{a}}{% endfor %}{% if d.type %} ({{d.type}}){% endif %}{% if d.doc %} — {{d.doc}}{% endif %}{% endfor %}{% if more %}
- …and {{more}} more.{% endif %}{% else %}
Nothing yet.{% endif %}
