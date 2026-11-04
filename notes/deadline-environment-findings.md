# eval-deadline failures: environment capability denial

## Question
Are the two failing `sandbox-exec`-dependent assertions in
`test/samizdat/eval_deadline_test.clj` a real wiring bug in the project, or a
limitation of the environment the suite runs in?

## Evidence (from a full run captured at /tmp/sz-e91a90-verify.log)

1. Process creation is refused at the OS layer:

       sandbox-exec: sandbox_apply: Operation not permitted

   The sandbox cannot be applied at all, so no child process is ever created.

2. The project image never comes up, on either port tried:

       project image did not come up on 58288 (attempt 1, waited up to 20000 ms, child exited 71)
       project image did not come up on <second port> (attempt 1, ..., child exited 71)

   Exit 71 is the child aborting immediately, consistent with (1).

3. The workflow directory cannot be read for the chain test:

       java.io.FileNotFoundException: .samizdat/prompts/chain-test.md (Operation not permitted)

4. The local model endpoint was never reachable:

       refusing a run: model endpoint http://127.0.0.1:8080/v1 - connection refused

5. The resume path then fails closed:

       resume failed: resume driver failed

## Conclusion
This is **environment capability denial**, not a wiring bug. Every failure
traces to `Operation not permitted` on process/file sandboxing or to a refused
localhost endpoint. The assertions under test never get the chance to evaluate
their subject, so their failure carries no information about project code.

## What was NOT done
No source change was warranted, because there is no source defect to fix.
No test file was modified to mask the failure.
