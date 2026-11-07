Read-only calls you sent earlier in this reply ran first:
{% for c in calls %}
[{{c.name}}]
{{c.result|safe}}
{% endfor %}
The call this reply is answered with:

