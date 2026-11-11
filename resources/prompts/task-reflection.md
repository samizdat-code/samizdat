You are recording what a coding session learned about a PROJECT, so the next
session does not start from nothing.

You are not summarising the session. Nobody will read a narrative of what
happened. What is wanted is the small set of durable facts a person joining
this codebase tomorrow would want to have been told — and nothing else.

CONCRETENESS IS THE WHOLE VALUE. Exact paths, exact commands, exact error text,
exact names. A line with no specific in it is worth nothing and costs a future
session the tokens to read it: "the tests are organised sensibly" is noise,
"tests live under test/ and run with `jolt -A:test`" is the point. If you do not
know something concretely, leave the section empty rather than filling it.

Never record a credential, token or key. Write [REDACTED] instead.

Reply in exactly these sections, and leave a section empty when you have
nothing concrete for it. Do not add sections, preamble or commentary.

## OVERVIEW
[At most one short paragraph: what this project IS, how it is laid out, how it
is built and tested. Write this ONLY if you now know it well enough to orient
somebody who has never seen the repository. There is at most one of these kept
per project and a new one replaces the old, so an incomplete guess is worse
than nothing.]

## FACTS
[One durable fact per line, each with a specific in it. Where things live, what
the build and test commands are, what the project does and does not use. Facts
about the PROJECT, not about your task.]

## RULES
[One per line: something that will hold again next time. "Reload the namespace
before re-running a test or you are testing the old definition." A rule is
worth recording only if breaking it costs a turn. Say what happens and why, not
"always" or "never": a bare standing rule is not kept.]

## GOTCHAS
[One per line: what wasted time here, with the exact error or refusal that
announced it. This is the highest-value section — a gotcha recorded once saves
every later session the turn it costs to rediscover.]

## WRONG
[Ids of memories you were shown that turned out to be wrong or stale, one per
line as `k-xxxxxx — why it is wrong`. A wrong memory left standing costs every
future run, and you are the only one in a position to notice.]

---

The session follows.
