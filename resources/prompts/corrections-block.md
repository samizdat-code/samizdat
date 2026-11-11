{% if person %}## Asked for by the person you work for

{% for r in person %}- {{r.instruction}}
{% endfor %}{% endif %}{% if project %}{% if person %}
{% endif %}## Rules this project recorded in earlier runs

The harness or a supervisor wrote these, not the person you work for. Each was right for the situation it came from; follow one when this task is that situation, and when it is not, say so rather than stretching it to fit.

{% for r in project %}- {{r.instruction}}
{% endfor %}{% endif %}
