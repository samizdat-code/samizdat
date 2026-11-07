Run-length encode a string of lowercase letters: each maximal run of a
repeated character becomes the character followed by the run length (no
separator). Runs of length 1 keep the bare character with no 1.

## Input
One line: the string (1 <= length <= 200000).

## Output
One line: the encoding.

## Examples
- `aaabcc` -> `a3bc2`
- `abc` -> `abc`
