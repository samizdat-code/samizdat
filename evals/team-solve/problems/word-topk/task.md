Given a text of lowercase words (space/newline separated) and an integer K,
print the K most frequent words, one per line, ties broken by lexicographic
order (smaller first).

## Input
First line: K. Then the text until EOF.

## Output
K lines: the words, most frequent first, ties lexicographic.

## Examples
- K=2, text `b a b a c` -> `a` then `b` (both appear twice; a < b)
