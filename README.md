# Warning

A lot of generated code for just exploring things, there will be bugs.

# Sync-Salabim

Extremely minimal one-way-sync app to sync Photos to a Webdav-Endpoint.

## Sync Strategy

- Has local Database that saves all successfully transferred media files
- Keeps data on phone after sync
- On next sync just syncs new files

# TODO

- Background Sync not really working as intended 
- Now that multiple folders are supported, implement handling when images with same filename are uploaded to same folder
  - for example: screenshots/a.jpg (shot on 1.2.2026) and photos/a.jpg (shot on 1.2.2026)
- ~Implement Kind of "refresh" when database gets corrupted or is missing~

# Why

Seems like I have a strange workflow to save media from my phone to nextcloud, and there was no (free) app that had this functionality.

I am an experienced iOS Developer, but have no experience in Android-Dev, a lot of code is generated.

# Why the name

Was the name an llm could spit out that was best regarding cringe/creativity ratio
