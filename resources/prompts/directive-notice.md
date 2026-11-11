{% ifequal issuer "execution" %}A background execution you started has reported back:{% else %}{% ifequal issuer "heldout" %}The held-out check has reported:{% else %}{% ifequal issuer "ship-review" %}A branch is waiting on your review:{% else %}A report from the harness:{% endifequal %}{% endifequal %}{% endifequal %}

{{text}}
