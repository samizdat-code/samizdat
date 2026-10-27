{% if stable %}Contextual project guidance:
{% for rule in stable %}[{{rule.id}}] {{rule.text}}
{% endfor %}{% endif %}{% if dynamic %}Current contextual guidance:
{% for rule in dynamic %}[{{rule.id}}] {{rule.text}}
{% endfor %}{% endif %}