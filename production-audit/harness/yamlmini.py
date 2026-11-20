from __future__ import annotations

import re
from typing import Any

TRUE_VALUES = {"true", "yes", "on"}
FALSE_VALUES = {"false", "no", "off"}
NULL_VALUES = {"", "null", "~"}
BLOCK_SCALAR_INDICATORS = {"|", "|-", "|+", ">", ">-", ">+"}
INT_PATTERN = re.compile(r"^[+-]?[0-9]+$")
FLOAT_PATTERN = re.compile(r"^[+-]?(?:[0-9]*\.[0-9]+|[0-9]+\.[0-9]*)(?:[eE][+-]?[0-9]+)?$")


class YamlError(Exception):
    pass


def strip_comment(text: str) -> str:
    result = []
    quote = ""
    index = 0
    while index < len(text):
        character = text[index]
        if quote:
            result.append(character)
            if character == "\\" and quote == '"' and index + 1 < len(text):
                result.append(text[index + 1])
                index += 2
                continue
            if character == quote:
                quote = ""
        elif character in "\"'":
            quote = character
            result.append(character)
        elif character == "#" and (not result or result[-1] in (" ", "\t")):
            break
        else:
            result.append(character)
        index += 1
    return "".join(result).rstrip()


def find_key_separator(text: str) -> int:
    quote = ""
    depth = 0
    index = 0
    while index < len(text):
        character = text[index]
        if quote:
            if character == "\\" and quote == '"' and index + 1 < len(text):
                index += 2
                continue
            if character == quote:
                quote = ""
        elif character in "\"'":
            quote = character
        elif character in "[{":
            depth += 1
        elif character in "]}":
            depth -= 1
        elif character == ":" and depth == 0:
            following = text[index + 1] if index + 1 < len(text) else " "
            if following in (" ", "\t") or index + 1 == len(text):
                return index
        index += 1
    return -1


def parse_key(raw: str) -> str:
    text = raw.strip()
    if len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        return text[1:-1].replace('\"', '"')
    if len(text) >= 2 and text[0] == "'" and text[-1] == "'":
        return text[1:-1].replace("''", "'")
    return text


def parse_scalar(raw: str) -> Any:
    text = raw.strip()
    if len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        return text[1:-1].replace('\"', '"').replace("\\\\", "\\")
    if len(text) >= 2 and text[0] == "'" and text[-1] == "'":
        return text[1:-1].replace("''", "'")
    lowered = text.lower()
    if lowered in NULL_VALUES:
        return None
    if lowered in TRUE_VALUES:
        return True
    if lowered in FALSE_VALUES:
        return False
    if INT_PATTERN.match(text):
        return int(text)
    if FLOAT_PATTERN.match(text):
        return float(text)
    if text.startswith("[") and text.endswith("]"):
        return parse_flow_sequence(text)
    if text.startswith("{") and text.endswith("}"):
        return parse_flow_mapping(text)
    return text


def split_flow(body: str) -> list[str]:
    parts = []
    depth = 0
    quote = ""
    current = []
    for character in body:
        if quote:
            current.append(character)
            if character == quote:
                quote = ""
            continue
        if character in "\"'":
            quote = character
            current.append(character)
            continue
        if character in "[{":
            depth += 1
        elif character in "]}":
            depth -= 1
        if character == "," and depth == 0:
            parts.append("".join(current))
            current = []
            continue
        current.append(character)
    remainder = "".join(current).strip()
    if remainder:
        parts.append(remainder)
    return [part.strip() for part in parts if part.strip()]


def parse_flow_sequence(text: str) -> list[Any]:
    return [parse_scalar(part) for part in split_flow(text[1:-1])]


def parse_flow_mapping(text: str) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for part in split_flow(text[1:-1]):
        separator = find_key_separator(part)
        if separator < 0:
            raise YamlError("Unsupported flow mapping entry: " + part)
        result[parse_key(part[:separator])] = parse_scalar(part[separator + 1:])
    return result


class Scanner:
    def __init__(self, raw_lines: list[str]) -> None:
        self.raw = list(raw_lines)
        self.index = 0

    def peek(self) -> tuple[int, str] | None:
        while self.index < len(self.raw):
            line = self.raw[self.index]
            stripped = line.strip()
            if stripped == "" or stripped.startswith("#") or stripped == "---":
                self.index += 1
                continue
            content = strip_comment(stripped)
            if content == "":
                self.index += 1
                continue
            return len(line) - len(line.lstrip(" ")), content
        return None

    def consume_block_scalar(self, parent_indent: int) -> str:
        collected: list[str] = []
        block_indent = None
        while self.index < len(self.raw):
            line = self.raw[self.index]
            if line.strip() == "":
                collected.append("")
                self.index += 1
                continue
            indent = len(line) - len(line.lstrip(" "))
            if indent <= parent_indent:
                break
            if block_indent is None:
                block_indent = indent
            collected.append(line[block_indent:] if len(line) >= block_indent else "")
            self.index += 1
        while collected and collected[-1] == "":
            collected.pop()
        return "\n".join(collected)

    def parse_block(self) -> Any:
        item = self.peek()
        if item is None:
            return None
        indent, content = item
        if content.startswith("- "):
            return self.parse_sequence(indent)
        return self.parse_mapping(indent)

    def parse_mapping(self, indent: int) -> dict[str, Any]:
        result: dict[str, Any] = {}
        while True:
            item = self.peek()
            if item is None:
                break
            current_indent, content = item
            if current_indent < indent:
                break
            if current_indent > indent:
                raise YamlError("Unexpected indentation at line " + str(self.index + 1))
            if content.startswith("- "):
                break
            separator = find_key_separator(content)
            if separator < 0:
                raise YamlError("Line " + str(self.index + 1) + " is not a mapping entry: " + content)
            key = parse_key(content[:separator])
            rest = content[separator + 1:].strip()
            self.index += 1
            if rest in BLOCK_SCALAR_INDICATORS:
                result[key] = self.consume_block_scalar(indent)
                continue
            if rest == "":
                following = self.peek()
                if following is not None and following[0] > indent:
                    result[key] = self.parse_block()
                elif following is not None and following[0] == indent and following[1].startswith("- "):
                    raise YamlError(
                        "line " + str(self.index + 1) + " starts a sequence at the same indentation as its key"
                    )
                else:
                    result[key] = None
                continue
            result[key] = parse_scalar(rest)
        return result

    def parse_sequence(self, indent: int) -> list[Any]:
        result: list[Any] = []
        while True:
            item = self.peek()
            if item is None:
                break
            current_indent, content = item
            if current_indent < indent:
                break
            if current_indent > indent:
                raise YamlError("Unexpected indentation at line " + str(self.index + 1))
            if not content.startswith("- "):
                break
            remainder = content[2:].strip()
            if remainder == "":
                self.index += 1
                following = self.peek()
                if following is not None and following[0] > current_indent:
                    result.append(self.parse_block())
                else:
                    result.append(None)
                continue
            if find_key_separator(remainder) >= 0:
                inner_indent = current_indent + 2
                self.raw[self.index] = " " * inner_indent + remainder
                result.append(self.parse_mapping(inner_indent))
                continue
            self.index += 1
            result.append(parse_scalar(remainder))
        return result


def loads(text: str, *additional: str) -> Any:
    source = "\n".join([text, *additional]) if additional else text
    scanner = Scanner(source.replace("\r\n", "\n").split("\n"))
    return scanner.parse_block()


def load_all(text: str) -> list[Any]:
    documents: list[Any] = []
    current: list[str] = []
    for raw in text.replace("\r\n", "\n").split("\n"):
        if raw.strip() == "---":
            if current:
                documents.append(loads("\n".join(current)))
                current = []
            continue
        current.append(raw)
    if current:
        parsed = loads("\n".join(current))
        if parsed is not None:
            documents.append(parsed)
    return documents


def load_file(path) -> Any:
    with open(path, "r", encoding="utf-8") as handle:
        return loads(handle.read())
