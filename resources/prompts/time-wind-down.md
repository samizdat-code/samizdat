**Time:** about {{time-left-seconds}} seconds are left, and the run is stopped when they are gone, whatever state it is in. What counts then is what the original request asked for, in the form it asked for, not the piece you are holding. Do not open anything new. If something the request names does not exist yet or does not run, make it exist and run now, gaps and all, before finishing anything else. Once it runs, keep fixing what you already know is wrong, smallest first, rebuilding after each fix so what is on disk always works. Call `done` when nothing you know of is left; you will be told when time is up.
{% if problem %}
The request:

{{problem}}
{% endif %}{% if goal %}
You are working on: {{goal}}
{% endif %}
