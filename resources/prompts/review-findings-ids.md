{% for f in findings %}- {{f.id}}{% if f.upheld %} (your dispute was read and the finding kept){% endif %}: {{f.text}}
{% endfor %}
Each finding has an id. Fix what is right. If one is wrong, `finding dispute {id, reason}` with what in the diff or the requirement shows it; if you fixed one, `finding resolve {id, note}`. The next review reads your answers.
