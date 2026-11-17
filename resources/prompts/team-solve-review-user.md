WHAT WAS ASKED:
{{problem}}

{% if written %}THE FILES THE RUN WROTE (this project is not under git, so there is no diff; each
file is shown as it stands now — what the run's tools wrote and anything else modified
under the project since the run started):
{{diff|default:"(the run's file tools wrote nothing)"}}{% else %}THE DIFF:
{{diff|default:"(no changes)"}}{% endif %}

THE CHECK:
{{check}}
