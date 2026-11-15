# Historical material

The current startup and product architecture are documented in the root
[README](../../README.md). This directory preserves installation provenance and
earlier research context; its prompts are not active installation steps.

- [Earlier project narrative](README-before-restructure.md)
- [Original CI upgrade notes](original_ci/)
- [Unified installer review patch](unified/unified-installer-fixes.patch) and
  [original bundle inventory](unified/unified-release-manifest.json)
- [Core streaming bundle inventory](core/core-upgrade-bundle-manifest.json)
- [Unified acceptance](../UNIFIED_ACCEPTANCE_20261115.md)
- [Core streaming acceptance](../CORE_UPGRADE_ACCEPTANCE.md)

Raw evidence stays outside the checkout with SHA manifests. Saved entrypoint
names have thin compatibility adapters. Historical database schemas and
migrations remain in the application. No Git history or dataset is removed.
