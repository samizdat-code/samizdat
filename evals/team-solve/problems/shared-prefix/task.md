Given Q queries, each a string, and a fixed text T (all lowercase), print for
each query the length of the longest prefix of the query that is also a
substring of T.

## Input
First line: T. Second line: Q. Then Q lines of queries.

## Output
Q lines: the longest prefix length (0 if the first character is absent).

## Constraints
|T| <= 200000, Q <= 2000, |query| <= 2000.

## Examples
- T=`banana`, query `ban` -> `3`; query `nana` -> `4`; query `x` -> `0`
