**Time:** about {{time-left-seconds}} seconds are left. Check now: does what the request asks for build and run end to end, the way the request says it will be run? If it does, carry on. If it does not, that comes next, before any more work on the parts: wire up what you have, with a stub that fails with a message for each missing part, build it and run it. Do not write the next part first.
{% if problem %}
The request:

{{problem}}
{% endif %}
