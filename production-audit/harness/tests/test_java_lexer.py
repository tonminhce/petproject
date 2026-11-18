from __future__ import annotations

import unittest

from harness.source_policy.java_lexer import comment_spans, line_of, strip_comments, tokenize_java
from harness.source_policy.java_lexer import TokenKind


class JavaLexerTest(unittest.TestCase):
    def test_line_comment_is_detected(self) -> None:
        source = "int value = 1; // trailing note\n"
        comments = comment_spans(source)
        self.assertEqual(len(comments), 1)
        self.assertEqual(comments[0][1], 1)

    def test_block_comment_is_detected(self) -> None:
        source = "/* header\n * body\n */\nclass Sample {}\n"
        comments = comment_spans(source)
        self.assertEqual(len(comments), 1)
        self.assertEqual(comments[0][1], 1)
        self.assertEqual(comments[0][2], 3)

    def test_double_slash_inside_string_is_not_a_comment(self) -> None:
        source = 'String url = "https://example.com/path";\n'
        self.assertEqual(comment_spans(source), [])
        self.assertEqual(strip_comments(source), source)

    def test_block_comment_marker_inside_string_is_not_a_comment(self) -> None:
        source = 'String value = "/* not a comment */";\n'
        self.assertEqual(comment_spans(source), [])

    def test_escaped_quote_inside_string_does_not_end_the_string(self) -> None:
        source = 'String value = "a\\" // still string";\n'
        self.assertEqual(comment_spans(source), [])

    def test_text_block_content_is_preserved(self) -> None:
        source = 'String block = """\n// inside text block\n""";\n'
        self.assertEqual(comment_spans(source), [])
        self.assertEqual(strip_comments(source), source)

    def test_char_literal_with_slash_is_preserved(self) -> None:
        source = "char separator = '/';\n"
        self.assertEqual(comment_spans(source), [])

    def test_escaped_backslash_in_char_literal(self) -> None:
        source = "char separator = '\\\\';\n"
        self.assertEqual(comment_spans(source), [])

    def test_unicode_escape_quote_does_not_open_a_string(self) -> None:
        source = "String value = \\u0022not a string start\\u0022;\n"
        self.assertEqual(comment_spans(source), [])

    def test_regex_like_literal_with_double_slash(self) -> None:
        source = 'Pattern pattern = Pattern.compile("https?://"); // note\n'
        comments = comment_spans(source)
        self.assertEqual(len(comments), 1)

    def test_strip_removes_only_comment_content(self) -> None:
        source = "int a = 1; // note\nint b = 2;\n"
        stripped = strip_comments(source)
        self.assertEqual(stripped, "int a = 1; \nint b = 2;\n")

    def test_line_numbers_are_one_based(self) -> None:
        source = "a\nb\n// note\n"
        self.assertEqual(line_of(source, 0), 1)
        self.assertEqual(line_of(source, 4), 3)

    def test_tokens_classify_string_and_char(self) -> None:
        source = 'String s = "x"; char c = \'y\'; // note\n'
        kinds = [token.kind for token in tokenize_java(source).tokens]
        self.assertIn(TokenKind.STRING, kinds)
        self.assertIn(TokenKind.CHAR, kinds)
        self.assertIn(TokenKind.LINE_COMMENT, kinds)

    def test_unterminated_string_is_reported(self) -> None:
        result = tokenize_java('String s = "unterminated\n')
        self.assertTrue(result.unterminated)

    def test_unterminated_block_comment_is_reported(self) -> None:
        result = tokenize_java("/* never closed\n")
        self.assertTrue(result.unterminated)

    def test_sql_inside_java_string_is_not_touched(self) -> None:
        source = 'String sql = "SELECT * FROM t -- not a java comment";\n'
        self.assertEqual(comment_spans(source), [])


if __name__ == "__main__":
    unittest.main()
