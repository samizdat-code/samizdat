#!/usr/bin/env python3
"""Build the hard corpus: problems whose public samples do not reveal the trap.

The first corpus is easy enough that a strong model solves it alone, and a test
everyone passes measures nothing. Each problem here has a reference solution and a
brute force. The builder cross-checks the two on random small inputs and refuses to
write a problem where they differ, so an expected output is never just one program's
opinion. Hidden cases add stress inputs and the trap the samples omit.

Written fresh for samizdat. Usage: python3 evals/team-solve/build_hard.py [out]
"""
import itertools
import os
import random
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
SEED = 20261105
PROBLEMS = []


def problem(name, statement, reference, brute, public, small, stress):
    PROBLEMS.append((name, statement, reference, brute, public, small, stress))


def run(src, stdin, timeout=10):
    t = time.time()
    r = subprocess.run([sys.executable, "-c", src], input=stdin, capture_output=True,
                       text=True, timeout=timeout)
    if r.returncode:
        raise RuntimeError(r.stderr[-400:])
    return r.stdout, time.time() - t


# 1 ---------------------------------------------------------------------------
problem("tree-independent", """\
A tree has N nodes numbered 1..N, each with an integer weight (possibly negative).
Choose a set of nodes, no two adjacent, maximizing the total weight. The empty set is
allowed. Print the maximum total.

## Input
Line 1: N (1 <= N <= 200000). Line 2: N weights, each in [-10^9, 10^9].
Next N-1 lines: edges u v.

## Output
The maximum total.
""", """\
import sys
def main():
    d = sys.stdin.read().split(); n = int(d[0]); w = [0] + [int(x) for x in d[1:n+1]]
    g = [[] for _ in range(n+1)]; p = n+1
    for _ in range(n-1):
        u, v = int(d[p]), int(d[p+1]); p += 2; g[u].append(v); g[v].append(u)
    order, par = [], [0]*(n+1); st = [1]; par[1] = -1
    while st:
        x = st.pop(); order.append(x)
        for y in g[x]:
            if y != par[x]: par[y] = x; st.append(y)
    a = [0]*(n+1); b = [0]*(n+1)
    for x in reversed(order):
        a[x] += w[x]
        q = par[x]
        if q > 0:
            a[q] += b[x]; b[q] += max(a[x], b[x])
    print(max(a[1], b[1]))
main()
""", """\
import sys
d = sys.stdin.read().split(); n = int(d[0]); w = [int(x) for x in d[1:n+1]]; p = n+1; e = []
for _ in range(n-1):
    e.append((int(d[p])-1, int(d[p+1])-1)); p += 2
best = 0
for m in range(1 << n):
    if any(m >> u & 1 and m >> v & 1 for u, v in e): continue
    best = max(best, sum(w[i] for i in range(n) if m >> i & 1))
print(best)
""", ["3\n1 2 3\n1 2\n2 3\n", "1\n-5\n", "4\n5 1 1 5\n1 2\n2 3\n3 4\n"],
    lambda r: (lambda n: f"{n}\n{' '.join(str(r.randint(-9, 9)) for _ in range(n))}\n" + "".join(f"{r.randint(1, i)} {i+1}\n" for i in range(1, n)))(r.randint(1, 10)),
    # A 200000-long chain: recursion dies here, and the samples never show it.
    lambda r: "200000\n" + " ".join(str(r.randint(-10**9, 10**9)) for _ in range(200000)) + "\n" + "".join(f"{i} {i+1}\n" for i in range(1, 200000)))

# 2 ---------------------------------------------------------------------------
problem("kth-table", """\
The N by M multiplication table holds i*j at row i, column j (1-indexed). Print the
K-th smallest value in it, counting duplicates.

## Input
One line: N M K (1 <= N, M <= 100000, 1 <= K <= N*M).

## Output
The K-th smallest value.
""", """\
n, m, k = map(int, input().split())
if n > m: n, m = m, n
def count(x):
    c = 0; i = 1
    while i <= n and i <= x:
        q = x // i
        j = min(n, x // q)
        c += (j - i + 1) * min(q, m)
        i = j + 1
    return c
lo, hi = 1, n*m
while lo < hi:
    mid = (lo+hi)//2
    if count(mid) >= k: hi = mid
    else: lo = mid+1
print(lo)
""", """\
n, m, k = map(int, input().split())
print(sorted(i*j for i in range(1, n+1) for j in range(1, m+1))[k-1])
""", ["2 3 4\n", "3 3 5\n", "1 5 5\n"],
    lambda r: (lambda n, m: f"{n} {m} {r.randint(1, n*m)}\n")(r.randint(1, 30), r.randint(1, 30)),
    lambda r: "100000 99999 5000000000\n")

# 3 ---------------------------------------------------------------------------
problem("window-median", """\
Given N integers and a window size K, print the median of every contiguous window of
size K, left to right. For an even K the median is the lower of the two middle values.

## Input
Line 1: N K (1 <= K <= N <= 200000). Line 2: N integers in [-10^9, 10^9].

## Output
One line: N-K+1 medians, space separated.
""", """\
import sys
def main():
    d = sys.stdin.read().split(); n, k = int(d[0]), int(d[1]); a = [int(x) for x in d[2:n+2]]
    v = sorted(set(a)); rank = {x: i+1 for i, x in enumerate(v)}; m = len(v)
    t = [0]*(m+1); top = 1
    while top*2 <= m: top *= 2
    need = (k+1)//2; out = []
    for i, x in enumerate(a):
        j = rank[x]
        while j <= m: t[j] += 1; j += j & -j
        if i >= k:
            j = rank[a[i-k]]
            while j <= m: t[j] -= 1; j += j & -j
        if i >= k-1:
            pos, left, b = 0, need, top
            while b:
                nx = pos+b
                if nx <= m and t[nx] < left: pos = nx; left -= t[nx]
                b >>= 1
            out.append(v[pos])
    print(" ".join(map(str, out)))
main()
""", """\
n, k = map(int, input().split()); a = list(map(int, input().split()))
print(" ".join(str(sorted(a[i:i+k])[(k-1)//2]) for i in range(n-k+1)))
""", ["5 3\n1 3 2 6 4\n", "4 2\n5 1 2 8\n", "1 1\n-7\n"],
    lambda r: (lambda n: f"{n} {r.randint(1, n)}\n{' '.join(str(r.randint(-5, 5)) for _ in range(n))}\n")(r.randint(1, 30)),
    lambda r: f"200000 100000\n{' '.join(str(r.randint(-10**9, 10**9)) for _ in range(200000))}\n")

# 4 ---------------------------------------------------------------------------
problem("reverse-pairs", """\
Count the pairs (i, j) with i < j and a[i] > 2*a[j].

## Input
Line 1: N (1 <= N <= 200000). Line 2: N integers in [-10^9, 10^9].

## Output
The number of pairs.
""", """\
import sys
from bisect import bisect_right
def main():
    d = sys.stdin.read().split(); n = int(d[0]); a = [int(x) for x in d[1:n+1]]
    vals = sorted(set(a)); m = len(vals); t = [0]*(m+1)
    res = 0; seen = 0
    for x in a:
        # earlier values v with v > 2*x
        i = bisect_right(vals, 2*x)
        c = 0; j = i
        while j > 0:
            c += t[j]; j -= j & -j
        res += seen - c
        j = bisect_right(vals, x)
        while j <= m:
            t[j] += 1; j += j & -j
        seen += 1
    print(res)
main()
""", """\
n = int(input()); a = list(map(int, input().split()))
print(sum(1 for i in range(n) for j in range(i+1, n) if a[i] > 2*a[j]))
""", ["5\n1 3 2 3 1\n", "4\n2 4 3 5\n", "2\n1 1\n"],
    lambda r: (lambda n: f"{n}\n{' '.join(str(r.randint(-6, 6)) for _ in range(n))}\n")(r.randint(1, 40)),
    lambda r: f"200000\n{' '.join(str(r.randint(-10**9, 10**9)) for _ in range(200000))}\n")

# 5 ---------------------------------------------------------------------------
problem("one-free-edge", """\
A directed graph has N nodes and M weighted edges. Travel from node 1 to node N. You may
make at most one edge cost 0, once. Print the least total cost, or -1 if N is unreachable.

## Input
Line 1: N M (2 <= N <= 100000, 0 <= M <= 200000). Next M lines: u v w (1 <= w <= 10^9).

## Output
The least cost, or -1.
""", """\
import sys, heapq
def main():
    d = sys.stdin.read().split(); n, m = int(d[0]), int(d[1])
    g = [[] for _ in range(n+1)]; p = 2
    for _ in range(m):
        u, v, w = int(d[p]), int(d[p+1]), int(d[p+2]); p += 3; g[u].append((v, w))
    inf = float('inf'); dist = [[inf, inf] for _ in range(n+1)]; dist[1][0] = 0; h = [(0, 1, 0)]
    while h:
        c, x, f = heapq.heappop(h)
        if c > dist[x][f]: continue
        for y, w in g[x]:
            if c+w < dist[y][f]: dist[y][f] = c+w; heapq.heappush(h, (c+w, y, f))
            if f == 0 and c < dist[y][1]: dist[y][1] = c; heapq.heappush(h, (c, y, 1))
    b = min(dist[n]); print(-1 if b == inf else b)
main()
""", """\
import sys
d = sys.stdin.read().split(); n, m = int(d[0]), int(d[1]); e = []; p = 2
for _ in range(m):
    e.append((int(d[p]), int(d[p+1]), int(d[p+2]))); p += 3
inf = float('inf')
def sp(costs):
    dist = [inf]*(n+1); dist[1] = 0
    for _ in range(n):
        for (u, v, _w), c in zip(e, costs):
            if dist[u]+c < dist[v]: dist[v] = dist[u]+c
    return dist[n]
base = [x[2] for x in e]; best = sp(base)
for i in range(m):
    c = list(base); c[i] = 0; best = min(best, sp(c))
print(-1 if best == inf else best)
""", ["3 2\n1 2 5\n2 3 7\n", "2 0\n", "3 3\n1 3 100\n1 2 1\n2 3 1\n"],
    lambda r: (lambda n, m: f"{n} {m}\n" + "".join(f"{r.randint(1, n)} {r.randint(1, n)} {r.randint(1, 20)}\n" for _ in range(m)))(r.randint(2, 7), r.randint(0, 14)),
    lambda r: "100000 200000\n" + "".join(f"{r.randint(1, 100000)} {r.randint(1, 100000)} {r.randint(1, 10**9)}\n" for _ in range(100000)) + "".join(f"{i} {i+1} {10**9}\n" for i in range(1, 100000)) + "1 1 1\n")

# 6 ---------------------------------------------------------------------------
problem("subset-count", """\
Count the subsets (including the empty one) of N integers whose sum is at most T.

## Input
Line 1: N T (1 <= N <= 36, |T| <= 10^15). Line 2: N integers in [-10^13, 10^13].

## Output
The number of subsets.
""", """\
from bisect import bisect_right
n, t = map(int, input().split()); a = list(map(int, input().split()))
def sums(x):
    s = [0]
    for v in x: s = s + [y+v for y in s]
    return s
l = sums(a[:n//2]); r = sorted(sums(a[n//2:]))
print(sum(bisect_right(r, t-x) for x in l))
""", """\
n, t = map(int, input().split()); a = list(map(int, input().split()))
print(sum(1 for m in range(1 << n) if sum(a[i] for i in range(n) if m >> i & 1) <= t))
""", ["3 2\n1 1 1\n", "2 -1\n-1 -1\n", "4 0\n5 5 5 5\n"],
    lambda r: (lambda n: f"{n} {r.randint(-20, 20)}\n{' '.join(str(r.randint(-9, 9)) for _ in range(n))}\n")(r.randint(1, 12)),
    lambda r: f"36 {r.randint(-10**13, 10**13)}\n{' '.join(str(r.randint(-10**13, 10**13)) for _ in range(36))}\n")

# 7 ---------------------------------------------------------------------------
problem("distinct-subseq", """\
Count the distinct non-empty subsequences of a string, modulo 1000000007.

## Input
One line: a string S of lowercase letters (1 <= |S| <= 500000).

## Output
The count modulo 1000000007.
""", """\
s = input().strip(); M = 10**9+7; last = {}; t = 1
for ch in s:
    n = (2*t - last.get(ch, 0)) % M
    last[ch] = t; t = n
print((t-1) % M)
""", """\
s = input().strip(); o = set()
for m in range(1, 1 << len(s)):
    o.add("".join(s[i] for i in range(len(s)) if m >> i & 1))
print(len(o) % (10**9+7))
""", ["abc\n", "aaa\n", "aba\n"],
    lambda r: "".join(r.choice("abc") for _ in range(r.randint(1, 13))) + "\n",
    lambda r: "".join(r.choice("abcdefghijklmnopqrstuvwxyz") for _ in range(500000)) + "\n")

# 8 ---------------------------------------------------------------------------
problem("lis-count", """\
Print the length of the longest strictly increasing subsequence of N integers, and how
many subsequences (by index set) attain it, modulo 1000000007.

## Input
Line 1: N (1 <= N <= 200000). Line 2: N integers in [-10^9, 10^9].

## Output
Two integers: the length and the count.
""", """\
import sys
from bisect import bisect_left
def main():
    d = sys.stdin.read().split(); n = int(d[0]); a = [int(x) for x in d[1:n+1]]
    M = 10**9+7; v = sorted(set(a)); m = len(v)
    tl = [0]*(m+1); tc = [0]*(m+1)
    bl, bc = 0, 0
    for x in a:
        i = bisect_left(v, x)
        l, c = 0, 1
        j = i
        while j > 0:
            if tl[j] > l: l, c = tl[j], tc[j]
            elif tl[j] == l and l > 0: c = (c+tc[j]) % M
            j -= j & -j
        l += 1
        if l > bl: bl, bc = l, c
        elif l == bl: bc = (bc+c) % M
        j = i+1
        while j <= m:
            if l > tl[j]: tl[j], tc[j] = l, c
            elif l == tl[j]: tc[j] = (tc[j]+c) % M
            j += j & -j
    print(bl, bc)
main()
""", """\
n = int(input()); a = list(map(int, input().split())); best = {}
for m in range(1, 1 << n):
    x = [a[i] for i in range(n) if m >> i & 1]
    if all(p < q for p, q in zip(x, x[1:])): best[len(x)] = best.get(len(x), 0)+1
k = max(best); print(k, best[k] % (10**9+7))
""", ["5\n1 3 5 4 7\n", "5\n2 2 2 2 2\n", "1\n9\n"],
    lambda r: (lambda n: f"{n}\n{' '.join(str(r.randint(-5, 5)) for _ in range(n))}\n")(r.randint(1, 14)),
    lambda r: f"200000\n{' '.join(str(r.randint(-10**9, 10**9)) for _ in range(200000))}\n")

# 9 ---------------------------------------------------------------------------
problem("gap-intervals", """\
N jobs each have a start, end and profit. Choose jobs maximizing total profit so that
each chosen job starts at least C after the previous chosen job ends.

## Input
Line 1: N C (1 <= N <= 200000, 0 <= C <= 10^9). Next N lines: s e p
(0 <= s <= e <= 10^9, 1 <= p <= 10^9).

## Output
The maximum total profit.
""", """\
import sys
from bisect import bisect_right
def main():
    d = sys.stdin.read().split(); n, c = int(d[0]), int(d[1])
    j = sorted((int(d[2+3*i+1]), int(d[2+3*i]), int(d[2+3*i+2])) for i in range(n))
    ends = [x[0] for x in j]; dp = [0]*(n+1)
    for i, (e, s, p) in enumerate(j):
        k = bisect_right(ends, s-c, 0, i)
        dp[i+1] = max(dp[i], dp[k]+p)
    print(dp[n])
main()
""", """\
import sys
d = sys.stdin.read().split(); n, c = int(d[0]), int(d[1])
j = [(int(d[2+3*i]), int(d[2+3*i+1]), int(d[2+3*i+2])) for i in range(n)]
best = 0
for m in range(1 << n):
    x = sorted(j[i] for i in range(n) if m >> i & 1)
    if all(b[0] >= a[1]+c for a, b in zip(x, x[1:])): best = max(best, sum(y[2] for y in x))
print(best)
""", ["3 0\n1 2 5\n2 3 6\n3 4 5\n", "2 5\n0 1 3\n2 3 4\n", "1 0\n0 0 9\n"],
    lambda r: (lambda n: f"{n} {r.randint(0, 3)}\n" + "".join((lambda s: f"{s} {s+r.randint(0, 5)} {r.randint(1, 9)}\n")(r.randint(0, 12)) for _ in range(n)))(r.randint(1, 10)),
    lambda r: f"200000 {r.randint(0, 1000)}\n" + "".join((lambda s: f"{s} {min(10**9, s+r.randint(0, 10**5))} {r.randint(1, 10**9)}\n")(r.randint(0, 10**9)) for _ in range(200000)))

# 10 --------------------------------------------------------------------------
problem("min-rotation", """\
Print the lexicographically smallest rotation of a string.

## Input
One line: a lowercase string S (1 <= |S| <= 1000000).

## Output
The smallest rotation.
""", """\
s = input().strip(); n = len(s); i, j, k = 0, 1, 0
while i < n and j < n and k < n:
    a, b = s[(i+k) % n], s[(j+k) % n]
    if a == b: k += 1; continue
    if a > b: i += k+1
    else: j += k+1
    if i == j: j += 1
    k = 0
m = min(i, j); print(s[m:]+s[:m])
""", """\
s = input().strip(); print(min(s[i:]+s[:i] for i in range(len(s))))
""", ["bca\n", "aaa\n", "cabab\n"],
    lambda r: "".join(r.choice("ab") for _ in range(r.randint(1, 40))) + "\n",
    # All one letter but the last: the naive comparison goes quadratic here.
    lambda r: "a"*999999 + "b\n")

# 11 --------------------------------------------------------------------------
problem("max-xor-subarray", """\
Print the maximum XOR of any non-empty contiguous subarray.

## Input
Line 1: N (1 <= N <= 200000). Line 2: N integers in [0, 2^31).

## Output
The maximum XOR.
""", """\
import sys
def main():
    d = sys.stdin.read().split(); n = int(d[0]); a = [int(x) for x in d[1:n+1]]
    pre = [0]
    for x in a: pre.append(pre[-1] ^ x)
    # Prefixes may not pair with themselves: a subarray is non-empty, and the check
    # runs before the prefix is added.
    best = 0
    for b in range(30, -1, -1):
        want = (best >> b | 1) << b
        seen = set(); ok = False
        for p in pre:
            q = p >> b
            if (q ^ (want >> b)) in seen: ok = True; break
            seen.add(q)
        if ok: best = want
    print(best)
main()
""", """\
n = int(input()); a = list(map(int, input().split())); best = -1
for i in range(n):
    x = 0
    for j in range(i, n):
        x ^= a[j]; best = max(best, x)
print(best)
""", ["4\n1 2 3 4\n", "3\n8 1 2\n", "2\n5 5\n"],
    lambda r: (lambda n: f"{n}\n{' '.join(str(r.randint(0, 31)) for _ in range(n))}\n")(r.randint(1, 30)),
    lambda r: f"200000\n{' '.join(str(r.randint(0, 2**31-1)) for _ in range(200000))}\n")

# 12 --------------------------------------------------------------------------
problem("largest-rectangle", """\
A grid of 0s and 1s is given. Print the area of the largest all-1 axis-aligned rectangle.

## Input
Line 1: R C (1 <= R, C <= 1500). Next R lines: C characters, each 0 or 1.

## Output
The largest area.
""", """\
import sys
def main():
    d = sys.stdin.read().split(); r, c = int(d[0]), int(d[1]); h = [0]*(c+1); best = 0
    for row in d[2:2+r]:
        st = []
        for j in range(c):
            h[j] = h[j]+1 if row[j] == '1' else 0
        for j in range(c+1):
            x = h[j]
            while st and h[st[-1]] >= x:
                t = st.pop(); w = j if not st else j-st[-1]-1; a = h[t]*w
                if a > best: best = a
            st.append(j)
    print(best)
main()
""", """\
d = open(0).read().split(); r, c = int(d[0]), int(d[1]); g = d[2:]; best = 0
for a in range(r):
    for b in range(c):
        for e in range(a, r):
            for f in range(b, c):
                if all(g[i][j] == '1' for i in range(a, e+1) for j in range(b, f+1)):
                    best = max(best, (e-a+1)*(f-b+1))
print(best)
""", ["2 3\n110\n111\n", "1 1\n0\n", "3 2\n11\n11\n11\n"],
    lambda r: (lambda a, b: f"{a} {b}\n" + "".join("".join(r.choice("0111") for _ in range(b))+"\n" for _ in range(a)))(r.randint(1, 6), r.randint(1, 6)),
    lambda r: "1500 1500\n" + "".join("".join(r.choice("01111111") for _ in range(1500))+"\n" for _ in range(1500)))


def write(d, stem, stdin, stdout):
    os.makedirs(d, exist_ok=True)
    open(os.path.join(d, f"{stem}.in"), "w").write(stdin)
    open(os.path.join(d, f"{stem}.out"), "w").write(stdout)


def build(out):
    for name, statement, ref, brute, public, small, stress in PROBLEMS:
        r = random.Random(f"{SEED}-{name}")
        pdir = os.path.join(out, name)
        os.makedirs(pdir, exist_ok=True)
        open(os.path.join(pdir, "task.md"), "w").write(statement)
        for i, s in enumerate(public, 1):
            write(os.path.join(pdir, "tests"), str(i), s, run(ref, s)[0])
        # The reference and the brute force must agree before anything is trusted.
        cases = public + [small(r) for _ in range(120)]
        for s in cases:
            got, want = run(ref, s)[0], run(brute, s, 60)[0]
            if got.strip() != want.strip():
                raise RuntimeError(f"{name}: reference {got!r} != brute {want!r} on {s!r}")
        hidden = [small(r) for _ in range(12)]
        for i, s in enumerate(hidden, 1):
            write(os.path.join(pdir, "hidden"), str(i), s, run(ref, s)[0])
        s = stress(r)
        o, took = run(ref, s, 10)
        write(os.path.join(pdir, "hidden"), "stress", s, o)
        print(f"  {name}: 120 cross-checks, stress reference {took:.2f}s")


if __name__ == "__main__":
    build(sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "hard"))
