Rebuild the program in this directory from scratch, in Clojure on Jolt.

`./executable` is the original program, with its source removed. Any documentation it shipped with is here too. Work out what it does by reading the documentation and running it, then write your own implementation that behaves the same: same output, same exit codes, same handling of flags, input and errors. It will be tested against many behavioural tests you cannot see, and the score is the fraction that pass, so cover as much of its behaviour as you can, including the edge cases.

Requirements:

- All of the implementation is Clojure source (`.clj`) run by Jolt. Do not build it with another compiler or language, and do not call another language's programs at run time. Jolt is on the PATH; its guide is at `/opt/jolt/llms.txt`.
- Provide `compile.sh` in this directory. Run from this directory on a clean checkout, it must use `jolt build` to produce a standalone binary named `./executable`, for example:

      #!/bin/sh
      set -e
      jolt build -m my.core -o executable

- Building replaces `./executable`, so keep a copy of the original somewhere outside this directory, such as `/tmp/original`, if you still want to compare against it. Submitting a copy of the original, or a wrapper around it, scores zero.
- Do not look for the original program's source code, here or anywhere else.

The upstream project's name and repository may be visible. Treat this as a clean-room rebuild from behaviour and documentation.
