# 04: Open Item Attachments in a full-screen carousel

**What to build:** Let a Member tap the Item Photo to browse all of that Item's attachments in one full-screen carousel.

**Blocked by:** 02: Back the existing Item Photo with an Item Attachment.

**Status:** implemented

- [x] Tapping the Item Photo opens a full-screen left-and-right carousel starting with the Item Photo, followed by all other attachments oldest-first.
- [x] The Item Photo shows a `+N` badge only when other attachment records exist, and the number excludes the Item Photo while including shared pending records.
- [x] Opening the carousel fetches the complete, unpaginated attachment collection and begins downloading every display image without a metered-network warning.
- [x] A Member can begin viewing the Item Photo immediately while other slides present clear loading or load-failure states.
- [x] Non-Item-Photo attachments have no generated or stored thumbnails.
- [x] Downloaded display images use Android cache storage with no application-defined size limit or trimming policy; tests do not assume the operating system retains cached files.

## Comments

- Added attachment collection observation to the selected Item detail flow, with Item Photo badge counting that excludes the projected photo attachment and retains shared records.
- Added a full-screen Compose `HorizontalPager` with immediate projected Item Photo display, attachment loading/failure states, eager display-file downloads, and back navigation.
- Added an Android cache-directory display-image cache without an application size limit or trimming policy, plus projection, controller, and cache tests.
