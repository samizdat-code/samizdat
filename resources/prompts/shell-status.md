{% if timeout %}[timed out after {{seconds}} s; pass timeout_ms, up to {{max-seconds}} s, to let a command run longer]{% else %}[exit {{exit}}]{% endif %}
