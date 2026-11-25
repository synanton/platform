import unittest
import build_annotations as builder


class Utf16GeneratorTest(unittest.TestCase):
    def test_non_bmp_offsets_are_utf16_code_units(self):
        structure = builder.parse_markdown("probe", "# Probe\n\nA😀B\n")
        paragraph = next(e for e in structure["elements"] if e["text"] == "A😀B")
        # "Probe" (5) + newline (1) = 6; "A😀B" is 4 UTF-16 code units.
        self.assertEqual(paragraph["documentStartOffset"], 6)
        self.assertEqual(paragraph["documentEndOffset"], 10)
        self.assertEqual(paragraph["chunkStartOffset"], 6)
        self.assertEqual(paragraph["chunkEndOffset"], 10)
        self.assertEqual(builder.utf16_len("A😀B"), 4)
        self.assertEqual(structure["chunks"][0]["documentStartOffset"], 0)
        self.assertEqual(structure["chunks"][0]["documentEndOffset"], 10)


if __name__ == "__main__":
    unittest.main()
