from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

from .java_lexer import Token, TokenKind, tokenize_java


@dataclass(frozen=True)
class GenericComment:
    start: int
    end: int
    text: str
    style: str


SHELL_SPECIAL_PARAMETERS = set("#?@*$!-0123456789")
SHELL_NAME_CHARACTER = re.compile(r"[A-Za-z0-9_]")
YAML_COMMENT_START = re.compile(r"(?:^|(?<=\s))#")


def _mask_quoted(source: str, index: int, quote: str) -> int:
    length = len(source)
    cursor = index + 1
    while cursor < length:
        character = source[cursor]
        if character == "\\":
            cursor += 2
            continue
        if character == quote:
            return cursor + 1
        cursor += 1
    return length


def _skip_balanced(source: str, index: int, opener: str, closer: str) -> int:
    length = len(source)
    cursor = index + 1
    depth = 1
    while cursor < length:
        character = source[cursor]
        if character == "\\":
            cursor += 2
            continue
        if character == opener:
            depth += 1
        elif character == closer:
            depth -= 1
            if depth == 0:
                return cursor + 1
        cursor += 1
    return length


def _skip_shell_expansion(source: str, index: int) -> int:
    length = len(source)
    if index + 1 >= length:
        return index + 1
    following = source[index + 1]
    if following == "{":
        return _skip_balanced(source, index, "{", "}")
    if following == "(":
        return _skip_balanced(source, index, "(", ")")
    if following == "`":
        cursor = index + 2
        while cursor < length and source[cursor] != "`":
            cursor += 2
        return min(cursor + 1, length)
    if following in SHELL_SPECIAL_PARAMETERS:
        return index + 2
    cursor = index + 1
    while cursor < length and SHELL_NAME_CHARACTER.match(source[cursor]):
        cursor += 1
    return cursor


def _heredoc_delimiters(line: str) -> list[str]:
    delimiters: list[str] = []
    for match in re.finditer(r"<<-?\s*(?:'([^']+)'|\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_]*))", line):
        delimiters.append(match.group(1) or match.group(2) or match.group(3))
    return delimiters


def _closing_quote_on_same_line(source: str, index: int, quote: str) -> int:
    cursor = index + 1
    length = len(source)
    while cursor < length:
        character = source[cursor]
        if character == "\\" and quote != "'":
            cursor += 2
            continue
        if character == "\n":
            return -1
        if character == quote:
            return cursor
        cursor += 1
    return -1


def scan_shell_comments(source: str) -> list[GenericComment]:
    comments: list[GenericComment] = []
    index = 0
    length = len(source)
    pending_heredocs: list[str] = []
    state = "code"

    while index < length:
        character = source[index]

        if character == "\n":
            index += 1
            if pending_heredocs:
                for delimiter in pending_heredocs:
                    line_end = source.find("\n", index)
                    if line_end == -1:
                        line_end = length
                    if source[index:line_end].strip() == delimiter:
                        index = line_end
                        break
                else:
                    continue
                pending_heredocs = []
            continue

        if state == "code":
            if character == "'":
                closing = _closing_quote_on_same_line(source, index, "'")
                if closing == -1:
                    index += 1
                    continue
                index = closing + 1
                continue
            if character == '"':
                closing = _closing_quote_on_same_line(source, index, '"')
                if closing == -1:
                    index += 1
                    continue
                index = closing + 1
                continue
            if character == "`":
                closing = source.find("`", index + 1)
                index = length if closing == -1 else closing + 1
                continue
            if character == "#":
                preceding = source[index - 1] if index > 0 else "\n"
                if preceding in ("\n", " ", "\t", ";"):
                    end = source.find("\n", index)
                    if end == -1:
                        end = length
                    comments.append(GenericComment(index, end, source[index:end], "HASH"))
                    index = end
                    continue
                index += 1
                continue
            if source.startswith("<<", index):
                line_end = source.find("\n", index)
                if line_end == -1:
                    line_end = length
                pending_heredocs.extend(_heredoc_delimiters(source[index:line_end]))
                index = line_end
                continue
            index += 1
            continue

        index += 1
    return comments


def scan_python_comments(source: str) -> list[GenericComment]:
    comments: list[GenericComment] = []
    index = 0
    length = len(source)
    while index < length:
        character = source[index]
        if character == "#":
            end = source.find("\n", index)
            if end == -1:
                end = length
            comments.append(GenericComment(index, end, source[index:end], "HASH"))
            index = end
            continue
        if source.startswith('"""', index) or source.startswith("'''", index):
            delimiter = source[index:index + 3]
            closing = source.find(delimiter, index + 3)
            index = length if closing == -1 else closing + 3
            continue
        if character == "'" or character == '"':
            index = _mask_quoted(source, index, character)
            continue
        index += 1
    return comments


def scan_yaml_comments(source: str) -> list[GenericComment]:
    comments: list[GenericComment] = []
    index = 0
    length = len(source)
    while index < length:
        character = source[index]
        if character == "'" or character == '"':
            index = _mask_quoted(source, index, character)
            continue
        if character == "#":
            preceding = source[index - 1] if index > 0 else "\n"
            if preceding in ("\n", " ", "\t"):
                end = source.find("\n", index)
                if end == -1:
                    end = length
                comments.append(GenericComment(index, end, source[index:end], "HASH"))
                index = end
                continue
            index += 1
            continue
        index += 1
    return comments


def _scan_hash_comments(source: str, single_quote: bool = True, double_quote: bool = True) -> list[GenericComment]:
    comments: list[GenericComment] = []
    index = 0
    length = len(source)
    while index < length:
        character = source[index]
        if single_quote and character == "'":
            index = _mask_quoted(source, index, "'")
            continue
        if double_quote and character == '"':
            index = _mask_quoted(source, index, '"')
            continue
        if character == "#":
            end = source.find("\n", index)
            if end == -1:
                end = length
            text = source[index:end]
            comments.append(GenericComment(index, end, text, "HASH"))
            index = end
            continue
        index += 1
    return comments


def _scan_dash_comments(source: str) -> list[GenericComment]:
    comments: list[GenericComment] = []
    index = 0
    length = len(source)
    while index < length:
        character = source[index]
        if character == "'":
            index = _mask_quoted(source, index, "'")
            continue
        if source.startswith("--", index):
            end = source.find("\n", index)
            if end == -1:
                end = length
            comments.append(GenericComment(index, end, source[index:end], "DASH"))
            index = end
            continue
        if source.startswith("/*", index):
            closing = source.find("*/", index + 2)
            end = length if closing == -1 else closing + 2
            comments.append(GenericComment(index, end, source[index:end], "BLOCK"))
            index = end
            continue
        index += 1
    return comments


def _scan_markup_comments(source: str) -> list[GenericComment]:
    comments: list[GenericComment] = []
    index = 0
    length = len(source)
    while index < length:
        character = source[index]
        if character == '"':
            index = _mask_quoted(source, index, '"')
            continue
        if source.startswith("<!--", index):
            closing = source.find("-->", index + 4)
            end = length if closing == -1 else closing + 3
            comments.append(GenericComment(index, end, source[index:end], "MARKUP"))
            index = end
            continue
        index += 1
    return comments


def _scan_script_comments(source: str) -> list[GenericComment]:
    comments: list[GenericComment] = []
    index = 0
    length = len(source)
    while index < length:
        character = source[index]
        if character in ("'", '"', "`"):
            index = _mask_quoted(source, index, character)
            continue
        if source.startswith("//", index):
            end = source.find("\n", index)
            if end == -1:
                end = length
            comments.append(GenericComment(index, end, source[index:end], "LINE"))
            index = end
            continue
        if source.startswith("/*", index):
            closing = source.find("*/", index + 2)
            end = length if closing == -1 else closing + 2
            comments.append(GenericComment(index, end, source[index:end], "BLOCK"))
            index = end
            continue
        index += 1
    return comments


HASH_SUFFIXES = {".yaml", ".yml", ".properties", ".conf", ".cfg", ".ini", ".toml"}
SHELL_SUFFIXES = {".sh", ".bash"}
PYTHON_SUFFIXES = {".py"}
DASH_SUFFIXES = {".sql"}
MARKUP_SUFFIXES = {".xml", ".html", ".htm", ".vue"}
SCRIPT_SUFFIXES = {".js", ".mjs", ".cjs", ".ts", ".tsx", ".jsx"}

DOCKERFILE_NAMES = ("dockerfile", "containerfile")


def _is_dockerfile(path: Path) -> bool:
    lowered = path.name.lower()
    return lowered in DOCKERFILE_NAMES or lowered.startswith("dockerfile.")


def scan_generic(path: Path, source: str) -> list[GenericComment]:
    suffix = path.suffix.lower()
    if _is_dockerfile(path):
        return _scan_hash_comments(source)
    if suffix in SHELL_SUFFIXES:
        return scan_shell_comments(source)
    if suffix in PYTHON_SUFFIXES:
        return scan_python_comments(source)
    if suffix in (".yaml", ".yml"):
        return scan_yaml_comments(source)
    if suffix in HASH_SUFFIXES:
        return _scan_hash_comments(source)
    if suffix in DASH_SUFFIXES:
        return _scan_dash_comments(source)
    if suffix in MARKUP_SUFFIXES:
        return _scan_markup_comments(source)
    if suffix in SCRIPT_SUFFIXES:
        return _scan_script_comments(source)
    return []


def scan_java(path: Path, source: str) -> list[GenericComment]:
    result = tokenize_java(source)
    comments: list[GenericComment] = []
    for token in result.tokens:
        if token.is_comment:
            style = "LINE" if token.kind is TokenKind.LINE_COMMENT else "BLOCK"
            comments.append(GenericComment(token.start, token.end, token.text, style))
    return comments


def strip_generic_comments(source: str, comments: list[GenericComment]) -> str:
    if not comments:
        return source
    ordered = sorted(comments, key=lambda item: item.start)
    pieces: list[str] = []
    cursor = 0
    for comment in ordered:
        pieces.append(source[cursor:comment.start])
        cursor = comment.end
    pieces.append(source[cursor:])
    return "".join(pieces)


def java_tokens(source: str) -> tuple[Token, ...]:
    return tokenize_java(source).tokens
