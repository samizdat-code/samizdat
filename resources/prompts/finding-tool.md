{% if usage %}`finding` answers the reviewer's findings on the task you hold. Each finding the reviewer sends back has an id like `f-1a2b3c4d`.
  list                           The findings still standing on your task.
  dispute {id, reason}           You believe the finding is wrong: say why, concretely — what in the diff or the requirement shows it. The next review reads your reason and rules on it.
  resolve {id, note}             You fixed it: say how. The next review checks.
Fix what is right and dispute what is wrong; do not change working code only to make a mistaken finding go away.{% endif %}{% if no-task %}You hold no task, so there are no findings to answer.{% endif %}{% if listed %}{% if findings %}Findings on {{task}}:
{% for f in findings %}- {{f.id}} [{{f.status}}] {{f.text}}{% if f.reason %} — your answer: {{f.reason}}{% endif %}
{% endfor %}{% else %}No findings stand on {{task}}.{% endif %}{% endif %}{% if unknown %}No standing finding {{id}} on {{task}} — `finding list` shows them.{% endif %}{% if answered %}{{id}} {% if disputed %}disputed{% else %}marked resolved{% endif %}. The next review reads it. Finish again when the rest holds.{% endif %}
