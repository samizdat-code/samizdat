## This session so far ({{turns}} turns){% if fitness %} — fitness {{fitness}}/turn{% endif %}
{% if tools %}Tools: {{tools}}
{% endif %}{% if signals %}Call mechanics: {{signals}}
{% endif %}{% if verify %}Ship verification: {{verify}}
{% endif %}{% if gates %}Gates: {{gates}}
{% endif %}{% if since %}
### Since your last look ({{since.turns}} turns){% if since.fitness %} — fitness {{since.fitness}}/turn{% endif %}
This is the interval your last change was in force for, and the only thing here that answers whether it helped. The tally above is the whole session and dilutes it.
{% if since.tools %}Tools: {{since.tools}}
{% endif %}{% if since.signals %}Call mechanics: {{since.signals}}
{% endif %}{% if since.verify %}Ship verification: {{since.verify}}
{% endif %}{% if since.gates %}Gates: {{since.gates}}
{% endif %}{% endif %}{% if quiet %}
### Since your last look
Nothing has happened since you looked: no turn has been taken. Whatever you changed has not been exercised yet, so there is nothing to judge it on — wait rather than changing something else.
{% endif %}{% if experiments %}
### Changes you have made, and what happened
{{experiments}}

{% if unsettled %}**{{unsettled}} change(s) above measured worse, unchanged, costly or below the best already kept, and you have not acted on them.** Do that first, before anything else: revert them, or say why you are keeping one. A modification the evidence says is not helping, left in place because nobody got back to it, is exactly what this measurement exists to prevent — and the next supervisor inherits it with no sign it was ever questioned.

{% endif %}A change with verdict `better` earned its place — say so and leave it. `worse` means REVERT it; that is not a failure, it is the experiment working. `unchanged` means the change was not the fix, so revert it too rather than leaving a change nobody can justify. `costly` means it gained less than the tokens it added are worth: revert it, or say why the price is right. `cheaper` means nothing measurable moved and the turns cost less — keep it. `below best` means it beat the stretch before it but not what a change you already kept reached — revert it. `too early` means wait — do not stack another change on an unmeasured one. `confounded` means the number moved but not because of you: the world moved under the measurement, so decide on the reasoning and say that is what you did. It still holds the slot until you settle it, the same as any other change in flight.
{% if plateau %}
**The last {{plateau}} changes you settled all measured unchanged.** Another threshold or wording tweak is unlikely to be the one. Stop adjusting constants and look for work whose result is thrown away: branches culled after spending their turns, edits refused after the gate replayed them, compaction re-reading a window, retries that end where they started, tool calls whose output nobody reads. Find the largest such cost in this session and make the next change remove it.

{% endif %}{% if miscalibrated %}
**Your predicted sizes keep missing: {{miscalibrated}}.** `overshot` means far less moved than you said, `undershot` far more, `wrong-way` the opposite direction. Your picture of where this loop spends its turns is off; revise it from the tally above before the next hypothesis, rather than adjusting the size you predict.

{% endif %}{% endif %}{% if findings %}
### Worth your attention
{{findings}}
{% endif %}