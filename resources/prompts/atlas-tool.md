{% if usage %}atlas ops: search {q}; callers|callees|card|impact|flows {id}; flow {entry}; changes. An id is a definition's `ns/name` — search finds it by name.{% endif %}{% if missing %}No definition `{{id}}` in the atlas. Search for it by name first: {"op": "search", "q": "..."}.{% endif %}{% if no-root %}No project is bound, so there is no atlas to read.{% endif %}{% if no-history %}No snapshot of this project's graph has been stored yet, so there is nothing to compare with.{% endif %}{% if listing %}{% if callers %}Called by{% endif %}{% if callees %}Calls{% endif %}{% if search %}Definitions matching "{{q}}"{% endif %}{% if impact %}Everything that reaches {{id}} ({{total}}){% endif %}{% if flows %}Flows (entry points) that reach {{id}}{% endif %}:{% for x in items %}
- {{x}}{% endfor %}{% if more %}
- … and {{more}} more{% endif %}{% if not items %} none{% endif %}{% if impact %}
Flows through it: {{flow-count}}{% endif %}{% endif %}{% if flow %}Flow from {{entry}}: {{count}} path(s) to an end{% if truncated %}, the first {{shown}} shown{% endif %}.{% for p in paths %}
- {{p}}{% endfor %}{% if loops %}
Cycles folded into one step: {{loops}}{% endif %}{% endif %}{% if card %}{{id}} ({{kind}}) at {{file}}:{{line}}{% if doc %}
{{doc}}{% endif %}
Serves {{flow-count}} flow(s). Calls {{callee-count}}, called by {{caller-count}}.

{{source}}{% endif %}{% if changes %}Since the last stored snapshot: {{added}} added, {{removed}} removed, {{changed}} changed.{% for x in items %}
- {{x}}{% endfor %}{% endif %}
