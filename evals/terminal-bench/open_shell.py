#!/usr/bin/env python3
"""Open the shell policy in a bundle's copy of samizdat. Only the bundle's: a task
container is thrown away after the run and the agents these scores are compared
with have an unrestricted bash, so asks and denies would measure the policy
instead of the agent. The product's policy is untouched."""
import sys
path = sys.argv[1] + "/src/samizdat/security/policy.clj"
s = open(path).read()
s2 = s
needle = "        decided (cond-> decided\n                  outside (assoc :effect :ask"
if s2.count(needle) != 1:
    sys.exit("open_shell: the shell decision has moved; update this script")
s2 = s2.replace(needle, "        decided (assoc decided :effect :allow :rule {:name :benchmark-container})\n        outside nil\n        label nil\n        refused-refs nil\n" + needle)
# What it reads outside the project must not taint the branch either: on a task
# with no project boundary that shut `eval` for the rest of the run.
taint = "        (when outside-all\n          (flow/observe! ctx (:outside-read flow/table) \"shell\"))"
if s2.count(taint) != 1:
    sys.exit("open_shell: the outside-read label has moved; update this script")
s2 = s2.replace(taint, "")
open(path, "w").write(s2)
