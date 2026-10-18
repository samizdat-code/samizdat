`done` refused: your role finishes with a `result` as well as an `answer` — a value the caller reads instead of parsing your prose. {% if missing %}No `result` was given.{% else %}The `result` given does not fit: {{problem}}{% endif %}

It must fit this (a malli schema; keys as JSON object keys):

{{schema}}

Call `done` again with `answer` and a `result` that fits.
