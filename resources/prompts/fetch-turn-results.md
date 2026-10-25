Turns matching {{query}} in {{branch}}:
{% if rows|not-empty %}{% for row in rows %}
t{{row.turn}} {{row.tool_name}} — {{row.preview}}{% endfor %}{% if more %}
More matches may exist; narrow the query or pass before to page older turns.{% endif %}{% else %}
No matching turns.{% endif %}
Open a whole record with fetch_turn({turn: N, branch: "{{branch}}"}).
