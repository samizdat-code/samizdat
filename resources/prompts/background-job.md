{% if started %}Background job {{id}} started: `{{command|safe}}`. It keeps running after this call and is stopped when the run ends. Read what it writes with jobs({"action": "output", "id": "{{id}}"}), stop it with jobs({"action": "kill", "id": "{{id}}"}); you are told when it exits on its own.{% if output %}
Its first output:
{{output|safe}}{% else %}
It has written nothing yet.{% endif %}{% endif %}{% if failed %}Background job {{id}} (`{{command|safe}}`) exited at once with code {{exit}}, so nothing is running.{% if output %} Its output:
{{output|safe}}{% else %} It wrote nothing.{% endif %}{% endif %}{% if finished %}Background job {{id}} (`{{command|safe}}`) already finished, exit code 0. For a command that finishes, run it without `background`.{% if output %} Its output:
{{output|safe}}{% endif %}{% endif %}{% if cap %}Refused: this run already has {{max}} background jobs running, the limit. List them with jobs({"action": "list"}) and kill one you no longer need first.{% endif %}{% if error %}The background job could not be started: {{error|safe}}{% endif %}
