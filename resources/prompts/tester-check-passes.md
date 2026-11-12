{% if commands %}These failures are reported with commands that exit 0 when run now:
{% for c in commands %}- `{{c}}`
{% endfor %}
Before the work ships, the harness reruns every failing command and reads only its exit code, so a check that prints a defect and exits 0 counts as a pass and the defect ships. Make each check exit non-zero when what it reports is wrong (`sys.exit(1)`, `exit 1`, an `assert`), run it to see it fail, and call `done` again with the same commands. If one really passes now, leave it out of `failing`.
{% endif %}{% if not-runnable %}These `failing` entries do not start with a command the shell can run:
{% for c in not-runnable %}- `{{c}}`
{% endfor %}
Each entry is "<the command to rerun it> — expected <...>, got <...>". Put a finding that no command can check in your answer instead, and keep `failing` to checks the harness can rerun.
{% endif %}