## Edits waiting on a removal condition
Each of these is the version in force, and when it went in it said when it should come out. If that has happened — the provider was fixed, the shipped template caught up, the task it was for is done — revert it under an `experiment`, so the removal is measured like the edit was.
{% for e in edits %}- {{e.kind}} `{{e.name}}` v{{e.version}}: remove when {{e.until}}{% if e.rationale %} (went in because: {{e.rationale}}){% endif %}
{% endfor %}
