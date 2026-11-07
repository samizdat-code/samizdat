The team is stuck: {{why}}. Do NOT patch the existing work. Write a FRESH attempt
using a technique different from every one tried so far.

Tried so far:
{% for a in attempts %}- round {{a.round}}: {{a.task}} -> {{a.gate}}
{% endfor %}
