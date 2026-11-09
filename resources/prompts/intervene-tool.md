{% if needs-kind %}`intervene` needs a `kind` — what you want done to the run.

{% for k in kinds %}- `{{k.0}}` — {{k.1}}
{% endfor %}
Call it as `intervene({"kind": "message", "branch": "T0", "text": "..."})`.
{% endif %}{% if unknown %}`{{unknown}}` is not a directive this harness has. The ones it does:

{% for k in kinds %}- `{{k.0}}` — {{k.1}}
{% endfor %}
Pick the closest and call again. To tell a branch something, that is
`message`.
{% endif %}{% if submitted %}Directive queued: **{{kind}}**{% if target %} against `{{target}}`{% endif %}.

{% ifequal kind "message" %}The branch reads it at the top of its next turn, above every machine gate.{% else %}{% ifequal kind "review" %}The branch reads it at the top of its next turn, above every machine gate.{% else %}It is applied at the boundary that owns `{{kind}}`, not this instant.{% endifequal %}{% endifequal %}

{% if text %}What it will read: {{text}}

{% endif %}{% if expect %}It is judged on whether the branch's next turns do `{{expect}}`.{% else %}With no `expect`, it is judged on whether the branch stops doing what it was doing when this arrived; name an `expect` (a tool, a command, a file) when you know what the right next move is.{% endif %} Your next look says whether it was acted on and what the branch did next.

You are steering a run that is still going. Watch what it does with
this before sending another: a second directive on top of an unread first one
is noise, and a branch that gets a new instruction every turn never finishes
any of them.
{% endif %}{% if refused %}The harness declined that directive: {{refused}}

Your call was well formed — this is a policy refusal, not a mistake to repair.
`cull` is refused for the last running branch, because a run with no branches
cannot recover. If you want the run to end, let it end on its own terms or
raise the problem rather than removing the only worker.
{% endif %}
{% if contradicted %}That directive says `{{tool}}` does not work, and this run's own record says otherwise: `{{tool}}` succeeded {{worked}} time(s) in the turns already journalled.

One failure is not a property of the environment. The shape this refuses is a
real one — a supervisor once read a single failed `require` at its own turn 2
as "requires do not work in this project", wrote it as a standing directive,
and the implementer stopped trying something that worked; that same run had
already required four namespaces successfully by turn 14.

If you meant a narrower claim, say the narrower thing: which call, on which
turn, with what error. If you believe the record is wrong, say what you
observed and why it contradicts the journal — that is a finding worth having,
and it is not this directive.

What you tried to send: {{text}}
{% endif %}
{% if nobody-to-read %}Not sent: no implementer branch is still running to read a `{{kind}}`{% for e in ended %}{% if forloop.first %} ({% else %}, {% endif %}`{{e.id}}` ended {{e.status}}{% if forloop.last %}){% endif %}{% endfor %}. The run is finishing; what you saw belongs in `remember`, or a change to the loop, so the next run starts from it.
{% endif %}