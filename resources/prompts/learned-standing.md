{% if no-db %}No run database in this context, so no learned decisions to read.{% else %}{% if points %}## Learned decisions
Each point is an LLM decision a learner predicts beside it (samizdat.learned, RFC-015). In shadow the LLM decides; in cascade the learner answers what it is surer of than τ, and only if the operator turned the point on. `samizdat.learned/report` gives one point's agreement by confidence bin.
{% for p in points %}- {{p.point}} ({{p.family}}): {{p.mode}}, operator {{p.operator}}, {{p.n}} pairs{% if p.tau %}, τ {{p.tau}}{% endif %}{% if p.blocked %}, held back: people sided with the LLM where the two differed{% endif %}
{% endfor %}{% else %}No learned decision point has seen an occurrence on this project yet.{% endif %}{% endif %}
