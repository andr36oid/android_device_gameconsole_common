-- The widget database (Widget.db) of Daijishou 1.5.0 (versionCode 416), Room schema
-- version 8, exactly as its WidgetDatabase_Impl.createAllTables makes it. The
-- gameconsole-emu service builds Widget.db from this plus the app shortcuts in
-- DJ_WIDGETS, so Daijishou's home page starts with them. The host test checks every
-- statement against the dex of apps/Daijishou/Daijishou.apk.
CREATE TABLE IF NOT EXISTS `WidgetInstanceEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `widget_id` TEXT NOT NULL, `widget_instance_id` INTEGER NOT NULL, `added_date_timestamp` INTEGER NOT NULL, `position` INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS `AppShortcutWidgetInstanceEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `resolve_info_string_id` TEXT, `alternative_image_path` TEXT, `last_update_timestamp` INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS `PinAndPlayWidgetInstanceEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playable_item_id` INTEGER, `alternative_image_path` TEXT, `last_update_timestamp` INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS `ImageBlockWidgetInstanceEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `image_path` TEXT, `last_update_timestamp` INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS `RSSWidgetInstanceEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `url` TEXT, `last_update_timestamp` INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS `PlaylistWidgetInstanceEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `whitelisted_keyword_list` TEXT NOT NULL, `blacklisted_keyword_list` TEXT NOT NULL, `whitelisted_platform_id_list` TEXT NOT NULL, `blacklisted_platform_id_list` TEXT NOT NULL, `whitelisted_playable_item_id_list` TEXT NOT NULL, `blacklisted_playable_item_id_list` TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS `ChecklistWidgetInstanceEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS `ChecklistWidgetChecklistItemEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `attached_widget_instance_id` INTEGER NOT NULL, `title` TEXT NOT NULL, `checked` INTEGER NOT NULL, `playable_item_id` INTEGER, `platform_id` INTEGER, `player_id` INTEGER, `parcelable` TEXT, `extra` TEXT NOT NULL);
CREATE UNIQUE INDEX IF NOT EXISTS `index_WidgetInstanceEntity_widget_id_widget_instance_id` ON `WidgetInstanceEntity` (`widget_id`, `widget_instance_id`);
CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT);
INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '109bd0715490395b90a901417c367810');
PRAGMA user_version = 8;
