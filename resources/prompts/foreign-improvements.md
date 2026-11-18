## What other projects tried on the same parts
Problems that other projects of this person met on prompts, cells, manifests or policies this project also has, with what was tried and what it did. It is evidence from a different setting. Weigh it less than this project's own record, and less again when the model differs too. Reuse a diagnosis when it fits the failure here, and say which project it came from.
{% for p in problems %}
{{p.name}} (from {{p.project}})
{% for a in p.attempts %}  - {{a.change}}{% if a.target %} [{{a.target}}]{% endif %}{% if a.model %} on {{a.model}}{% endif %}: {% if a.verdict %}{{a.verdict}}{% else %}not measured{% endif %}{% if a.settled %}, {{a.settled}}{% endif %}{% if a.fixed %}; fixed {{a.fixed}}{% endif %}{% if a.missed %}; missed {{a.missed}}{% endif %}
{% endfor %}{% endfor %}
