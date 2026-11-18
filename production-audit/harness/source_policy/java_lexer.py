from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class TokenKind(str, Enum):
    CODE = "CODE"
    LINE_COMMENT = "LINE_COMMENT"
    BLOCK_COMMENT = "BLOCK_COMMENT"
    STRING = "STRING"
    TEXT_BLOCK = "TEXT_BLOCK"
    CHAR = "CHAR"


@dataclass(frozen=True)
class Token:
    kind: TokenKind
    start: int
    end: int
    text: str

    @property
    def is_comment(self) -> bool:
        return self.kind in (TokenKind.LINE_COMMENT, TokenKind.BLOCK_COMMENT)


@dataclass(frozen=True)
class LexResult:
    tokens: tuple[Token, ...]
    unterminated: tuple[Token, ...]


_HEX_DIGITS = set("0123456789abcdefABCDEF")


def _unicode_escape_length(source: str, index: int) -> int:
    if index + 1 >= len(source) or source[index] != "\\" or source[index + 1] != "u":
        return 0
    cursor = index + 2
    while cursor < len(source) and source[cursor] == "u":
        cursor += 1
    if cursor + 4 > len(source):
        return 0
    for offset in range(cursor, cursor + 4):
        if source[offset] not in _HEX_DIGITS:
            return 0
    return cursor + 4 - index


def tokenize_java(source: str) -> LexResult:
    tokens: list[Token] = []
    unterminated: list[Token] = []
    length = len(source)
    index = 0
    code_start = 0

    def flush_code(end: int) -> None:
        if end > code_start:
            tokens.append(Token(TokenKind.CODE, code_start, end, source[code_start:end]))

    while index < length:
        character = source[index]

        if character == "/" and index + 1 < length and source[index + 1] == "/":
            flush_code(index)
            end = source.find("\n", index)
            if end == -1:
                end = length
            tokens.append(Token(TokenKind.LINE_COMMENT, index, end, source[index:end]))
            index = end
            code_start = index
            continue

        if character == "/" and index + 1 < length and source[index + 1] == "*":
            flush_code(index)
            closing = source.find("*/", index + 2)
            if closing == -1:
                token = Token(TokenKind.BLOCK_COMMENT, index, length, source[index:])
                tokens.append(token)
                unterminated.append(token)
                index = length
                code_start = index
                continue
            end = closing + 2
            tokens.append(Token(TokenKind.BLOCK_COMMENT, index, end, source[index:end]))
            index = end
            code_start = index
            continue

        if character == '"':
            flush_code(index)
            if source.startswith('"""', index):
                end = _scan_text_block(source, index)
                token = Token(TokenKind.TEXT_BLOCK, index, end, source[index:end])
                tokens.append(token)
                if end == length and not source.rstrip().endswith('"""'):
                    unterminated.append(token)
            else:
                end = _scan_quoted(source, index, '"')
                token = Token(TokenKind.STRING, index, end, source[index:end])
                tokens.append(token)
                if end == length and not source.rstrip().endswith('"'):
                    unterminated.append(token)
            index = end
            code_start = index
            continue

        if character == "'":
            flush_code(index)
            end = _scan_quoted(source, index, "'")
            token = Token(TokenKind.CHAR, index, end, source[index:end])
            tokens.append(token)
            if end == length and not source.rstrip().endswith("'"):
                unterminated.append(token)
            index = end
            code_start = index
            continue

        escape_length = _unicode_escape_length(source, index)
        if escape_length:
            index += escape_length
            continue

        index += 1

    flush_code(length)
    return LexResult(tuple(tokens), tuple(unterminated))


def _scan_quoted(source: str, start: int, quote: str) -> int:
    index = start + 1
    length = len(source)
    while index < length:
        character = source[index]
        if character == "\\":
            index += 2
            continue
        if character == "\n" and quote == "'":
            return index
        if character == quote:
            return index + 1
        index += 1
    return length


def _scan_text_block(source: str, start: int) -> int:
    index = start + 3
    length = len(source)
    while index < length:
        if source[index] == "\\":
            index += 2
            continue
        if source.startswith('"""', index):
            return index + 3
        index += 1
    return length


def line_of(source: str, offset: int) -> int:
    return source.count("\n", 0, offset) + 1


def comment_spans(source: str) -> list[tuple[Token, int, int]]:
    result = tokenize_java(source)
    spans: list[tuple[Token, int, int]] = []
    for token in result.tokens:
        if not token.is_comment:
            continue
        start_line = line_of(source, token.start)
        end_line = line_of(source, max(token.start, token.end - 1))
        spans.append((token, start_line, end_line))
    return spans


def strip_comments(source: str) -> str:
    result = tokenize_java(source)
    removals: list[tuple[int, int]] = [(token.start, token.end) for token in result.tokens if token.is_comment]
    if not removals:
        return source
    pieces: list[str] = []
    cursor = 0
    for start, end in removals:
        pieces.append(source[cursor:start])
        cursor = end
    pieces.append(source[cursor:])
    return "".join(pieces)
