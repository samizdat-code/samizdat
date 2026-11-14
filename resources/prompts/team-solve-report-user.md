## The request
{{problem}}

## What each round's worker reported
{% for r in results %}- {{r}}
{% endfor %}
## Files changed (from git)
{% if files %}{% for f in files %}- {{f}}
{% endfor %}{% else %}(none recorded){% endif %}
## The change
{{diff|default:"(no diff available)"}}

## The check
{{check}}
