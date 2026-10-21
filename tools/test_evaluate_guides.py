#!/usr/bin/env python3
"""Unit tests for the offline evaluation contract."""
import unittest
from evaluate_guides import evaluate

class EvaluatorTests(unittest.TestCase):
    def test_no_aim_rejects_false_positive(self):
        truth = [{"video":"v","timestamp_seconds":"0","expected":"none"}]
        pred = [{"video":"v","timestamp_seconds":"0","detected":"true"}]
        self.assertEqual(evaluate(truth, pred, 12, 8)[1], 0)

    def test_correct_guide(self):
        truth = [{"video":"v","timestamp_seconds":"1","expected":"guide",
                  "origin_x":"100","origin_y":"100","angle_degrees":"0"}]
        pred = [{"video":"v","timestamp_seconds":"1","detected":"true",
                 "origin_x":"102","origin_y":"100","tip_x":"150","tip_y":"100"}]
        self.assertEqual(evaluate(truth, pred, 12, 8)[1], 1)

    def test_reversed_direction_rejected(self):
        truth = [{"video":"v","timestamp_seconds":"1","expected":"guide",
                  "origin_x":"100","origin_y":"100","angle_degrees":"0"}]
        pred = [{"video":"v","timestamp_seconds":"1","detected":"true",
                 "origin_x":"100","origin_y":"100","tip_x":"50","tip_y":"100"}]
        self.assertEqual(evaluate(truth, pred, 12, 8)[1], 0)

    def test_missing_frame_fails(self):
        truth = [{"video":"v","timestamp_seconds":"1","expected":"none"}]
        self.assertEqual(evaluate(truth, [], 12, 8)[1], 0)

if __name__ == "__main__":
    unittest.main()
