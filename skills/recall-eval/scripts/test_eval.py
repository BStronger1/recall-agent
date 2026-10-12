"""Offline checks of scoring, pairing and failure accounting (no API calls)."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import run_eval as ev


def result(labels, **extra):
    return dict(status="ok", content=json.dumps({"labels": labels, "evidence": ["trace field"]}), **extra)


class EvaluationTests(unittest.TestCase):
    def test_label_order_does_not_matter(self):
        labels, error = ev.parse_output(result(["citation_error", "unsupported_answer"]))
        self.assertEqual(labels, {"unsupported_answer", "citation_error"})
        self.assertIsNone(error)

    def test_unknown_duplicate_and_conflicting_labels_fail(self):
        for labels in (["wrong"], ["citation_error", "citation_error"],
                       ["citation_error", "insufficient_evidence"]):
            self.assertIsNone(ev.parse_output(result(labels))[0])

    def test_invalid_json_and_schema_fail(self):
        for content in ("not json", '{"labels":[]}',
                        '{"labels":[],"evidence":[],"extra":true}',
                        '{"labels":["citation_error"],"evidence":[]}'):
            self.assertIsNone(ev.parse_output({"status": "ok", "content": content})[0])

    def test_transport_and_missing_fail(self):
        self.assertIsNone(ev.parse_output(None)[0])
        self.assertIsNone(ev.parse_output({"status": "transport_error"})[0])

    def test_clean_and_insufficient_are_distinct(self):
        self.assertEqual(ev.parse_output(result([]))[0], set())
        self.assertEqual(ev.parse_output(result(["insufficient_evidence"]))[0],
                         {"insufficient_evidence"})

    def prepared(self, path):
        with contextlib.redirect_stdout(io.StringIO()):
            return ev.prepare(path)

    def test_fixed_paired_schedule_and_no_gold_in_prompts(self):
        with tempfile.TemporaryDirectory() as td:
            manifest = self.prepared(Path(td) / "run")
            self.assertEqual(len(manifest["jobs"]), 96)
            self.assertEqual(len({j["id"] for j in manifest["jobs"]}), 96)
            for index in range(0, 96, 2):
                a, b = manifest["jobs"][index:index+2]
                self.assertEqual((a["case_id"], a["repeat"]), (b["case_id"], b["repeat"]))
                self.assertEqual({a["arm"], b["arm"]}, {"baseline", "skill"})
                self.assertEqual(a["messages"][1], b["messages"][1])
                for job in (a, b):
                    self.assertNotIn("expected_labels", json.dumps(job["messages"]))
                    self.assertTrue(job["messages"][0]["content"].startswith(ev.COMMON))
            second = self.prepared(Path(td) / "second")
            self.assertEqual(manifest["jobs"], second["jobs"])

    def test_freeze_refuses_overwrite_and_detects_modification(self):
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "run"
            self.prepared(out)
            with self.assertRaises(FileExistsError):
                self.prepared(out)
            (out / "manifest.json").write_text("{}", encoding="utf-8")
            with self.assertRaises(ValueError):
                ev.load_manifest(out)

    def test_interrupted_attempt_is_not_retried(self):
        manifest = {"jobs": [{"id": "a"}, {"id": "b"}]}
        self.assertEqual(ev.pending_jobs(manifest, [{"event": "started", "id": "a"}]),
                         [{"id": "b"}])

    def test_truncated_events_fail_closed(self):
        with tempfile.TemporaryDirectory() as td:
            (Path(td) / "events.jsonl").write_text('{"event":', encoding="utf-8")
            with self.assertRaises(ValueError):
                ev.load_events(td)

    def test_missing_results_stay_in_denominator(self):
        with tempfile.TemporaryDirectory() as td:
            manifest = self.prepared(Path(td) / "run")
            report = ev.build_report(manifest, [], "test")
            for arm in report["arms"].values():
                self.assertEqual(arm["scheduled"], 48)
                self.assertEqual(arm["correct"], 0)
                self.assertEqual(arm["micro_defect_recall_pct"], 0)
            self.assertEqual(report["paired"]["both_wrong"], 48)

    def test_all_correct_and_one_regression(self):
        with tempfile.TemporaryDirectory() as td:
            manifest = self.prepared(Path(td) / "run")
            events = [dict(event="finished", id=j["id"], **result(j["expected_labels"]))
                      for j in manifest["jobs"]]
            report = ev.build_report(manifest, events, "test")
            self.assertEqual(report["arms"]["baseline"]["correct"], 48)
            self.assertEqual(report["arms"]["skill"]["correct"], 48)
            target = next(e for e in events if e["id"] == "C09-r1-skill")
            target.update(result(["citation_error"]))
            report = ev.build_report(manifest, events, "test")
            self.assertEqual(report["arms"]["skill"]["correct"], 47)
            self.assertEqual(report["arms"]["skill"]["missed_defects"], 2)
            self.assertEqual(report["paired"]["regressed"], 1)

    def test_duplicate_results_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            manifest = self.prepared(Path(td) / "run")
            event = dict(event="finished", id=manifest["jobs"][0]["id"], **result([]))
            with self.assertRaises(ValueError):
                ev.build_report(manifest, [event, event], "test")

    def test_runner_stops_after_three_errors_without_extra_calls(self):
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "run"
            manifest = self.prepared(out)
            cfg = Path(td) / "credentials.json"
            ev.write_json(cfg, {"MODEL_BASE_URL": "https://www.dmxapi.cn/v1",
                                "MODEL_API_KEY": "FAKE_LOCAL_UNIT_TEST"})
            with mock.patch.object(ev, "request_model", side_effect=TimeoutError), \
                    contextlib.redirect_stdout(io.StringIO()):
                ev.run(out, cfg)
            events = ev.load_events(out)
            self.assertEqual(len([e for e in events if e["event"] == "started"]), 3)
            self.assertEqual(len(ev.pending_jobs(manifest, events)), 93)
            self.assertFalse((out / ".runner.lock").exists())


if __name__ == "__main__":
    unittest.main()