"""Compatibility import for saved research runners; use app_runtime and store.py."""
import sys
import app_runtime as _runtime

if __name__ == '__main__':
    from store import main
    main()
else:
    sys.modules[__name__] = _runtime
