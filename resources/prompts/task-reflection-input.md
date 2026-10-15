TASK: {{problem}}

WHAT THIS SESSION DID:
{% for line in turns %}{{line}}
{% endfor %}{% if memories %}
MEMORIES YOU WERE SHOWN (flag any that are wrong):
{% for m in memories %}{{m.id}} [{{m.kind}}] {{m.content}}
{% endfor %}{% endif %}{% if examples %}

MEMORIES THAT HELD UP when checked against the session they came from — write yours this concretely:
{% for m in examples %}[{{m.kind}}] {{m.content}}
{% endfor %}{% endif %}
