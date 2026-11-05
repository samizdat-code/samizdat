#!/usr/bin/env bash
# team-solve. Every worker gets one turn, which cannot write and check a program.
# Out: `budget`, or a policy edit to :worker-max-turns.
sed 's/:worker-max-turns 15/:worker-max-turns 1/' "$1/resources/gates.edn" > "$2/.samizdat/gates.edn"
grep -q ':worker-max-turns 1$' "$2/.samizdat/gates.edn" || grep -q ':worker-max-turns 1\b' "$2/.samizdat/gates.edn"
