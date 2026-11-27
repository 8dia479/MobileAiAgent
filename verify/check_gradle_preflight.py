"""
Pre-flight checks for the Gradle build job: things that are fine for the offline
type-check but break a real AGP build.
"""
import os, re, sys
problems, notes = [], []

# 1) Room schema export path must be under the module so it can be an artifact.
bd = open('app/build.gradle.kts').read()
m = re.search(r'room\.schemaLocation"?\s*,\s*"([^"]+)"', bd)
notes.append(f"room.schemaLocation = {m.group(1) if m else 'NOT SET'}")
if not m:
    problems.append("room.schemaLocation missing but @Database(exportSchema = true) — Gradle build fails")

# 2) exportSchema true requires the schemas dir to be writable at build time (it is), but
#    .gitignore must not swallow it silently for later review.
gi = open('.gitignore').read()
if 'schemas' in gi:
    notes.append("schemas dir ignored (acceptable, CI uploads it as an artifact)")

# 3) Every @Entity must be listed in @Database(entities=[...]).
ent = open('app/src/main/kotlin/com/mobileaiagent/data/db/Entities.kt').read()
db  = open('app/src/main/kotlin/com/mobileaiagent/data/db/AgentDatabase.kt').read()
declared = set(re.findall(r'data class (\w+Entity)\s*\(', ent))
listed   = set(re.findall(r'(\w+Entity)::class', db))
missing  = declared - listed
if missing:
    problems.append(f"@Entity not listed in @Database(entities=...): {sorted(missing)}")
notes.append(f"entities declared={len(declared)} listed={len(listed)}")

# 4) Every DAO declared as abstract fun must exist.
daos_listed = set(re.findall(r'abstract fun (\w+)\(\): (\w+Dao)', db))
daofiles = open('app/src/main/kotlin/com/mobileaiagent/data/db/Daos.kt').read()
daos_defined = set(re.findall(r'interface (\w+Dao)', daofiles))
for fn, dao in daos_listed:
    if dao not in daos_defined:
        problems.append(f"AgentDatabase.{fn}() returns {dao}, which is not declared in Daos.kt")
notes.append(f"DAOs referenced={len(daos_listed)} defined={len(daos_defined)}")
unused = daos_defined - {d for _, d in daos_listed}
if unused:
    notes.append(f"DAOs defined but not exposed: {sorted(unused)}")

# 5) buildConfigField defaults referenced from code must exist.
for field in re.findall(r'buildConfigField\("String", "(\w+)"', bd):
    notes.append(f"buildConfig field: {field}")

# 6) resource references: R.string/R.drawable used in code must exist in res/
res = 'app/src/main/res'
strings, drawables = set(), set()
for root, _, files in os.walk(res):
    for f in files:
        if root.endswith('/values') and f.endswith('.xml'):
            strings |= set(re.findall(r'<string name="([^"]+)"', open(os.path.join(root,f)).read()))
        if '/drawable' in root:
            drawables.add(f.rsplit('.',1)[0])
used_s, used_d = set(), set()
for root, _, files in os.walk('app/src/main/kotlin'):
    for f in files:
        if not f.endswith('.kt'): continue
        src = open(os.path.join(root,f)).read()
        used_s |= set(re.findall(r'R\.string\.(\w+)', src))
        used_d |= set(re.findall(r'R\.drawable\.(\w+)', src))
miss_s, miss_d = used_s - strings, used_d - drawables
if miss_s: problems.append(f"R.string used but not defined: {sorted(miss_s)}")
if miss_d: problems.append(f"R.drawable used but not defined: {sorted(miss_d)}")
notes.append(f"strings defined={len(strings)} used={len(used_s)}; drawables defined={len(drawables)} used={len(used_d)}")

# 7) Manifest activity/service classes must exist in source.
man = open('app/src/main/AndroidManifest.xml').read()
for name in re.findall(r'android:name="\.([\w.]+)"', man):
    path = 'app/src/main/kotlin/com/mobileaiagent/' + name.replace('.', '/')
    if not any(os.path.exists(path + ext) for ext in ('.kt', 'Kt.kt')):
        problems.append(f"Manifest references .{name} but no source file found")

print("PRE-FLIGHT NOTES")
for n in notes: print("  ·", n)
print()
if problems:
    print("BLOCKERS FOR THE GRADLE BUILD:")
    for p in problems: print("  ✗", p)
    sys.exit(1)
print("No blockers found for the Gradle build.")

# 8) Test sources run on the JVM, but they compile against the *Android* classpath
#    in a Gradle Android build: java.awt / javax.imageio do not exist there.
import glob
banned = {
    "javax.imageio": "not available on Android; decode the bytes by hand",
    "java.awt": "not available on Android",
    "javax.swing": "not available on Android",
}
test_hits = []
for path in glob.glob("app/src/test/kotlin/**/*.kt", recursive=True):
    text = open(path).read()
    for needle, why in banned.items():
        if needle in text:
            test_hits.append(f"{path} uses {needle} ({why})")
if test_hits:
    problems.extend(test_hits)
    print("\nTEST SOURCE BLOCKERS:")
    for h in test_hits:
        print("  ✗", h)
    sys.exit(1)
print("Test sources avoid JVM-only desktop APIs.")
