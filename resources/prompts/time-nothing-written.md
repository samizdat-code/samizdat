**Time:** about {{time-left-seconds}} seconds are left and nothing has been written yet. Only what is in a file counts when the run stops, and writing takes time too. Stop investigating and use write_file now: write the first version of what the request names, with what you already know, then build it and run it. Leave gaps for what you do not know yet, and check them against the running program afterwards. Keep each file short so that one mistake does not cost you a whole rewrite. If a file will not load, fix that spot with edit_file rather than sending the file again.
{% if problem %}
The request:

{{problem}}
{% endif %}