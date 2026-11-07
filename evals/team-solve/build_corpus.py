#!/usr/bin/env python3
"""Build the team-solve eval corpus.

20 self-contained stdin/stdout problems, each with:
  task.md        the statement the loop reads
  tests/         PUBLIC paired samples (the loop sees these; the workflow may run them)
  hidden/        HIDDEN paired cases (only the grader touches these; the score)

Expected outputs are produced by the reference solutions in PROBLEMS — never
hand-typed — so a wrong reference shows up as a wrong corpus, and regenerating
is always one command. Written fresh for samizdat (2026-11); no LiveCodeBench
or GVS5H items, so no contamination and no license questions.

Usage: python3 evals/team-solve/build_corpus.py [--out evals/team-solve/problems]
Verifies as it goes: a reference must pass its own public AND hidden cases
(it refuses to write a problem whose reference fails — the expected outputs
are produced BY the reference, so this holds by construction), and the
degenerate `print(0)` answer must not pass any problem's hidden set.
Refuses to leave a broken problem behind.
"""

import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# name -> (statement, reference solution, public inputs, hidden inputs)
# Inputs are exact stdin strings; outputs are computed by running the reference.
PROBLEMS = {}

def problem(name, statement, reference, public, hidden):
    PROBLEMS[name] = (statement, reference, public, hidden)

# ---------------------------------------------------------------- 01 balanced
problem(
    "balanced-abc",
    """\
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
""",
    """\
s = input().strip()
from collections import defaultdict
count = defaultdict(int)
count[(0, 0)] = 1
a = b = c = 0
ans = 0
for ch in s:
    if ch == 'a': a += 1
    elif ch == 'b': b += 1
    else: c += 1
    ans += count[(a - c, b - c)]
    count[(a - c, b - c)] += 1
print(ans)
""",
    ["abcabc\n", "aaa\n", "aabbcc\n"],
    ["abc\n", "cbacba\n", "a" * 99991 + "b" * 3 + "c" * 3 + "a" * 5 + "\n",
     "abcabcabcabcabcabc" * 4000 + "\n"],
)

# ------------------------------------------------------------------ 02 kadane
problem(
    "max-subarray",
    """\
Given a list of N integers (possibly negative), print the maximum possible sum
of a non-empty contiguous subarray.

## Input
One line: N, then N integers separated by spaces.

## Output
One integer: the maximum subarray sum.

## Constraints
1 <= N <= 200000, |each| <= 10^9. O(N) time.

## Examples
- `5 -2 1 -3 4 -1 2 1 -5 4` (N=9) -> `6`
""",
    """\
line = input().split()
n = int(line[0])
xs = list(map(int, line[1:1 + n]))
best = cur = xs[0]
for x in xs[1:]:
    cur = x if cur < 0 else cur + x
    best = max(best, cur)
print(best)
""",
    ["9 -2 1 -3 4 -1 2 1 -5 4\n", "3 -5 -2 -8\n", "4 1 2 3 4\n"],
    ["1 42\n", "5 -1 -1 -1 -1 -1\n",
     "10 " + " ".join(str((-1) ** i * (i * 7919 % 100000)) for i in range(1, 11)) + "\n",
     "200000 " + " ".join(str((-1) ** i * ((i * 2654435761) % 2000000000 - 1000000000)) for i in range(200000)) + "\n"],
)

# ------------------------------------------------------------- 03 pair count
problem(
    "pair-sum-count",
    """\
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
""",
    """\
n, t = map(int, input().split())
xs = list(map(int, input().split()))
from collections import defaultdict
seen = defaultdict(int)
pairs = 0
for x in xs:
    pairs += seen[t - x]
    seen[x] += 1
print(pairs)
""",
    ["5 6\n1 5 3 3 3\n", "3 100\n1 2 3\n", "4 2\n1 1 1 1\n"],
    ["1 0\n0\n", "6 0\n5 -5 5 -5 0 0\n",
     "200000 100\n" + " ".join(str((i * 37 % 200) - 100) for i in range(200000)) + "\n",
     "10 2000000000\n1000000000 1000000000 0 0 0 0 0 0 0 0\n"],
)

# --------------------------------------------------------------- 04 brackets
problem(
    "bracket-repair",
    """\
A string of ( and ) characters is given. Print the minimum number of characters
that must be INSERTED to make it a balanced bracket sequence.

## Input
One line: the string (1 <= length <= 200000).

## Output
One integer: minimum insertions.

## Examples
- `())` -> `1`
- `((` -> `2`
""",
    """\
s = input().strip()
depth = 0
need = 0
for ch in s:
    depth += 1 if ch == '(' else -1
    if depth < 0:
        need += 1
        depth = 0
print(need + depth)
""",
    ["())\n", "((\n", "()\n"],
    ["(\n", ")()(\n", "(" * 50000 + ")" * 49999 + "\n",
     ")(" * 30000 + "\n"],
)

# ------------------------------------------------------------------ 05 joseph
problem(
    "josephus",
    """\
N people stand in a circle, numbered 1..N. Counting starts at person 1; every
K-th person leaves the circle until one remains. Print the number of the last
person standing.

## Input
One line: N and K (1 <= N <= 10^6, 1 <= K <= 10^6).

## Output
One integer.

## Examples
- N=5 K=2 -> `3`
- N=1 K=99 -> `1`
""",
    """\
n, k = map(int, input().split())
r = 0
for m in range(1, n + 1):
    r = (r + k) % m
print(r + 1)
""",
    ["5 2\n", "1 99\n", "7 3\n"],
    ["1000000 1\n", "999983 7\n", "10 10\n", "2 1000000\n"],
)

# -------------------------------------------------------------- 06 base add
problem(
    "base-add",
    """\
Add two non-negative integers written in base B (2 <= B <= 36) and print the
sum in the same base. Digits above 9 are uppercase letters A..Z. No leading
zeros except the single digit 0 itself.

## Input
Three lines: B, then the first number, then the second number.

## Output
One line: the sum in base B.

## Examples
- B=16, `FF`, `1` -> `100`
""",
    """\
b = int(input())
def val(ch):
    return int(ch) if ch.isdigit() else ord(ch) - 55
def digs(x):
    if x == 0:
        return "0"
    out = []
    while x:
        d = x % b
        out.append(str(d) if d < 10 else chr(55 + d))
        x //= b
    return "".join(reversed(out))
a = sum(val(c) * b ** i for i, c in enumerate(reversed(input().strip())))
c = sum(val(c) * b ** i for i, c in enumerate(reversed(input().strip())))
print(digs(a + c))
""",
    ["16\nFF\n1\n", "2\n1010\n1011\n", "10\n99\n1\n"],
    ["36\nZZ\n1\n", "2\n0\n0\n", "17\n2G\n1F\n", "36\n" + "Z" * 5000 + "\n" + "1\n"],
)

# ------------------------------------------------------------ 07 run length
problem(
    "run-length",
    """\
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
""",
    """\
s = input().strip()
out = []
i = 0
while i < len(s):
    j = i
    while j < len(s) and s[j] == s[i]:
        j += 1
    run = j - i
    out.append(s[i] + (str(run) if run > 1 else ""))
    i = j
print("".join(out))
""",
    ["aaabcc\n", "abc\n", "a\n"],
    ["a" * 200000 + "\n", "abababab\n", "aabbaabb\n", "z" * 199999 + "y\n"],
)

# ---------------------------------------------------------------- 08 spiral
problem(
    "spiral-print",
    """\
Print an R x C matrix of integers in spiral order (right, down, left, up,
repeating), values separated by single spaces on ONE line.

## Input
First line: R and C. Then R lines of C integers.

## Output
One line: all R*C values in spiral order, space-separated.

## Examples
- 3x3 matrix 1..9 by rows -> `1 2 3 6 9 8 7 4 5`
""",
    """\
r, c = map(int, input().split())
m = [list(map(int, input().split())) for _ in range(r)]
out = []
top, bottom, left, right = 0, r - 1, 0, c - 1
while top <= bottom and left <= right:
    for j in range(left, right + 1):
        out.append(m[top][j])
    top += 1
    for i in range(top, bottom + 1):
        out.append(m[i][right])
    right -= 1
    if top <= bottom:
        for j in range(right, left - 1, -1):
            out.append(m[bottom][j])
        bottom -= 1
    if left <= right:
        for i in range(bottom, top - 1, -1):
            out.append(m[i][left])
        left += 1
print(" ".join(map(str, out)))
""",
    ["3 3\n1 2 3\n4 5 6\n7 8 9\n", "1 5\n1 2 3 4 5\n", "2 2\n1 2\n3 4\n"],
    ["1 1\n42\n", "4 1\n1\n2\n3\n4\n", "5 3\n" + "\n".join(" ".join(str(i * 3 + j + 1) for j in range(3)) for i in range(5)) + "\n",
     "100 100\n" + "\n".join(" ".join(str(i * 100 + j + 1) for j in range(100)) for i in range(100)) + "\n"],
)

# ------------------------------------------------------------------ 09 roman
problem(
    "roman-sum",
    """\
Two integers are given as Roman numerals (I, V, X, L, C, D, M; 1 <= value
<= 3999 for each). Print the SUM as a Roman numeral. Subtractive notation
(IV, IX, XL, XC, CD, CM) is used in inputs and must be used in the output.

## Input
Two lines: one Roman numeral each.

## Output
One line: the sum as a Roman numeral.

## Examples
- `XIV` + `LX` = 74 -> `LXXIV`
""",
    """\
VALS = {'I': 1, 'V': 5, 'X': 10, 'L': 50, 'C': 100, 'D': 500, 'M': 1000}
def parse(s):
    total = 0
    for i, ch in enumerate(s):
        v = VALS[ch]
        if i + 1 < len(s) and VALS[s[i + 1]] > v:
            total -= v
        else:
            total += v
    return total
NUM = [(1000, 'M'), (900, 'CM'), (500, 'D'), (400, 'CD'), (100, 'C'), (90, 'XC'),
       (50, 'L'), (40, 'XL'), (10, 'X'), (9, 'IX'), (5, 'V'), (4, 'IV'), (1, 'I')]
def roman(n):
    out = []
    for v, s in NUM:
        while n >= v:
            out.append(s)
            n -= v
    return "".join(out)
print(roman(parse(input().strip()) + parse(input().strip())))
""",
    ["XIV\nLX\n", "I\nI\n", "MMMCMXCIX\nI\n"],
    ["DCCCXC\nCXX\n", "XLII\nVIII\n", "MCMXCIV\nMCMXCIV\n", "CD\nCD\n"],
)

# ------------------------------------------------------------ 10 word count
problem(
    "word-topk",
    """\
Given a text of lowercase words (space/newline separated) and an integer K,
print the K most frequent words, one per line, ties broken by lexicographic
order (smaller first).

## Input
First line: K. Then the text until EOF.

## Output
K lines: the words, most frequent first, ties lexicographic.

## Examples
- K=2, text `b a b a c` -> `a` then `b` (both appear twice; a < b)
""",
    """\
import sys
from collections import Counter
k = int(input())
words = sys.stdin.read().split()
counts = Counter(words)
order = sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))
print(chr(10).join(w for w, _ in order[:k]))
""",
    ["2\nb a b a c\n", "1\nx\n", "3\na b c a b c\n"],
    ["3\n" + " ".join(["alpha", "beta", "alpha", "gamma", "beta", "alpha"]) + "\n",
     "5\na a a b b c\n",
     "2\n" + ("lorem ipsum " * 1000) + "\n",
     "1\nz z z z z z z z z z\n"],
)

# ----------------------------------------------------------- 11 binary palin
problem(
    "binary-palindrome",
    """\
For each of T integers, print YES if its binary representation (without
leading zeros) is a palindrome, else NO.

## Input
First line: T. Then T lines, one integer each (1 <= v <= 10^18).

## Output
T lines: YES or NO.

## Examples
- 5 -> YES (101), 9 -> YES (1001), 6 -> NO (110)
""",
    """\
t = int(input())
for _ in range(t):
    b = bin(int(input()))[2:]
    print("YES" if b == b[::-1] else "NO")
""",
    ["3\n5\n9\n6\n", "1\n1\n", "2\n3\n7\n"],
    ["4\n2\n3\n10\n1000000000000000000\n", "5\n" + "\n".join(str(2 ** k - 1) for k in [1, 2, 3, 20, 40]) + "\n",
     "3\n341\n1023\n682\n"],
)

# --------------------------------------------------------------- 12 dijkstra
problem(
    "shortest-path",
    """\
A weighted undirected graph with N nodes (numbered 1..N) and M edges. Print
the shortest distance from node 1 to node N, or -1 if unreachable.

## Input
First line: N M. Then M lines: u v w (1-based nodes, 1 <= w <= 10^4).

## Output
One integer: the distance, or -1.

## Constraints
2 <= N <= 5000, 1 <= M <= 20000.

## Examples
- N=3 M=2, edges (1,2,5), (2,3,7) -> `12`
""",
    """\
import heapq
n, m = map(int, input().split())
adj = [[] for _ in range(n + 1)]
for _ in range(m):
    u, v, w = map(int, input().split())
    adj[u].append((v, w))
    adj[v].append((u, w))
dist = [None] * (n + 1)
pq = [(0, 1)]
while pq:
    d, u = heapq.heappop(pq)
    if dist[u] is not None:
        continue
    dist[u] = d
    for v, w in adj[u]:
        if dist[v] is None:
            heapq.heappush(pq, (d + w, v))
print(dist[n] if dist[n] is not None else -1)
""",
    ["3 2\n1 2 5\n2 3 7\n", "2 0\n", "4 3\n1 2 1\n2 3 1\n3 4 1\n"],
    ["2 1\n1 2 10000\n", "3 1\n1 2 1\n",
     "6 8\n1 2 3\n1 3 8\n2 3 2\n2 4 5\n3 4 6\n4 5 10000\n5 6 1\n2 5 7\n",
     "1000 999\n" + "\n".join(f"{i} {i + 1} {10000 - i}" for i in range(1, 1000)) + "\n"],
)

# ------------------------------------------------------------ 13 date delta
problem(
    "date-difference",
    """\
Print the number of days between two dates, given as YYYY-MM-DD (second minus
first; the answer is non-negative in every test). Use the proleptic Gregorian
calendar. Standard library date handling is allowed.

## Input
Two lines: the two dates.

## Output
One integer: days between.

## Examples
- 2026-01-01 and 2026-01-31 -> `30`
""",
    """\
from datetime import date
a = date(*map(int, input().split("-")))
b = date(*map(int, input().split("-")))
print((b - a).days)
""",
    ["2026-01-01\n2026-01-31\n", "2000-01-01\n2001-01-01\n", "2024-02-28\n2024-03-01\n"],
    ["1900-02-28\n1900-03-01\n", "2000-02-28\n2000-03-01\n", "1999-12-31\n2000-01-01\n",
     "1600-01-01\n2400-01-01\n"],
)

# ------------------------------------------------------------- 14 partition
problem(
    "equal-partition",
    """\
Can the given list of positive integers be split into two parts with equal
sums? Print YES or NO. Every element must be in exactly one part.

## Input
Two lines: N, then the N integers.

## Output
YES or NO.

## Constraints
1 <= N <= 400, 1 <= a[i] <= 500.

## Examples
- `1 5 11 5` -> YES (1+5+5 = 11)
""",
    """\
n = int(input())
xs = list(map(int, input().split()))
total = sum(xs)
if total % 2:
    print("NO")
else:
    half = total // 2
    reachable = {0}
    for x in xs:
        reachable |= {r + x for r in reachable if r + x <= half}
    print("YES" if half in reachable else "NO")
""",
    ["4\n1 5 11 5\n", "3\n1 2 5\n", "1\n2\n"],
    ["2\n500 500\n", "10\n" + " ".join(["3"] * 10) + "\n",
     "400\n" + " ".join(str((i * 7919) % 500 + 1) for i in range(400)) + "\n",
     "6\n2 2 2 2 2 4\n"],
)

# -------------------------------------------------------------- 15 lcs grid
problem(
    "grid-paths",
    """\
Count paths from the top-left cell (1,1) to the bottom-right cell (R,C) of a
grid, moving only right or down, through free cells ('.') and blocked cells
('#'). The count is modulo 1_000_000_007. Start and end cells are free.

## Input
First line: R C. Then R lines of C characters ('.' or '#').

## Output
One integer: number of paths mod 1e9+7.

## Examples
- 2x2 all free -> `2`
""",
    """\
r, c = map(int, input().split())
g = [input().strip() for _ in range(r)]
MOD = 10 ** 9 + 7
dp = [[0] * c for _ in range(r)]
dp[0][0] = 1 if g[0][0] == '.' else 0
for i in range(r):
    for j in range(c):
        if g[i][j] != '.':
            continue
        if i == 0 and j == 0:
            continue
        top = dp[i - 1][j] if i > 0 else 0
        left = dp[i][j - 1] if j > 0 else 0
        dp[i][j] = (top + left) % MOD
print(dp[r - 1][c - 1])
""",
    ["2 2\n..\n..\n", "1 1\n.\n", "2 2\n.#\n..\n"],
    ["3 3\n...\n.#.\n...\n", "1 10\n.........#\n".replace("#", ".") + "\n",
     "200 200\n" + "\n".join(("#." * 100 if i % 9 == 4 else "." * 200) for i in range(200)) + "\n",
     "3 3\n...\n...\n...\n"],
)

# ----------------------------------------------------------------- 16 infix
problem(
    "infix-eval",
    """\
Evaluate an arithmetic expression of non-negative integers, + - * and
parentheses, with standard precedence. Unary minus does NOT occur. Integer
arithmetic; subexpressions always evaluate to non-negative integers, and the
final result fits in 64 bits. Print the value.

## Input
One line: the expression (spaces allowed anywhere between tokens).

## Output
One integer: the value.

## Examples
- `2*(3+4)` -> `14`
- `1 + 2*3` -> `7`
""",
    """\
import re
s = input()
toks = re.findall(r"\\d+|[+*()-]", s)
pos = 0
def peek():
    return toks[pos] if pos < len(toks) else None
def take():
    global pos
    t = toks[pos]
    pos += 1
    return t
def prim():
    t = take()
    if t == "(":
        v = expr()
        take()  # )
        return v
    return int(t)
def term():
    v = prim()
    while peek() in ("*",):
        take()
        v *= prim()
    return v
def expr():
    v = term()
    while peek() in ("+", "-"):
        op = take()
        r = term()
        v = v + r if op == "+" else v - r
    return v
print(expr())
""",
    ["2*(3+4)\n", "1 + 2*3\n", "42\n"],
    ["((1+2)*(3+4))\n", "10-2*3\n", "2*3*4*5*6*7*8*9*10*11\n",
     "(1+(2*(3+(4*(5+(6*(7+8)))))))\n"],
)

# ------------------------------------------------------------ 17 matrix rot
problem(
    "matrix-rotate",
    """\
Rotate an R x C matrix of integers by 90 degrees clockwise and print it. The
result is C x R.

## Input
First line: R C. Then R lines of C integers.

## Output
C lines of R integers: the rotated matrix.

## Examples
- 2x3 `1 2 3` / `4 5 6` -> 3x2 `4 1` / `5 2` / `6 3`
""",
    """\
r, c = map(int, input().split())
m = [list(map(int, input().split())) for _ in range(r)]
for j in range(c):
    print(" ".join(str(m[i][j]) for i in range(r - 1, -1, -1)))
""",
    ["2 3\n1 2 3\n4 5 6\n", "1 1\n7\n", "2 2\n1 2\n3 4\n"],
    ["3 1\n1\n2\n3\n", "1 4\n1 2 3 4\n",
     "50 50\n" + "\n".join(" ".join(str((i * 50 + j) % 97) for j in range(50)) for i in range(50)) + "\n",
     "2 5\n-1 -2 -3 -4 -5\n6 7 8 9 10\n"],
)

# ------------------------------------------------------------- 18 bit count
problem(
    "popcount-sort",
    """\
Sort a list of distinct integers by the number of 1 bits in their binary
representation, ascending; ties by value ascending. Print the sorted list on
one line, space-separated.

## Input
Two lines: N, then the N integers.

## Output
One line: the sorted values.

## Examples
- `8 4 2 3` -> `2 4 8 3` (popcounts 1,1,1,2; ties by value)
""",
    """\
n = int(input())
xs = list(map(int, input().split()))
print(" ".join(map(str, sorted(xs, key=lambda v: (bin(v).count("1"), v)))))
""",
    ["4\n8 4 2 3\n", "3\n7 8 9\n", "1\n0\n"],
    ["5\n1 2 4 8 16\n", "6\n3 5 6 9 10 12\n",
     "100\n" + " ".join(str((i * 104729) % 1000000) for i in range(100)) + "\n",
     "2\n1023 1024\n"],
)

# ---------------------------------------------------------------- 19 prefix
problem(
    "shared-prefix",
    """\
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
""",
    """\
t = input().strip()
q = int(input())
for _ in range(q):
    s = input().strip()
    lo, hi = 0, len(s)
    while lo < hi:
        mid = (lo + hi + 1) // 2
        if s[:mid] in t:
            lo = mid
        else:
            hi = mid - 1
    print(lo)
""",
    ["banana\n3\nban\nnana\nx\n", "abc\n1\nabc\n", "a\n2\na\nb\n"],
    ["mississippi\n4\nmiss\nissi\nippi\nssissippi\n",
     "aaaa\n3\naaa\naaaa\naaaaaa\n",
     "".join("ab" for _ in range(100000)) + "\n3\nabab\nbaba\nab" * 1 + "\n",
     "z\n1\nzz\n"],
)

# ------------------------------------------------------------- 20 xor range
problem(
    "xor-range",
    """\
Define f(n) = 1 xor 2 xor ... xor n (with f(0) = 0). Given T ranges [L, R],
print f(L-1) xor f(R) — the xor of all integers from L to R — for each.

## Input
First line: T. Then T lines: L R (1 <= L <= R <= 10^18).

## Output
T lines: one integer each.

## Examples
- [3,5] -> 3^4^5 = 2
""",
    """\
def f(n):
    m = n % 4
    if m == 0: return n
    if m == 1: return 1
    if m == 2: return n + 1
    return 0
t = int(input())
for _ in range(t):
    l, r = map(int, input().split())
    print(f(l - 1) ^ f(r))
""",
    ["1\n3 5\n", "2\n1 1\n1 10\n", "1\n1 1000000000000000000\n"],
    ["3\n1 4\n5 8\n9 12\n", "1\n2 3\n", "2\n1000000000000000000 1000000000000000000\n7 7\n",
     "5\n" + "\n".join(f"{k * 10 ** 17 + 1} {k * 10 ** 17 + 10 ** 17}" for k in range(5)) + "\n"],
)

# --------------------------------------------------------------------- build

def run_reference(reference, stdin):
    r = subprocess.run([sys.executable, "-c", reference], input=stdin,
                       capture_output=True, text=True, timeout=20)
    if r.returncode != 0:
        raise RuntimeError(f"reference failed on {stdin[:40]!r}: {r.stderr[:300]}")
    return r.stdout

def write_case(directory, name, stdin, stdout):
    with open(os.path.join(directory, name + ".in"), "w") as f:
        f.write(stdin)
    with open(os.path.join(directory, name + ".out"), "w") as f:
        f.write(stdout)

def build(out_root):
    os.makedirs(out_root, exist_ok=True)
    for name, (statement, reference, public, hidden) in PROBLEMS.items():
        pdir = os.path.join(out_root, name)
        for sub in ("tests", "hidden"):
            d = os.path.join(pdir, sub)
            os.makedirs(d, exist_ok=True)
            for old in os.listdir(d):
                os.remove(os.path.join(d, old))
        with open(os.path.join(pdir, "task.md"), "w") as f:
            f.write(statement)
        for i, stdin in enumerate(public, 1):
            write_case(os.path.join(pdir, "tests"), str(i), stdin,
                       run_reference(reference, stdin))
        for i, stdin in enumerate(hidden, 1):
            write_case(os.path.join(pdir, "hidden"), str(i), stdin,
                       run_reference(reference, stdin))
        # The wrong-answer check: the degenerate `print(0)` program must FAIL
        # the hidden set of every problem. A hidden set where 0 is correct
        # for every case would give the campaign a meaningless floor. (A
        # problem where 0 is right for SOME case only makes the check
        # weaker, not wrong — it still has to fail the others.)
        hidden_dir = os.path.join(pdir, "hidden")
        ins = [f for f in os.listdir(hidden_dir) if f.endswith(".in")]
        zeros = [f for f in ins
                 if open(os.path.join(hidden_dir, f[:-3] + ".out")).read().strip() == "0"]
        if len(zeros) == len(ins):
            raise RuntimeError(
                f"{name}: the all-zeros program passes every hidden case — "
                "the corpus floor is meaningless; add a case with a non-zero answer")
        print(f"  built {name}: {len(public)} public, {len(hidden)} hidden")

if __name__ == "__main__":
    out = os.path.join(HERE, "problems")
    if len(sys.argv) == 3 and sys.argv[1] == "--out":
        out = sys.argv[2]
    print(f"building corpus at {out}")
    build(out)
    print("done")
