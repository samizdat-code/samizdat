[harness] {% for w in waits %}Your watch {{w.wait}} on {{w.job}} {% if w.matched %}fired: it printed a line matching /{{w.pattern|safe}}/ after {{w.seconds}}s:
{{w.matches|safe}}
{{w.job}} is {{w.status}}.{% endif %}{% if w.exited %}fired: {{w.job}} ended after {{w.seconds}}s, {{w.status}}{% if w.exit %}, exit code {{w.exit}}{% endif %}{% if w.pattern %}, without printing a line matching /{{w.pattern|safe}}/{% endif %}.{% endif %}{% if w.deadline %}ran out after {{w.seconds}}s{% if w.pattern %} with no line matching /{{w.pattern|safe}}/{% endif %}; {{w.job}} is still {{w.status}}.{% endif %}{% if w.quiet %}stopped: {{w.job}} wrote nothing for {{w.quiet-seconds}}s; it is still {{w.status}}.{% endif %}{% if w.gone %}ended: {{w.job}} did not survive the run's restart, so it is not running any more.{% endif %}{% if w.tail %} Its last output:
{{w.tail|safe}}{% endif %}
{% endfor %}
