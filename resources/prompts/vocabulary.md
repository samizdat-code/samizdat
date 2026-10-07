## The project's own vocabulary

These are the project's public functions and macros, by namespace — the language its code is already written in. Build the change out of them where they fit, and when something is missing, add it in the same style rather than writing generic code beside them. {% if brief %}Only the names fit here; `manual` with a namespace's name gives their arguments and doc lines, and with a var's name its whole docstring.{% else %}Each line is a name and its arguments; `manual` with a namespace's name lists all of it, and with a var's name gives that var's docstring.{% endif %}
{% for n in namespaces %}
### {{n.ns}}{% if n.doc %} — {{n.doc}}{% endif %}
{% if brief %}{% for p in n.publics %}{% if p.macro %}macro {% endif %}`{{p.name}}`{% if not forloop.last %}, {% endif %}{% endfor %}
{% else %}{% for p in n.publics %}- {% if p.macro %}macro {% endif %}`{{p.name}}`{% for a in p.arglists %} {{a}}{% endfor %}{% if p.doc %} — {{p.doc}}{% endif %}
{% endfor %}{% endif %}{% endfor %}{% if omitted %}
…and {{omitted}} more namespace{% if many-omitted %}s{% endif %} not shown here for space; `manual` lists every one.
{% endif %}
