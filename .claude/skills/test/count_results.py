"""Count JUnit XML results of the MedLink test modules: python3 -I count_results.py rileylink medtronic implementation"""
import glob
import sys
import xml.etree.ElementTree as ET

DIRS = {
    "rileylink": "pump/rileylink/build/test-results/testFullDebugUnitTest",
    "medtronic": "pump/medtronic/build/test-results/testFullDebugUnitTest",
    "implementation": "implementation/build/test-results/testFullDebugUnitTest",
}

modules = sys.argv[1:] or ["rileylink", "medtronic"]
bad = 0
for module in modules:
    path = DIRS.get(module)
    if path is None:
        print(f"{module}: unknown module (known: {', '.join(DIRS)})")
        bad += 1
        continue
    files = glob.glob(f"{path}/*.xml")
    if not files:
        print(f"{module}: no results in {path} (tests did not run)")
        bad += 1
        continue
    tests = failures = 0
    failed = []
    for f in files:
        suite = ET.parse(f).getroot()
        tests += int(suite.get("tests", 0))
        failures += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
        for case in suite.iter("testcase"):
            for problem in list(case.findall("failure")) + list(case.findall("error")):
                message = (problem.get("message") or problem.text or "").strip().splitlines()
                failed.append(f"  {case.get('classname')}.{case.get('name')}: {message[0] if message else ''}"[:300])
    print(f"{module} {tests - failures}/{tests}" + (f" ({failures} failing)" if failures else ""))
    if failed:
        print("\n".join(failed))
    bad += failures
sys.exit(1 if bad else 0)
