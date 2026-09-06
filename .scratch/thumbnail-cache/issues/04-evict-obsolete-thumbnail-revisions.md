# 04 — Evict obsolete thumbnail revisions

**What to build:** When an Item photo is replaced or removed, every connected device that observes the Inventory change discards the obsolete local thumbnail while immediately treating the new revision as distinct content.

**Blocked by:** 02 — Cache viewed Item thumbnails.

**Status:** implemented

- [x] Observing an Item photo replacement evicts the previous thumbnail revision from both memory and disk caches on that device.
- [x] Observing an Item photo removal evicts the removed thumbnail from both memory and disk caches on that device.
- [x] Eviction works for both legacy thumbnail locations and UUID-versioned locations.
- [x] A replacement can never display the previous cached bytes under the new revision's cache key.
- [x] Local eviction does not delete superseded cloud objects or interfere with the new revision's independent background uploads.
- [x] Automated checks cover replacement and removal initiated locally and observed from another connected Household Member.

## Comments

- Inventory state changes now pass the complete set of current Item Photo thumbnail locations to the Item Photo loading module. The module evicts disappeared revisions from memory and disk regardless of whether the controller change originated locally or from the shared Inventory observer.
- Per-location generations prevent a late obsolete request from recommitting bytes after eviction without disrupting independent thumbnail requests.
- Interface tests cover UUID-versioned replacement and legacy-location removal.
