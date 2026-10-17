## What the last review of this task said
These findings were sent back to the owner last time, with how the owner answered each one. Rule on every one of them in your findings: repeat a finding (keeping its words) if it still holds — including one the owner disputed, if the dispute is wrong — and leave it out if the diff now fixes it or the owner's dispute is right. Do not invent new findings to replace ones you drop; raise a new one only if it is new in the diff.
{% for f in findings %}- {{f.id}} [{{f.status}}] {{f.text}}{% if f.reason %}
  owner: {{f.reason}}{% endif %}
{% endfor %}
