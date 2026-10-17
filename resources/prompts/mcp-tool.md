{% if usage %}`mcp` reaches the tools of the MCP servers this project's configuration names.
  servers                              The servers you may use, with their policy.
  tools {server}                       A server's tools, each with what it does and the arguments it takes.
  call {server, tool, arguments}       Call one. `arguments` is an object fitting the tool's schema. A server's policy may put the call to a person first, or refuse tools it does not trust.{% endif %}{% if listed %}{% if servers %}MCP servers:
{% for s in servers %}- {{s.name}} (policy {{s.policy}}{% if s.running %}, running{% endif %})
{% endfor %}{% else %}No MCP servers are configured for this project.{% endif %}{% if untrusted %}
Not available: {{untrusted}} — named by this project's own config, which the person running the harness has not trusted.{% endif %}{% endif %}{% if untrusted %}{% if server %}Refused: `{{server}}` is named only by this project's own config.edn, and the person running the harness has not listed this project under :mcp :trusted-roots, so it may not start a process here.{% endif %}{% endif %}{% if unknown %}No MCP server named `{{server}}` — `mcp servers` lists them.{% endif %}{% if tools %}Tools on {{server}}:
{% for t in entries %}- {{t.name}}: {{t.description}}
  arguments: {{t.schema}}
{% endfor %}{% endif %}{% if denied %}Refused: {{server}}/{{tool}} is not allowed under the server's {{policy}} policy.{% endif %}{% if declined %}{{server}}/{{tool}} was not approved, so it did not run.{% endif %}{% if failed %}{{server}}/{{tool}} failed:
{{text}}{% endif %}{% if called %}{{text}}{% endif %}{% if ask-reason %}The agent wants to call MCP tool {{server}}/{{tool}}.{% endif %}
