"""Run the same unittest discovery; expose failures as readable GitHub annotations."""
import os
from pathlib import Path
import sys
import unittest


if __name__=='__main__':
    root=Path(__file__).resolve().parents[1]
    suite=unittest.defaultTestLoader.discover(str(root/'scripts'),pattern='test_*.py')
    result=unittest.TextTestRunner(verbosity=2).run(suite)
    if os.environ.get('GITHUB_ACTIONS')=='true':
        for test,trace in result.failures+result.errors:
            message=(str(test)+'\n'+trace).replace('%','%25').replace('\r','%0D').replace('\n','%0A')
            print('::error title=Python regression failure::'+message,flush=True)
    sys.exit(0 if result.wasSuccessful() else 1)
