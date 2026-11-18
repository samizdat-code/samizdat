## Edits made on another model
This run is on {{model}}. Each edit below is the version in force, but it was made while the project ran on another model. An edit tuned to one model's habits can hold another one back, so check each against what this run is doing. If it still helps, keep it. If it only answered the old model's quirk, revert it under an `experiment` so the removal is measured too. If its purpose still applies but its form does not, revise it.
{% for e in edits %}- {{e.kind}} `{{e.name}}` v{{e.version}}, made on {{e.model}}{% if e.rationale %}: {{e.rationale}}{% endif %}
{% for a in e.attempts %}    - tried: {{a.change}}{% if a.model %} on {{a.model}}{% endif %}: {% if a.verdict %}{{a.verdict}}{% else %}not measured{% endif %}{% if a.settled %}, {{a.settled}}{% endif %}
{% endfor %}{% endfor %}
