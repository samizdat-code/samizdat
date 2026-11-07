PROBLEM:
{{problem}}

PLAN:
{{plan}}

NOTES:
{{notes|default:"(none yet)"}}
{% if samples %}
The deliverable is `{{target}}`: a complete, self-contained Python 3 program using
only the standard library that reads stdin and prints only the answer. Public
sample tests are paired files `tests/N.in` and `tests/N.out`. Do not modify
anything under `tests/`.
{% endif %}{% if verify-cmd %}
The check that decides this work is `{{verify-cmd}}`. Run it before you finish.
{% endif %}
CURRENT WORK:
{{current|default:"(none yet)"}}

The PROBLEM is what counts, and you judge it yourself. YOUR TASK says where to start; it
does not narrow or replace the problem. If doing only the task would leave what the
problem asks for missing, do that too if you can, and otherwise say what is missing in
your report.

YOUR TASK: {{task}}
