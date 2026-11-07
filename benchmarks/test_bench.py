import json, sqlite3, tempfile, unittest
from pathlib import Path

import bench


class MemoryReplay(unittest.TestCase):
    def test_verdict_says_no_more_than_the_pair_supports(self):
        self.assertIn("not measured", bench.memory_verdict(0.2, 0.9, 0))
        self.assertEqual("better with memory", bench.memory_verdict(0.5, 0.6, 3))
        self.assertEqual("worse with memory", bench.memory_verdict(0.6, 0.5, 3))
        self.assertEqual("no visible difference", bench.memory_verdict(0.5, 0.52, 3))

    def trial(self, shown):
        d = Path(tempfile.mkdtemp())
        (d / "agent").mkdir()
        c = sqlite3.connect(d / "agent/samizdat.sqlite3")
        c.executescript("""create table runs (id text, started_at text);
                           create table knowledge (id text, run_id text);
                           create table events (run_id text, kind text, data text);
                           insert into runs values ('old', '1'), ('new', '2');
                           insert into knowledge values ('k-old', 'old'), ('k-new', 'new');""")
        for kind, ids in shown:
            c.execute("insert into events values ('new', ?, ?)", (kind, json.dumps({"ids": ids})))
        c.commit()
        return d

    def test_only_an_earlier_runs_memory_counts_as_delivered(self):
        self.assertEqual(0, bench.delivered(self.trial([("exposed", ["k-new"])])))
        self.assertEqual(1, bench.delivered(self.trial([("exposed", ["k-new", "k-old"]), ("recall", ["k-old"])])))
        self.assertEqual(0, bench.delivered(self.trial([])))


if __name__ == "__main__":
    unittest.main()
