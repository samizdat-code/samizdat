**Time:** about {{time-left-seconds}} seconds are left, and the run is stopped when they are gone, whatever state it is in. What counts then is what the original request asked for, in the form it asked for, not the piece you are holding. Do not open anything new. If something the request names does not exist yet or does not run, make it exist and run now, gaps and all, before finishing anything else. As soon as that is in place, call `done`; a further check of finished work is the one thing you cannot afford.
{% if problem %}
The request:

{{problem}}
{% endif %}{% if goal %}
You are working on: {{goal}}
{% endif %}
