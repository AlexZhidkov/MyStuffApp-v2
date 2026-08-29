# Store Supporting Files as Item-Owned Attachments

Receipts, instructions, and additional photos are supporting files owned by a non-root Item, not Child Items in the Household Inventory tree. Each file is represented by an immutable Item Attachment in a Firestore subcollection beneath its Item; image attachments use nested Storage paths, and one image attachment is designated and projected onto the Item document as its Item Photo for efficient thumbnails and Description Generation.

Using Child Items was rejected because it would give supporting files misleading Item Paths, Search presence, and tree behavior. An attachment array embedded in the Item document was rejected because Items have no product-defined attachment limit; existing Item Photos will instead receive attachment records through a one-time metadata backfill, without copying their stored image data or supporting concurrent legacy clients.
