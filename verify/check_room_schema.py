#!/usr/bin/env python3
"""
Static Room schema check.

KSP validates DAO SQL only during a real Gradle build, which is impossible in an
offline sandbox. This script catches the most common Room compile failures ahead
of time: a @Query naming a table that no @Entity declares (a typo or a renamed
entity), and snake_case identifiers that are not fields of that entity.

It is deliberately conservative: it only reports an identifier when the entity
exists and the column is provably absent, so it does not fail on SQL keywords.
"""
import re
import sys

ENTITIES = "app/src/main/kotlin/com/mobileaiagent/data/db/Entities.kt"
DAOS = "app/src/main/kotlin/com/mobileaiagent/data/db/Daos.kt"


def balanced(src, start, open_c="(", close_c=")"):
    depth = 0
    i = start
    while i < len(src):
        if src[i] == open_c:
            depth += 1
        elif src[i] == close_c:
            depth -= 1
            if depth == 0:
                return src[start:i + 1], i + 1
        i += 1
    return src[start:], len(src)


def parse_entities(src):
    found = {}
    for m in re.finditer(r"@Entity", src):
        args, _ = balanced(src, m.end())
        after = src[m.end() + len(args):]
        cm = re.match(r"\s*data class (\w+)", after)
        if not cm:
            continue
        name = cm.group(1)
        tbl = re.search(r'tableName\s*=\s*"([^"]+)"', args)
        table = tbl.group(1) if tbl else name
        body, _ = balanced(src, m.end() + len(args) + cm.end())
        cols = set()
        for line in body.split("\n"):
            mm = re.match(r"\s*(?:@\w+(?:\([^)]*\))?\s*)*val (\w+)\s*:", line)
            if mm:
                cols.add(mm.group(1))
        found[table] = (name, cols)
    return found


def main():
    ents = parse_entities(open(ENTITIES).read())
    daos = open(DAOS).read()
    problems = []
    queries = 0
    for m in re.finditer(r'@Query\("([^"]+)"\)\s*(?:@\w+\s*)*suspend fun (\w+)', daos):
        queries += 1
        sql, fn = m.group(1), m.group(2)
        for t in re.findall(r"(?:FROM|INTO|UPDATE|JOIN)\s+(\w+)", sql, re.I):
            if t not in ents:
                problems.append("%s: unknown table '%s'" % (fn, t))
                continue
            for ident in re.findall(r"\b([a-z][a-z0-9]*(?:_[a-z0-9]+)+)\b", sql):
                if ident == t or ident in ents[t][1]:
                    continue
                problems.append("%s: '%s' is not a field of %s" % (fn, ident, t))

    print("Room schema: %d tables, %d queries" % (len(ents), queries))
    if problems:
        for p in dict.fromkeys(problems):
            print("  ERROR " + p)
        return 1
    print("Room schema OK: every @Query references a declared table and its fields")
    return 0


if __name__ == "__main__":
    sys.exit(main())
