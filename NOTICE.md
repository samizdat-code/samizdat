# Third-party work

## GVS5H

`resources/prompts/program-*.md` (except `program-guard-*`), the program path in
`resources/cells/team_solve.clj` and the readers in `src/samizdat/agent/sections.clj`
are ported from `codebase/v2-current/escalation/multiagent.py` in
[GVS5H](https://github.com/slee-persis/GVS5H), under the MIT License:

    Copyright (c) 2026 Persis Capital Inc.

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.

`test/samizdat/fixtures/gvs5h_parity.json` holds what that code sends a scripted model.

## memdoor

The parity scenarios in `evals/team-solve/parity/gen_golden.py` and the A and B ideator
directives (`resources/prompts/team-solve-ideator-*.md`) come from
[memdoor](https://github.com/guregodevo/memdoor), `examples/workflows/team-solve`, under
the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0. Both are
modified here. Its reports of the two loss modes and their proposed fixes are the basis of
the program path's `:loss-guards?`.

memdoor's NOTICE reads: Memdoor. Copyright 2026 Gregory Desvaux.

## What this project added

The ledger in the database, the guards' counterexample check, the supervisor, and the
tool-using worker and gates for repository work are this project's. On a repository,
team-solve is its own workflow and makes no claim on either project's results.
