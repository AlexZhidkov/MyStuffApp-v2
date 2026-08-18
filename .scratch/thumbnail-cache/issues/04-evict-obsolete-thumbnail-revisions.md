# 04 — Evict obsolete thumbnail revisions

**What to build:** When an Item photo is replaced or removed, every connected device that observes the Inventory change discards the obsolete local thumbnail while immediately treating the new revision as distinct content.

**Blocked by:** 02 — Cache viewed Item thumbnails.

**Status:** ready-for-agent

- [ ] Observing an Item photo replacement evicts the previous thumbnail revision from both memory and disk caches on that device.
- [ ] Observing an Item photo removal evicts the removed thumbnail from both memory and disk caches on that device.
- [ ] Eviction works for both legacy thumbnail locations and UUID-versioned locations.
- [ ] A replacement can never display the previous cached bytes under the new revision's cache key.
- [ ] Local eviction does not delete superseded cloud objects or interfere with the new revision's independent background uploads.
- [ ] Automated checks cover replacement and removal initiated locally and observed from another connected Household Member.
