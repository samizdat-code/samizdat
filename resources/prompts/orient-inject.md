## Named in the task, found in the tree

The task names these, and this is where they are. A snippet is where to look, not what is there now — read the file before you change it.
{% for d in definitions %}
### `{{d.name}}` — {{d.path}}:{{d.line}}

```
{{d.snippet}}
```
{% endfor %}
{% if history %}
### Recent reverts and hotfixes on these files

A person undid or rushed something here lately. Read the commit before you change the same code: `git show <sha>`.
{% for h in history %}- `{{h.sha}}` {{h.date}} {{h.subject}} — {% for p in h.paths %}{{p}}{% if not forloop.last %}, {% endif %}{% endfor %}
{% endfor %}{% endif %}
