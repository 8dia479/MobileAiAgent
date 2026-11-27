#!/usr/bin/env python3
"""
Language-level check the offline type-check cannot do.

verify/typecheck-all.sh compiles with -language-version 2.2, where `break` and
`continue` are legal inside inline lambdas (e.g. `list.forEach { continue }`).
The Gradle build compiles this module with Kotlin 2.1, where both are hard errors:

    The feature "break continue in inline lambdas" is only available since
    language version 2.2

Strategy: for each break/continue, walk upward brace by brace. The block that
directly encloses it decides:
  * opened by `for (` / `while (`  -> legal
  * opened by anything else (a call, an `if`, a `when` arm) -> keep walking,
    unless it is a lambda, in which case remember it.
Report only when a lambda was found *before* any enclosing loop.
"""
import os
import re
import sys

KEYWORD = re.compile(r"\b(continue|break)\b")
LOOP_HEAD = re.compile(r"\b(for|while)\s*\(")
# Something that ends in a call/identifier right before `{` is a lambda opener,
# e.g. `items.forEach {`, `x?.let {`, `run {`, `->
LAMBDA_HEAD = re.compile(r"(?:[\w)\]}]\s*\.\s*\w+|^\s*\w+\s*\(.*\)|->|\breturn\b)\s*$")
# Control-flow blocks are not lambdas and do not stop the search.
CONTROL_HEAD = re.compile(
    r"""^\s*(
          (if|else|when|try|catch|finally|do|synchronized)\b   # keyword-headed block
        | \w+\s*\([^()]*\)\s*->                             # when (x) -> ...? (arm)
        | .*->                                                  # any when-arm:  "a" -> {
    )""",
    re.VERBOSE,
)
STRING_OR_COMMENT = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//.*$')


def strip_noise(line: str) -> str:
    return STRING_OR_COMMENT.sub('S', line)


def classify(lines, index):
    """Returns (loop_line or None, lambda_line or None) for the keyword at `index`."""
    depth = 0
    loop_at = None
    lambda_at = None
    for j in range(index, -1, -1):
        text = strip_noise(lines[j])
        for k in range(len(text) - 1, -1, -1):
            ch = text[k]
            if ch == "}":
                depth += 1
            elif ch == "{":
                if depth > 0:
                    depth -= 1
                    continue
                head = text[:k].rstrip()
                if LOOP_HEAD.search(head):
                    return (j + 1, lambda_at)
                if CONTROL_HEAD.match(head):
                    continue
                if LAMBDA_HEAD.search(head):
                    if lambda_at is None:
                        lambda_at = j + 1
    return (loop_at, lambda_at)


problems = []
inspected = 0
for root, _, files in os.walk("app/src/main/kotlin"):
    for name in files:
        if not name.endswith(".kt"):
            continue
        path = os.path.join(root, name)
        lines = open(path).read().split("\n")
        for i, line in enumerate(lines):
            m = KEYWORD.search(strip_noise(line))
            if not m:
                continue
            inspected += 1
            loop_line, lambda_line = classify(lines, i)
            if loop_line and lambda_line and lambda_line > loop_line:
                problems.append(
                    f"{path}:{i + 1}: '{m.group(1)}' inside a lambda opened at line "
                    f"{lambda_line}, nested in the loop at line {loop_line}"
                )

print(f"Kotlin language check: {inspected} break/continue statement(s) inspected")
if problems:
    for p in problems:
        print("  ERROR " + p)
    print("Compiles with -language-version 2.2 but not with 2.1 (the module targets 2.1).")
    sys.exit(1)
print("No 2.2-only break/continue inside inline lambdas")
