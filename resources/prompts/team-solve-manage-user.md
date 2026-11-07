PROBLEM:
{{problem}}

CURRENT WORK:
{{current|default:"(none yet)"}}

NOTES:
{{notes}}

CURRENT TASK LIST:
{{tasks|default:"(none)"}}

LATEST WORKER RESULT: {{latest}}

PROPOSED NEW STEPS:
{{proposals|default:"(none)"}}
{% if time-left %}
TIME LEFT: about {{time-left}} seconds, and the run is stopped when they are gone.
{% endif %}{% if guidance %}
THE SUPERVISOR WATCHING THIS RUN SAYS:
{{guidance}}
{% endif %}
