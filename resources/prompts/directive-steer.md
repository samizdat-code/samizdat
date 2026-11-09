**{% ifequal issuer "supervisor" %}The supervisor{% else %}{% ifequal issuer "watch" %}The harness's reflex{% else %}A human{% endifequal %}{% endifequal %} has intervened in this run.**

{{text}}

This takes precedence over anything the harness has told you. Act on it on this turn.{% if expect %} What it expects you to do next: `{{expect}}`.{% endif %}
