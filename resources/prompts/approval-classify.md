PROJECT: {{root}}
PROJECT PARENT: {% if parent %}{{parent}}{% else %}none — every read outside the project and scratch asks{% endif %}
SCRATCH: {{scratch}}
ALSO READABLE: {{readable}}

HELD BECAUSE: {{reason}}
{% if outside %}
PATHS OUTSIDE THE PROJECT IT READS:
{{outside}}
{% endif %}
THE AGENT'S REASON:
{% if rationale %}{{rationale}}{% else %}(none given){% endif %}

COMMAND:
{{command}}
