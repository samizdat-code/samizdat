MEMORY ({{kind}}):
{{memory}}

SOURCE — the turns it was written from:
{% for l in source %}{{l}}
{% endfor %}
OUTCOME — a later session was shown it and ended {{status|default:"unknown"}}{% if answer %} ("{{answer}}"){% endif %}. What it did after:
{% for l in outcome %}{{l}}
{% endfor %}
