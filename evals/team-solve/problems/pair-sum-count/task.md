Given N integers and a target T, print how many unordered pairs (i < j) have
a[i] + a[j] = T.

## Input
First line: N and T. Second line: N integers.

## Output
One integer: the number of pairs.

## Constraints
1 <= N <= 200000, |a[i]|, |T| <= 10^9. O(N) or O(N log N).

## Examples
- N=5 T=6, values `1 5 3 3 3` -> `4`  (1+5 once, 3+3 three times)
