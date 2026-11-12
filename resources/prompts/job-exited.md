[harness] {% for j in jobs %}Background job {{j.id}} (`{{j.command|safe}}`) has exited on its own: {{j.status}}, exit code {{j.exit}}.{% if j.tail %} Its last output:
{{j.tail|safe}}{% endif %}
{% endfor %}
