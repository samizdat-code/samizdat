WHAT WAS ASKED:
{{problem}}

{% if written %}THE FILES THE RUN WROTE (git has no diff to show here; each file is shown as it
stands now — what the run's tools wrote and anything else modified under the project since
the run started):
{{diff|default:"(the run's file tools wrote nothing)"}}{% else %}THE DIFF:
{{diff|default:"(no changes)"}}{% endif %}

THE CHECK:
{{check}}
