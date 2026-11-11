You are given a string S of length N (1 <= N <= 200000) over the alphabet {a, b, c}.
A substring is *balanced* if it contains the same number of 'a', 'b' and 'c'.

Print the number of non-empty balanced substrings of S.

## Input
A single line: the string S.

## Output
One integer: the number of balanced substrings.

## Constraints
N up to 200000; the solution must run in O(N) or O(N log N) time.

## Examples
- `abcabc` -> `5`  (abc, bca, cab, abc, abcabc)
- `aaa` -> `0`
