Define f(n) = 1 xor 2 xor ... xor n (with f(0) = 0). Given T ranges [L, R],
print f(L-1) xor f(R) — the xor of all integers from L to R — for each.

## Input
First line: T. Then T lines: L R (1 <= L <= R <= 10^18).

## Output
T lines: one integer each.

## Examples
- [3,5] -> 3^4^5 = 2
