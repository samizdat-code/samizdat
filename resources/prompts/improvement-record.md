## The improvement record
Every change made to this project's harness, filed under the problem it was meant to fix, with what it predicted and what it did. It outlives the runs that made it, so treat it as prior experience, not as instructions:
- Look at the model and edit behind each attempt before reusing it. What helped on one model can hurt another.
- When an existing problem explains the failure in front of you, start from it: cite it by id in `experiment {problem: "P3"}`, and build on what was tried. Name a new problem only when none of these explains it.
- Keep, revise, replace or remove an earlier edit when the evidence calls for it. Its reasons still apply even when its implementation does not.
- Try a direction already tested again only when there is new evidence. Say what that evidence is in the hypothesis.
- Before measuring, name in `fixes` the battery cases you expect the edit to fix. A prediction written after the result is not a prediction.
{% for p in problems %}
P{{p.id}} {{p.name}}
{% for a in p.attempts %}  - {{a.change}}{% if a.target %} [{{a.target}}]{% endif %}{% if a.model %} on {{a.model}}{% endif %}: {% if a.verdict %}{{a.verdict}}{% else %}not measured{% endif %}{% if a.settled %}, {{a.settled}}{% endif %}{% if a.hypothesis %}; expected: {{a.hypothesis}}{% endif %}{% if a.predicted %}; predicted to fix {{a.predicted}}{% endif %}{% if a.fixed %}; fixed {{a.fixed}}{% endif %}{% if a.missed %}; missed {{a.missed}}{% endif %}{% if a.unresolved %}; still unresolved {{a.unresolved}}{% endif %}
{% endfor %}{% endfor %}
