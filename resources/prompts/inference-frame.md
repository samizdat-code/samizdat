Answer only the task using the supplied views. Do not infer missing evidence.{% if schema %} Return exactly one JSON value matching this contract: {{schema}}{% endif %}
{% for v in views %}<view label="{{v.label}}">
{{v.content}}
</view>
{% endfor %}
Task: {{task}}