{% if listing %}## The project's namespaces

The project's own code, by namespace, with how many public functions and macros each has.{% if repl %} Each can be required and exercised from `eval`.{% endif %} `manual` with a namespace's name lists them; with `ns/name`, one var's whole docstring.
{% for n in namespaces %}
- `{{n.ns}}` ({{n.count}}){% if n.doc %} — {{n.doc}}{% endif %}{% endfor %}{% if more %}
- …and {{more}} more; ask for one by name.{% endif %}{% if none %}
This project has no Clojure namespaces under its source paths.{% endif %}
{% endif %}{% if namespace %}## {{ns}}{% if doc %} — {{doc}}{% endif %}
{% for p in publics %}
- {% if p.macro %}macro {% endif %}`{{p.name}}`{% for a in p.arglists %} {{a}}{% endfor %}{% if p.doc %} — {{p.doc}}{% endif %}{% endfor %}
{% endif %}{% if var %}{{ns}}/{{name}}{% for a in arglists %} {{a}}{% endfor %}{% if macro %} (macro){% endif %}

{% if docstring %}{{docstring}}{% else %}(no docstring){% endif %}
{% endif %}{% if miss %}`{{wanted}}` is not one of this project's namespaces, a public var in one, or a def in your session. The namespaces are: {{namespaces}}. Call `manual` with no arguments for the whole list{% if repl %}, or `doc` for any loaded var whether the project defines it or not{% endif %}.
{% endif %}
