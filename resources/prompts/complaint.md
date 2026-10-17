A person using this agent harness has a complaint about how the model has been behaving. Turn it into ONE correction rule the harness will show the model whenever the same behaviour shows up again.

The complaint:

{{complaint}}

The model's most recent turns, which the complaint is about:
{% for o in outputs %}
--- turn ---
{{o}}
{% endfor %}
{% if miss %}
Your last draft used the trigger `{{miss}}`, which does not occur in any of those turns, so the rule would never fire. Pick a phrase that is actually in them.
{% endif %}
Reply with one EDN map and nothing else:

{:id "short-kebab-case-name" :instruction "what the model should do instead, in one or two sentences, addressed to the model" :trigger "a short literal phrase, copied exactly from the turns above, that marks the behaviour"}

The trigger is matched literally and case-insensitively against what the model writes, so it must be text the model produced, short (a few words), and specific to the behaviour complained about rather than common to every turn.
