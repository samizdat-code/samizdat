{{heading}}

Implementors: {{shipped}}/{{total}} shipped. Outcomes: {{outcomes}}
Reviewer: {{reviewer}}   Critic: {{critic}}

Per branch — where it is in its work and since when, its last write, then
turns / mechanics-thrash / fitness per turn (the number the cull reads):
{% for b in branches %}- {{b.id}}: {{b.state|default:"?"}}{% if b.task %}, task {{b.task}}{% endif %}{% if b.status %}{% ifunequal b.status "active" %}, {{b.status}}{% endifunequal %}{% endif %} since turn {{b.since}} ({{b.held}} turns); last write {% if b.wrote %}turn {{b.wrote}}{% else %}none{% endif %}; {{b.turns}} turns, {{b.thrash}} thrash{% if b.fitness %}, fitness {{b.fitness}}/turn{% endif %}{% if b.unmet %}, {{b.unmet}} gate(s) unmet{% endif %}{% if b.stuck %} — STUCK:{% if b.stuck.idle %} {{b.stuck.idle}} turns without a write{% endif %}{% if b.stuck.held %} {{b.stuck.held}} turns without moving{% endif %}{% if b.stuck.thrash %} {{b.stuck.thrash}} turns that made no call{% endif %}{% endif %}
{% endfor %}{% if stuck-mode %}
A branch that is moving is one line here, not its steps. Its steps are below
only once it is STUCK; to look at any branch sooner, `fetch_turn` the turns
you want.
{% endif %}{% if failures %}
Failures this run, newest last. Start HERE: read the failure's own words,
then `fetch_turn({turn: N, branch: "B"})` for the full record, then fix the
cause at the surface that governs it — a parse failure lives in the prompt
or call format, a provider failure at the endpoint or the context budget, a
tool failure in the work or the tool.

CLASSIFY BEFORE YOU REPAIR, and name the layer you are changing. Without that
step every failure reads as a prompt that needs rewording, because the prompt
is the surface nearest to hand. The layers, from the one you cannot touch to
the one you can change in a sentence: the KERNEL is mechanism and is not
yours; a CELL holds a decision; a MANIFEST holds the wiring between decisions;
`gates.edn` holds the number behind a decision; a PROMPT holds the words. A
symptom that is really about the world — a provider down, a fixture missing,
a test that was already red — belongs to none of them, and changing any layer
to answer it makes the loop worse. Say which layer owns the failure before you
open the experiment; if you cannot, you are about to change the nearest thing
rather than the right one.
{% if patterns %}
The SHAPE of them, commonest first — a count this high against one signature
is one fix, not many:
{{patterns}}
{% endif %}{% if failures.parse %}
Calls that did not parse ({{failures.parse.count}} total):
{{failures.parse.lines}}
{% endif %}{% if failures.provider %}
Provider failures ({{failures.provider.count}} total):
{{failures.provider.lines}}
{% endif %}{% if failures.tool %}
Tool failures ({{failures.tool.count}} total):
{{failures.tool.lines}}
{% endif %}{% if failures.wins %}
And what WORKED ({{failures.wins.count}} successful calls this run) — read
these before concluding the loop is broken, and before writing a rule that
would have stopped them:
{{failures.wins.lines}}
{% endif %}{% endif %}
{% if prescription %}This project has already tuned itself: {{prescription.names}}
piece(s) of userspace overridden ({{prescription.kinds}}){% if prescription.pct %},
now {{prescription.pct}}% the size of the templates they replaced{% endif %}.
Every rule you add is context every later run reads. Metan measured richer
context making a pre-optimized loop WORSE, so before writing another one, check
whether an existing rule is the thing that is wrong.

{% endif %}{% if gates %}Steering that is NOT working — a gate this branch has been told
by repeatedly and has not once done what it asked. Either the advice is wrong,
it is aimed at the wrong branch, or this model does not respond to advice at
all; all three are yours to fix, and more nagging is not the fix:
{{gates}}

{% endif %}Signals:
{% if signals %}{{signals}}{% else %}- none flagged; the loop looks healthy{% endif %}
