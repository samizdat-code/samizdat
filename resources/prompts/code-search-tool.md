{% if usage %}code_search({query, limit?}) ranks the project's source by how well each part fits the words in `query` — identifiers, the words in a comment or an error. Give it words, not a regex; for an exact pattern use grep.{% endif %}{% if failed %}The code index could not be searched: {{detail}}{% endif %}{% if empty %}No code matches {{query}}. Try the identifier itself, or fewer words; for an exact pattern use grep.{% endif %}{% if found %}{{count}} best match{% if plural %}es{% endif %} for {{query}}, best first (path:lines, score):
{% for h in hits %}
{{h.path}}:{{h.lines}} ({{h.score}})
{{h.snippet}}
{% endfor %}
read_file a path with `offset` at its line to see the whole of one.{% endif %}
