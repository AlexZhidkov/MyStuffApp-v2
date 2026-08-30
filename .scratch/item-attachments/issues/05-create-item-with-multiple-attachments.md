# 05: Create an Item with multiple attachments

**What to build:** Let a Member capture or choose several independent image attachments while creating one Item and then browse them from the saved Item.

**Blocked by:** 04: Open Item Attachments in a full-screen carousel.

**Status:** implemented

- [x] Item creation supports repeated camera capture through **Add another** and multi-selection through the Android photo picker.
- [x] Each selected image can be used without cropping or optionally cropped to an arbitrary aspect ratio rather than a forced square.
- [x] Each accepted image becomes one independent immutable Item Attachment with no required name, caption, purpose, or page grouping.
- [x] Display images are resized to approximately 2,048 pixels on the longest edge, encoded near WebP quality 80, and kept within the 2 MB upload limit; original camera or picker files are not retained as attachments.
- [x] The first attachment automatically becomes the Item Photo even when it depicts a receipt or instructions.
- [x] Item creation imposes no product-defined attachment count limit, and successful uploads can be viewed in the carousel in creation order.
- [x] Camera denial or unavailability still permits Item creation and photo-picker use without requiring an attachment.

## Comments

- Added repeated camera capture, Android multi-select photo picker flow, optional non-cropped processing, and free-ratio creation cropping.
- Added list-based Item creation that creates one immutable ordered attachment record per accepted image, projects the first as the Item Photo, and uploads the first thumbnail plus each display image independently.
- Added attachment-specific 2,048-pixel/WebP processing with a 2 MB cap while preserving the completed singular Item Photo path for Edit.
