WHAT WAS ASKED:
{{problem}}

{% if written %}THE FILES THE RUN WROTE (this project is not under git, so there is no diff; each
file is shown as it stands now, and a file changed only by a shell command is not listed):
{{diff|default:"(the run's file tools wrote nothing)"}}{% else %}THE DIFF:
{{diff|default:"(no changes)"}}{% endif %}

THE CHECK:
{{check}}
