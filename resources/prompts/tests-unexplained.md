This change alters tests that were there when the run started, and the answer does not say why. A test is the definition of done that existed before this work; changing it to get green and changing it because it was wrong look the same in a diff, and only you can say which it was.

{% for t in missing %}- **{{t.key}}** ({{t.path}}) — {{t.kind}}
{% endfor %}
A remembered procedure ("update the test when it disagrees") is not a reason: say what in this task's statement or this run's evidence makes the old assertion wrong. A test that fails against the code you were given is evidence of a defect until the task says otherwise; rewriting its expected value to the code's output, or moving its input away from the failing case, hides the defect.

If a change was not meant, put the test back. Otherwise call `done` again with a `changed_tests` entry for each: `"changed_tests": [{"test": "{{missing.0.key}}", "reason": "what was wrong with the old test, or where it went, and what now pins the behaviour"}, …]`.
