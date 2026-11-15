"""Compatibility entrypoint; new setup uses prepare_runtime.py."""
import sys
import prepare_runtime as _prepare

if __name__ == '__main__':
    _prepare.main()
else:
    sys.modules[__name__] = _prepare
