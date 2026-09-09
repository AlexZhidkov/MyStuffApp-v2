# Simplify adding Photos to an existing Item

Members add Photos to an existing Item directly from its overflow menu. The menu exposes **Take photo** and **Choose photos**; Edit contains no Photo preview or Photo actions.

Camera capture accepts one Photo at a time. Gallery selection accepts several Photos and presents them sequentially for optional cropping, with progress shown as **Photo N of N**. Camera review offers **Use photo**, **Use original**, **Retake**, and **Cancel**. Gallery review offers **Use photo**, **Use original**, and **Skip**. Back or close discards the current and remaining gallery selections, retains already accepted Photos, and returns to the same Item.

Each accepted Photo is appended through an attachment-only operation and uploads in the background without rewriting Item metadata. An existing Item Photo remains designated; if the Item has none, the first added Photo becomes its Item Photo. Designation and deletion remain in the carousel, and there is no separate replacement flow.

Camera cancellation returns silently to the Item. Permission denial, camera unavailability, and technical failures report a brief error. Existing upload-failure and retry behaviour remains, with no additional upload-progress interface. Rare concurrent-removal cases receive no special handling.

Add Item remains camera-first. Its crop controls are source-aware: cancelling camera review continues to the form without a Photo, while leaving a gallery review preserves the Item draft and accepted Photos and discards the current and remaining selections.
